package com.qzxy.lite.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** GitHub Release 元数据（只取更新所需字段）。 */
data class UpdateInfo(
    val version: String,   // tag_name，如 "v0.1.1"
    val title: String,
    val notes: String,     // release body
    val apkUrl: String,    // asset 的 browser_download_url
    val apkName: String,
    val apkSize: Long,
)

/**
 * 基于 GitHub Release 的应用内更新：
 * 检查（releases/latest，公开仓库免鉴权）→ 应用内下载到系统 Downloads →
 * 拉起系统安装器 → 覆盖安装后由 [com.qzxy.lite.QzxyApp] 里注册的
 * ACTION_PACKAGE_REPLACED 接收器删除下载文件（"安装完毕后自行删除"）。
 *
 * 不跳浏览器、不嵌任何私钥：检查/下载走 GitHub 公开 OpenAPI 与 Release 附件直链。
 */
object UpdateManager {

    /** GitHub 仓库拥有者与仓库名（默认配置为当前作者账号 xiaobaphage/qzxy-lite） */
    var githubOwner: String = "xiaobaphage"
    var githubRepo: String = "qzxy-lite"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 下载/待安装信息落盘 key（安装完成后清理用）。 */
    const val PREFS = "qzxy_update"
    const val KEY_PENDING_URI = "pendingUri"
    const val KEY_PENDING_FILE = "pendingFile"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** 拉取最新 Release；无 Release/网络失败返回 null（静默，不打扰用户）。 */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            // 请求 GitHub Releases OpenAPI (官方规范要求携带 User-Agent)
            val apiUrl = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"
            val req = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "qzxy-lite-Android")
                .build()
            client.newCall(req).execute().use { rsp ->
                if (!rsp.isSuccessful) return@runCatching null
                val obj = json.parseToJsonElement(rsp.body!!.string()).jsonObject
                val apk = obj["assets"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .firstOrNull { (it["name"]?.jsonPrimitive?.content ?: "").endsWith(".apk", true) }
                    ?: return@runCatching null
                UpdateInfo(
                    version = obj["tag_name"]?.jsonPrimitive?.content.orEmpty(),
                    title = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                    notes = obj["body"]?.jsonPrimitive?.content.orEmpty(),
                    apkUrl = apk["browser_download_url"]?.jsonPrimitive?.content.orEmpty(),
                    apkName = apk["name"]?.jsonPrimitive?.content.orEmpty(),
                    apkSize = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                )
            }
        }.getOrNull()
    }

    /** 语义化版本比较：v0.1.1 vs 0.1.0 → true（有更新）。非数字段按 0 处理。 */
    fun isNewer(remote: String, current: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").removePrefix("V")
            .split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parse(remote); val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val l = a.getOrElse(i) { 0 }; val r = b.getOrElse(i) { 0 }
            if (l != r) return l > r
        }
        return false
    }

    private class Sink(
        val uri: Uri,
        val file: File?,
        private val openStream: () -> OutputStream,
    ) {
        fun stream(): OutputStream = openStream()
        fun cleanup(context: Context) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            file?.delete()
        }
    }

    /**
     * 流式下载 APK：
     * - API 29+ 写入系统 Downloads（MediaStore，无需存储权限）；
     * - API 26-28 写公共 Downloads 目录（WRITE_EXTERNAL_STORAGE，maxSdk=28 已声明），
     *   不可写时退回应用专属目录。
     * Gitee 对 Release 附件下载会套一层 zip，因此先落到临时名，
     * 下载完成后 [normalizeToApk] 归一成 .apk 再返回。
     * 下载前先清掉上一次的待安装残留；进度回调 [0,100]，已切主线程。
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        clearPending(context)

        val req = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", "qzxy-lite-Android")
            .build()
        val rsp = try {
            val r = client.newCall(req).execute()
            if (r.isSuccessful) r else throw IllegalStateException("HTTP ${r.code}")
        } catch (e: Exception) {
            // 若国内直连 GitHub 遇阻，尝试加速镜像节点下载
            val mirrorReq = Request.Builder()
                .url("https://ghproxy.net/" + info.apkUrl)
                .header("User-Agent", "qzxy-lite-Android")
                .build()
            runCatching { client.newCall(mirrorReq).execute() }.getOrNull()?.takeIf { it.isSuccessful }
                ?: throw e
        }
        val body = rsp.body ?: throw IllegalStateException("下载失败：空响应")
        val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize

        val appContext = context.applicationContext
        val temp = openSink(appContext, info.apkName + ".zip")
        try {
            body.byteStream().use { input ->
                temp.stream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onProgress(100) }
                }
            }
        } catch (t: Throwable) {
            temp.cleanup(appContext)
            throw t
        }

        // Gitee 会把附件打包成 zip：这里统一归一成 .apk（需要时解包）。
        val final = try {
            normalizeToApk(appContext, temp, info)
        } catch (t: Throwable) {
            temp.cleanup(appContext)
            throw t
        }

        // 记录待安装项：装完由 PACKAGE_REPLACED 接收器删除。
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING_URI, final.uri.toString())
            .putString(KEY_PENDING_FILE, final.file?.absolutePath)
            .apply()
        final.uri
    }

    private fun openSink(context: Context, displayName: String): Sink {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入下载目录")
            return Sink(uri, null) { context.contentResolver.openOutputStream(uri)!! }
        }
        val public = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            displayName,
        )
        val target = if (public.parentFile?.canWrite() == true) public
        else File(context.getExternalFilesDir("apk"), displayName).also { it.parentFile?.mkdirs() }
        return Sink(fileUri(context, target), target) { target.outputStream() }
    }

    /**
     * 把下载产物归一成 .apk：
     * - 内容本身是 APK（zip 容器含 AndroidManifest.xml）→ 重命名为目标名；
     * - 内容是 Gitee 打包的 zip（内含 *.apk）→ 解出 APK 写入目标名，并删除 zip；
     * - 都不是 → 报错。
     */
    private fun normalizeToApk(context: Context, temp: Sink, info: UpdateInfo): Sink {
        val entryNames = mutableListOf<String>()
        runCatching {
            val raw = context.contentResolver.openInputStream(temp.uri)
                ?: temp.file?.inputStream()
                ?: throw IllegalStateException("下载内容无法读取")
            raw.use { ins ->
                ZipInputStream(BufferedInputStream(ins)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        entryNames.add(e.name)
                        e = zis.nextEntry
                    }
                }
            }
        }.onFailure { return renameTo(context, temp, info.apkName) } // 读不出结构就按原样交付

        val looksLikeApk = entryNames.any { it == "AndroidManifest.xml" }
        if (looksLikeApk) return renameTo(context, temp, info.apkName)

        val apkEntry = entryNames.firstOrNull { it.endsWith(".apk", true) }
            ?: throw IllegalStateException("下载内容不是有效的安装包")

        // zip 里包着 apk：解出写入最终位置
        val final = openSink(context, info.apkName)
        try {
            val raw = context.contentResolver.openInputStream(temp.uri)
                ?: temp.file?.inputStream()
                ?: throw IllegalStateException("下载内容无法读取")
            raw.use { ins ->
                ZipInputStream(BufferedInputStream(ins)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null && e.name != apkEntry) e = zis.nextEntry
                    if (e == null) throw IllegalStateException("安装包解压失败")
                    final.stream().use { output -> zis.copyTo(output) }
                }
            }
        } catch (t: Throwable) {
            final.cleanup(context)
            throw t
        }
        temp.cleanup(context)
        return final
    }

    /** 把已下载内容改成正式 .apk 文件名（MediaStore 改 DISPLAY_NAME / 文件 rename）。 */
    private fun renameTo(context: Context, sink: Sink, newName: String): Sink {
        if (sink.file != null) {
            val target = File(sink.file.parentFile, newName)
            if (sink.file.renameTo(target)) {
                return Sink(fileUri(context, target), target) { target.outputStream() }
            }
            return sink
        }
        runCatching {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
            }
            context.contentResolver.update(sink.uri, values, null, null)
        }
        return sink
    }

    /** <29 时把 File 包装成可授予读取权限的 content:// Uri。 */
    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** 拉起系统安装器（应用内完成，不跳浏览器）。 */
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

    /** 清理上一次下载残留（MediaStore 记录 + 文件）。 */
    fun clearPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PENDING_URI, null)?.let {
            runCatching { context.contentResolver.delete(Uri.parse(it), null, null) }
        }
        prefs.getString(KEY_PENDING_FILE, null)?.let { runCatching { File(it).delete() } }
        prefs.edit().remove(KEY_PENDING_URI).remove(KEY_PENDING_FILE).apply()
    }
}
