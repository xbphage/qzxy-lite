package com.qzxy.lite.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.qzxy.lite.BuildConfig
import com.qzxy.lite.core.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** GitHub Release 元数据（只取更新所需字段）。 */
data class UpdateInfo(
    val version: String,   // tag_name，如 "v1.0.3"
    val title: String,
    val notes: String,     // release body
    val apkUrl: String,    // asset 的 browser_download_url
    val apkName: String,
    val apkSize: Long,
)

/** 已下载并完成本地签名校验、可直接拉起安装器的更新包。 */
data class ReadyUpdate(
    val version: String,
    val title: String,
    val notes: String,
    val apkFile: File,
    val installUri: Uri,
)

/**
 * 基于 GitHub Release 的应用内静默更新：
 * 1. 国内网络优先尝试 `https://gh-proxy.com/`（失败自动回退备用镜像与 GitHub 直连）；
 * 2. 发现新版本后在后台静默下载，完成 APK 包名、版本号与签名证书 SHA-256 校验后再弹窗提示安装；
 * 3. 已下载校验通过的安装包在重启应用后直接本地校验并提示安装，无需重新请求网络或重下；
 * 4. 覆盖安装完成后由 [com.qzxy.lite.QzxyApp] 的 `PACKAGE_REPLACED` 接收器或下次启动自动清理旧包。
 */
object UpdateManager {

    var githubOwner: String = "xbphage"
    var githubRepo: String = "qzxy-lite"
    private const val PRIMARY_GH_PROXY = "https://gh-proxy.com/"
    private const val FALLBACK_GH_PROXY = "https://ghproxy.net/"
    private val KNOWN_PROXIES = listOf(PRIMARY_GH_PROXY, FALLBACK_GH_PROXY)

    const val PREFS = "qzxy_update"
    private const val SETTINGS_PREFS = "qzxy_update_settings"
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    private const val KEY_READY_VERSION = "readyVersion"
    private const val KEY_READY_TITLE = "readyTitle"
    private const val KEY_READY_NOTES = "readyNotes"
    private const val KEY_READY_FILE = "readyFile"
    private const val KEY_READY_SHA256 = "readySha256"
    private const val KEY_PENDING_FILE = "pendingFile"

    private val autoUpdateState = MutableStateFlow(true)
    @Volatile private var autoUpdateLoaded = false

    private fun ensureAutoUpdateLoaded(context: Context): StateFlow<Boolean> {
        if (!autoUpdateLoaded) {
            synchronized(this) {
                if (!autoUpdateLoaded) {
                    autoUpdateState.value = context.applicationContext
                        .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                        .getBoolean(KEY_AUTO_UPDATE_ENABLED, true)
                    autoUpdateLoaded = true
                }
            }
        }
        return autoUpdateState.asStateFlow()
    }

    /** 响应式订阅「自动检查更新」开关状态，便于设置页与弹窗即时同步。 */
    fun autoUpdateEnabledFlow(context: Context): StateFlow<Boolean> = ensureAutoUpdateLoaded(context)

