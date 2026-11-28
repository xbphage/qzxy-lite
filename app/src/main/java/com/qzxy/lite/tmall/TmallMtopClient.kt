package com.qzxy.lite.tmall

import com.qzxy.lite.core.md5Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** mtop 网关或网络层异常。 */
class TmallMtopError(
    val ret: List<String>,
    val payload: String = "",
) : Exception(ret.firstOrNull() ?: "UNKNOWN") {

    val code: String = ret.firstOrNull()?.substringBefore("::") ?: "UNKNOWN"
    val detail: String = ret.firstOrNull()?.substringAfter("::", "") ?: ""
    val retryable: Boolean get() = code in TmallMtopClient.RETRY_CODES

    fun human(): String = TmallMtopClient.CODES[code] ?: detail.ifBlank { code }
}

/** 紧凑 JSON 序列化 —— 保证与 mtop 网关签名计算字符串完全一致（无多余空格、中文不转义）。 */
object TmallJson {

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> {
                if (v == Math.rint(v) && !v.isInfinite()) sb.append(v.toLong().toString())
                else sb.append(v.toString())
            }
            is Float -> write(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, item) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, item)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

/**
 * 淘宝 / 天猫校园饮水 H5 mtop 网关客户端（迁移自 lmc2007/TMallKey）。
 *
 * 负责：
 * - mtop URL 组装与 `md5(token & t & appKey & data)` 签名；
 * - `_m_h5_tk` 首次空令牌自动握手与重试；
 * - 严格隔离 `.confong.cn` 作用域的 `cookie2`，不被 `.taobao.com` 同名 Cookie 覆盖。
 */
