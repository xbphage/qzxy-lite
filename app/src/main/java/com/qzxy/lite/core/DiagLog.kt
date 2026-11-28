package com.qzxy.lite.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

/**
 * 诊断日志（仅 diag 构建开启，见 BuildConfig.DIAG_LOG）。
 *
 * 为什么不用 logcat：Android 上 `READ_LOGS` 是系统权限，普通 App 读不到 logcat。
 * 所以这里自己记——App 业务日志 + 每一次 HTTP 请求/响应（参数与响应体），
 * 再提供"导出到下载目录 / 分享出去"两个出口，方便远程用户把文件发回来。
 *
 * 隐私：写盘和展示前统一脱敏（手机号、loginCode、密码、签名等），
 * 但仍建议只用于排查、排查完删除。
 */
object DiagLog {

    private const val MAX_MEMORY_LINES = 1500
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val DIR_NAME = "diag"
    private const val FILE_NAME = "qzxy-diag.log"

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val fileTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.CHINA)

    private val writeLock = Any()
    private val ringBuffer = ArrayDeque<String>(MAX_MEMORY_LINES)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var enabled = false

    /** 是否已初始化（UI 用来决定要不要显示诊断入口）。 */
    val isEnabled: Boolean get() = enabled

    fun init(context: Context) {
        if (enabled) return
        appContext = context.applicationContext
        enabled = true
        val boot = "===== diag start ${LocalDateTime.now().format(timeFmt)} " +
            "v${com.qzxy.lite.BuildConfig.VERSION_NAME}(${com.qzxy.lite.BuildConfig.VERSION_CODE}) " +
            "SDK ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER}/${Build.MODEL} ====="
        d("boot", boot)
        installCrashHandler()
    }

    fun d(tag: String, message: String) {
        if (!enabled) return
        val timestamp = LocalDateTime.now().format(timeFmt)
        val line = "$timestamp [$tag] ${redact(message)}"
        synchronized(writeLock) {
            if (ringBuffer.size >= MAX_MEMORY_LINES) ringBuffer.removeFirst()
            ringBuffer.addLast(line)
            _lines.value = ringBuffer.toList()
            runCatching {
                val f = logFile(appContext ?: return@runCatching) ?: return@runCatching
                if (f.length() > MAX_FILE_BYTES) {
                    f.writeText(line + "\n")
                } else {
                    f.appendText(line + "\n")
                }
            }
        }
    }

    /** 记录一次 HTTP 交互（已脱敏）。 */
    fun http(
        method: String,
        url: String,
        requestBody: String?,
        code: Int?,
        responseBody: String?,
        durationMs: Long,
    ) {
        if (!enabled) return
        val sb = StringBuilder()
        sb.append("$method ").append(url)
        if (!requestBody.isNullOrBlank()) sb.append("\n  → ").append(requestBody)
        sb.append("\n  ← HTTP ").append(code ?: "-").append(" (").append(durationMs).append("ms)")
        if (!responseBody.isNullOrBlank()) {
            sb.append(" ").append(responseBody.take(2000))
        }
        d("http", sb.toString())
    }

    /** 敏感值脱敏：JSON 字段与表单 kv 都处理。 */
    fun redact(text: String): String {
        var out = text
        SENSITIVE_KEYS.forEach { key ->
            // "key":"value" / key=value / key: value
            out = Regex("(\"$key\"\\s*:\\s*\")[^\"]*(\")").replace(out) { m -> "${m.groupValues[1]}***${m.groupValues[2]}" }
            out = Regex("(\\b$key=)[^&\\s]*").replace(out) { m -> "${m.groupValues[1]}***" }
        }
        // 手机号兜底
        out = Regex("(1[3-9]\\d)\\d{4}(\\d{4})").replace(out) { m -> "${m.groupValues[1]}****${m.groupValues[2]}" }
        return out
    }

    private val SENSITIVE_KEYS = listOf(
        "loginCode", "wfwapiLoginCode", "telephone", "telPhone", "password",
        "signature", "secret", "token", "access_token", "smsCode", "identifier",
        "cookie2", "_m_h5_tk", "x5sec", "_x5sec", "umidToken",
    )

    private fun logFile(context: Context): File? =
        File(context.getExternalFilesDir(DIR_NAME) ?: context.filesDir, FILE_NAME)
            .also { it.parentFile?.mkdirs() }

    /** 当前完整日志文本（导出/分享用）。 */
    fun dump(): String = _lines.value.joinToString("\n")

    fun clear(context: Context) {
        synchronized(writeLock) {
            ringBuffer.clear()
            _lines.value = emptyList()
            runCatching { logFile(context)?.delete() }
        }
        d("diag", "log cleared")
    }

    /** 导出到系统下载目录，返回 Uri（供 UI 提示保存位置）。 */
    fun exportToDownloads(context: Context): Uri? {
        val content = dump().ifBlank { "（无日志）" }
        val name = "qzxy-diag-${LocalDateTime.now().format(fileTimeFmt)}.log"
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val f = File(dir, name)
                f.writeText(content)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }
        }.getOrNull()
    }

    /** 把日志文件用系统分享面板发出去（微信/邮件等），方便用户回传。 */
    fun share(context: Context) {
        runCatching {
            val file = logFile(context) ?: return
            if (!file.exists()) file.writeText(dump())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "qzxy-lite 诊断日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(send, "发送诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 未捕获异常落盘：崩溃现场的堆栈是定位问题的关键。 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                d("crash", "thread=${thread.name}\n" + throwable.stackTraceToString().take(8000))
                appContext?.let { ctx ->
                    runCatching { exportToDownloads(ctx) }
                    runCatching { logFile(ctx)?.copyTo(File(ctx.filesDir, "crash-last.log"), overwrite = true) }
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}