    /** 是否开启自动检查与静默下载更新（独立存储，不受 clearPending 影响）。 */
    fun isAutoUpdateEnabled(context: Context): Boolean = ensureAutoUpdateLoaded(context).value

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        ensureAutoUpdateLoaded(context)
        context.applicationContext
            .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled)
            .apply()
        autoUpdateState.value = enabled
    }

    private val downloadMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    internal fun buildCandidateUrls(rawUrl: String): List<String> {
        val canonical = stripKnownProxyPrefix(rawUrl.trim())
        if (canonical.isBlank()) return emptyList()
        return listOf(
            "$PRIMARY_GH_PROXY$canonical",
            "$FALLBACK_GH_PROXY$canonical",
            canonical,
        ).distinct()
    }

    private fun stripKnownProxyPrefix(url: String): String {
        for (prefix in KNOWN_PROXIES) {
            if (url.startsWith(prefix, ignoreCase = true)) {
                return url.substring(prefix.length)
            }
        }
        return url
    }

    /** 语义化版本比较：v1.0.3 vs 1.0.2 → true（有更新）。非数字段按 0 处理。 */
    fun isNewer(remote: String, current: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").removePrefix("V")
            .split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parse(remote)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val l = a.getOrElse(i) { 0 }
            val r = b.getOrElse(i) { 0 }
            if (l != r) return l > r
        }
        return false
    }

    internal fun matchingSignerDigests(
        apkSigners: Set<String>,
        installedSigners: Set<String>,
    ): Boolean = apkSigners.isNotEmpty() && apkSigners.any { it in installedSigners }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(uppercase = false)

    /**
     * 检查本地是否已有已下载且签名校验通过的新版本。
     * 若当前应用已升级到等于或高于缓存版本，则自动清理旧安装包并返回 null。
     * 本方法全程离线运行，不会触发任何网络请求。
     */
    suspend fun getVerifiedReadyUpdate(
        context: Context,
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): ReadyUpdate? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_READY_VERSION, null)?.takeIf { it.isNotBlank() } ?: return@withContext null
        val filePath = prefs.getString(KEY_READY_FILE, null)?.takeIf { it.isNotBlank() } ?: return@withContext null
        val expectedSha256 = prefs.getString(KEY_READY_SHA256, null)?.takeIf { it.isNotBlank() } ?: return@withContext null

        if (!isNewer(version, currentVersion)) {
            clearPending(appContext)
            return@withContext null
        }

        val file = File(filePath)
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            clearPending(appContext)
            return@withContext null
        }

        val intactAndSigned = runCatching {
            sha256FileHex(file) == expectedSha256 && verifyDownloadedApk(appContext, file)
        }.getOrDefault(false)

        if (!intactAndSigned) {
            clearPending(appContext)
            return@withContext null
        }

        ReadyUpdate(
            version = version,
            title = prefs.getString(KEY_READY_TITLE, "").orEmpty(),
            notes = prefs.getString(KEY_READY_NOTES, "").orEmpty(),
            apkFile = file,
            installUri = fileUri(appContext, file),
        )
    }

    /** 拉取最新 Release；优先走 `https://gh-proxy.com/`，失败自动回退。 */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val apiUrl = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"
        for (candidate in buildCandidateUrls(apiUrl)) {
            val info = runCatching {
                val req = Request.Builder()
                    .url(candidate)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "qzxy-lite-Android")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .build()
                client.newCall(req).execute().use { rsp ->
                    if (!rsp.isSuccessful) return@runCatching null
                    val text = rsp.body?.string().orEmpty()
                    if (text.isBlank()) return@runCatching null
                    val obj = json.parseToJsonElement(text).jsonObject
                    val candidates = obj["assets"]?.jsonArray.orEmpty().map { assetEl ->
                        val assetObj = assetEl.jsonObject
                        ApkAssetCandidate(
                            name = assetObj["name"]?.jsonPrimitive?.content.orEmpty(),
                            downloadUrl = stripKnownProxyPrefix(
                                assetObj["browser_download_url"]?.jsonPrimitive?.content.orEmpty(),
                            ),
                            size = assetObj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        )
                    }
                    val apk = UpdateDecision.selectPreferredApkAsset(candidates)
                        ?: return@runCatching null
                    UpdateInfo(
                        version = obj["tag_name"]?.jsonPrimitive?.content.orEmpty(),
                        title = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                        notes = obj["body"]?.jsonPrimitive?.content.orEmpty(),
                        apkUrl = apk.downloadUrl,
                        apkName = apk.name,
                        apkSize = apk.size,
                    )
                }
            }.getOrNull()
            if (info != null && info.version.isNotBlank() && info.apkUrl.isNotBlank()) {
                return@withContext info
            }
        }
        null
    }

    /**
     * 静默下载并校验签名：
     * - 若对应版本已本地就绪且签名完整，直接复用（零网络下载）；
     * - 否则走镜像通道下载到应用私有更新目录，必要时解包 zip；
     * - 校验包名、版本号及与当前安装包一致的签名证书（且拒绝无签名包）；
     * - 校验通过后再写入就绪元信息，供当前或下次打开应用直接提示安装。
     */
    suspend fun prepareUpdateSilently(
        context: Context,
        info: UpdateInfo,
    ): ReadyUpdate = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            val appContext = context.applicationContext
            val existing = getVerifiedReadyUpdate(appContext, BuildConfig.VERSION_NAME)
            if (existing != null && !isNewer(info.version, existing.version)) {
                return@withLock existing
            }

            clearPending(appContext)
            val dir = apkStorageDir(appContext)
            val tmpFile = File(dir, "download.tmp")
            val finalFile = File(dir, sanitizeApkFileName(info.apkName))
            try {
                downloadWithMirrors(info.apkUrl, tmpFile)
                normalizeToApkFile(tmpFile, finalFile)
                if (!verifyDownloadedApk(appContext, finalFile)) {
                    throw SecurityException("安装包签名校验失败（未签名或签名与当前应用不一致）")
                }
                val fileSha256 = sha256FileHex(finalFile)
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_READY_VERSION, info.version)
                    .putString(KEY_READY_TITLE, info.title)
                    .putString(KEY_READY_NOTES, info.notes)
                    .putString(KEY_READY_FILE, finalFile.absolutePath)
                    .putString(KEY_READY_SHA256, fileSha256)
                    .putString(KEY_PENDING_FILE, finalFile.absolutePath)
                    .apply()
                ReadyUpdate(
                    version = info.version,
                    title = info.title,
                    notes = info.notes,
                    apkFile = finalFile,
                    installUri = fileUri(appContext, finalFile),
                )
            } catch (t: Throwable) {
                tmpFile.delete()
                finalFile.delete()
                throw t
            }
        }
    }

    private fun downloadWithMirrors(rawUrl: String, destFile: File) {
        var lastError: Throwable? = null
        for (url in buildCandidateUrls(rawUrl)) {
            destFile.delete()
            val attempt = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "qzxy-lite-Android")
                    .build()
                client.newCall(req).execute().use { rsp ->
                    if (!rsp.isSuccessful) throw IllegalStateException("HTTP ${rsp.code}")
                    val mediaType = rsp.body?.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                    if (mediaType.equals("text/html", ignoreCase = true)) {
                        throw IllegalStateException("镜像返回异常页面而非安装包")
                    }
                    val body = rsp.body ?: throw IllegalStateException("下载失败：空响应")
                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    if (!destFile.exists() || destFile.length() <= 0L) {
                        throw IllegalStateException("下载文件为空")
                    }
                }
            }
            if (attempt.isSuccess) return
            destFile.delete()
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("下载失败：所有下载通道均不可用")
    }

    private fun normalizeToApkFile(tmpFile: File, targetFile: File) {
        val apkEntryName: String?
        val isDirectApk = try {
            // 通过 ZipFile 读取中央目录（O(1)），避免流式全包扫描两遍大文件
            ZipFile(tmpFile).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") != null) {
                    apkEntryName = null
                    true
                } else {
                    val entries = zip.entries()
                    var found: String? = null
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            found = name
                            break
                        }
                    }
                    apkEntryName = found
                    false
                }
            }
        } catch (t: Throwable) {
            throw IllegalStateException("安装包格式损坏（非有效压缩包）", t)
        }

        if (isDirectApk) {
            targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            return
        }

        val entryName = apkEntryName ?: throw IllegalStateException("下载内容不是有效的安装包")
        targetFile.delete()
        ZipFile(tmpFile).use { zip ->
            val entry = zip.getEntry(entryName) ?: throw IllegalStateException("安装包解压失败")
            zip.getInputStream(entry).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            }
        }
        tmpFile.delete()
    }

    /** 校验已下载 APK 的包名、版本号（防降级）及签名证书 SHA-256（需与当前已安装应用同签且非空）。 */
    private fun verifyDownloadedApk(context: Context, apkFile: File): Boolean {
        val pm = context.packageManager
        val flags = signingQueryFlags()
        val archiveInfo = getPackageArchiveCompat(pm, apkFile.absolutePath, flags) ?: return false
        if (archiveInfo.packageName != context.packageName) return false

        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        if (archiveVersionCode < BuildConfig.VERSION_CODE.toLong()) return false

        val apkSigners = extractSignerDigests(archiveInfo, includeHistory = false)
        if (apkSigners.isEmpty()) return false

        val installedInfo = getInstalledPackageCompat(pm, context.packageName, flags) ?: return false
        val installedSigners = extractSignerDigests(installedInfo, includeHistory = true)
        return matchingSignerDigests(apkSigners, installedSigners)
    }

    private fun signingQueryFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun getPackageArchiveCompat(pm: PackageManager, path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, flags)
        }

    private fun getInstalledPackageCompat(pm: PackageManager, pkgName: String, flags: Int): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, flags)
            }
        }.getOrNull()

    private fun extractSignerDigests(info: PackageInfo, includeHistory: Boolean): Set<String> {
        val certs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else if (includeHistory && signingInfo.signingCertificateHistory != null) {
                signingInfo.signingCertificateHistory
            } else {
                signingInfo.apkContentsSigners
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return certs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun sha256FileHex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().toHexString(uppercase = false)
    }

    private fun apkStorageDir(context: Context): File =
        (context.getExternalFilesDir("apk") ?: File(context.filesDir, "apk")).apply { mkdirs() }

    private fun sanitizeApkFileName(rawName: String): String {
        val safe = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.endsWith(".apk", ignoreCase = true) }
        return safe ?: "qzxy-lite-update.apk"
    }

    /** 把本地更新目录里的文件包装为可授权读取的 FileProvider content:// Uri。 */
    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** 拉起系统安装器（应用内完成，不跳浏览器）。 */
    @Suppress("DEPRECATION")
    fun install(context: Context, uri: Uri) {
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    /** 是否允许安装未知来源（Android 8+ 需用户授权过一次）。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** 未授权时跳系统"安装未知应用"设置页（仅一次授权，非浏览器）。 */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 清理已缓存/待安装的更新文件与元数据。 */
    fun clearPending(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        listOf(KEY_READY_FILE, KEY_PENDING_FILE).forEach { key ->
            prefs.getString(key, null)?.let { path -> runCatching { File(path).delete() } }
        }
        apkStorageDir(appContext).listFiles()?.forEach { runCatching { it.delete() } }
        prefs.edit().clear().apply()
    }
}