class TmallMtopClient(
    cookies: MutableMap<String, String> = linkedMapOf(),
    private val appKey: String = APP_KEY,
    private val timeoutMs: Int = 15_000,
    private val transport: ((url: String, cookieHeader: String) -> Pair<String, List<String>>)? = null,
) {
    private val cookies: LinkedHashMap<String, String> = LinkedHashMap(cookies)

    @Synchronized
    fun token(): String = parseToken(cookies[TOKEN_COOKIE])

    @Synchronized
    fun cookieCount(): Int = cookies.size

    @Synchronized
    fun export(): Map<String, String> = LinkedHashMap(cookies)

    @Synchronized
    fun merge(extra: Map<String, String>) {
        extra.forEach { (k, v) -> if (k.isNotBlank()) cookies[k] = v }
    }

    fun call(
        api: String,
        data: Map<String, Any?> = emptyMap(),
        version: String = "1.0",
        maxRetry: Int = 2,
    ): String {
        var last: TmallMtopError? = null
        val payload = TmallJson.stringify(data)
        for (attempt in 0..maxRetry) {
            val t = System.currentTimeMillis().toString()
            val url = buildUrl(api, version, t, payload)
            val (body, setCookies) = transport?.invoke(url, cookieHeader()) ?: httpGet(url)
            absorb(setCookies)

            val ret = parseRet(body)
            if (ret.isNotEmpty() && ret[0].startsWith("SUCCESS")) return body

            val err = TmallMtopError(ret, body)
            last = err
            if (attempt < maxRetry && err.retryable) continue
            throw err
        }
        throw last ?: TmallMtopError(listOf("FAIL_SYS_UNKNOWN::未知失败"))
    }

    private fun buildUrl(api: String, version: String, t: String, payload: String): String {
        val currentToken = token()
        val q = listOf(
            "jsv" to "2.7.2",
            "appKey" to appKey,
            "t" to t,
            "sign" to sign(currentToken, t, appKey, payload),
            "api" to api,
            "v" to version,
            "type" to "originaljson",
            "dataType" to "json",
            "data" to payload,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "$GATEWAY_PREFIX$api/$version/?$q"
    }

    private fun httpGet(url: String): Pair<String, List<String>> {
        val cookieStr = cookieHeader()
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://share.confong.cn/")
                setRequestProperty("Accept", "application/json")
                if (cookieStr.isNotEmpty()) setRequestProperty("Cookie", cookieStr)
            }
        } catch (e: Throwable) {
            throw TmallMtopError(listOf("NETWORK_ERROR::${e.message ?: e.javaClass.simpleName}"))
        }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val setCookies = conn.headerFields["Set-Cookie"] ?: emptyList()
            if (body.isBlank()) throw TmallMtopError(listOf("NETWORK_ERROR::HTTP $code 空响应"))
            body to setCookies
        } catch (e: TmallMtopError) {
            throw e
        } catch (e: Throwable) {
            throw TmallMtopError(listOf("NETWORK_ERROR::${e.message ?: e.javaClass.simpleName}"))
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 吸收 Set-Cookie：已存在 `cookie2` 时，仅允许 `.confong.cn` 域将其覆盖，
     * 避免 `.taobao.com` 下发的同名单点 Cookie 顶掉校园（CM）会话。
     */
    @Synchronized
    internal fun absorb(setCookies: List<String>) {
        for (raw in setCookies) {
            val first = raw.substringBefore(';')
            val name = first.substringBefore('=', "").trim()
            if (name.isEmpty() || !first.contains('=')) continue
            if (name == "cookie2" && cookies.containsKey("cookie2") && !isConfongScoped(raw)) continue
            cookies[name] = first.substringAfter('=', "")
        }
    }

    private fun isConfongScoped(setCookie: String): Boolean {
        val low = setCookie.lowercase()
        val idx = low.indexOf("domain=")
        if (idx < 0) return false
        val domain = low.substring(idx + "domain=".length).substringBefore(';').trim().trimStart('.')
        return domain == CONFONG_DOMAIN || domain.endsWith(".$CONFONG_DOMAIN")
    }

    @Synchronized
    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    companion object {
        const val APP_KEY = "12574478"
        const val CONFONG_DOMAIN = "confong.cn"
        private const val GATEWAY_PREFIX = "https://h5api.m.taobao.com/h5/"
        private const val TOKEN_COOKIE = "_m_h5_tk"

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"

        val RETRY_CODES = setOf(
            "FAIL_SYS_TOKEN_EMPTY",
            "FAIL_SYS_TOKEN_EXOIRED",
            "FAIL_SYS_TOKEN_EXPIRED",
            "FAIL_SYS_SESSION_EXPIRED",
            "FAIL_SYS_ILLEGAL_ACCESS",
        )

        val CODES: Map<String, String> = mapOf(
            "1048" to "订单未找到",
            "1018" to "业务类型不合法",
            "1077" to "ISV 不合法",
            "1106" to "缺少设备资源号（二维码未识别到设备编号）",
            "UNKNOWN_DEVICE" to "未知的设备（该编号可能不在当前校区）",
            "999" to "服务端风控或缺少校园参数",
            "CAMPUS_AREA_INFO_ID_NULL" to "缺少校区标识（campusAreaInfoId）",
            "FAIL_SYS_BIZPARAM_MISSED" to "缺少业务参数",
            "FAIL_SYS_UNAUTHORIZED_ENTRANCE" to "接口未授权",
            "FAIL_SYS_TOKEN_EMPTY" to "令牌为空，正在重试握手",
            "FAIL_SYS_SESSION_EXPIRED" to "登录态已过期，请重新登录",
            "GET_CAMPUS_USER_ERROR" to "当前账号未激活天猫校园档案或缺少校园会话",
        )

        fun sign(token: String, t: String, appKey: String, data: String): String =
            md5Hex("$token&$t&$appKey&$data")

        fun parseToken(cookieValue: String?): String =
            cookieValue?.substringBefore('_')?.trim() ?: ""

        private val json = Json { ignoreUnknownKeys = true }

        fun parseRet(body: String): List<String> = runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
            val arr = obj["ret"] as? JsonArray ?: return emptyList()
            arr.mapNotNull { (it as? JsonPrimitive)?.content }
        }.getOrDefault(emptyList())
    }
}
