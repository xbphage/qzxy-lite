package com.qzxy.lite.data.network

import com.qzxy.lite.core.ApiConstants
import com.qzxy.lite.data.session.Session
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 会话失效拦截器：仅对 v3-api 的响应生效（wfwapi 不认 v3 会话，其 errorCode=8 不应清掉本地会话）。
 * 响应体出现 errorCode=8 时：
 * 1. 若开启了保持登录且有本地凭据，优先进行同步静默登录续期，并无感重新发起本次请求（单请求最多重试 1 次）；
 * 2. 并发场景下若其它线程已刷新过 loginCode，直接复用新会话重试，避免重复登录相互顶号；
 * 3. 若无法自动恢复，回调 onFinalExpired（清会话 → UI 跳登录）。
 */
class SessionExpiredInterceptor(
    private val currentSessionToken: () -> String? = { null },
    private val canSilentReLogin: () -> Boolean = { false },
    private val performSilentReLogin: () -> Boolean = { false },
    private val onFinalExpired: () -> Unit = {},
) : Interceptor {

    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val alreadyRetried = request.header(CommonParamsInterceptor.HEADER_RETRIED) != null
        val tokenBefore = currentSessionToken()
        val response = chain.proceed(request)
        if (!alreadyRetried && response.isSuccessful && request.url.host == SESSION_HOST) {
            val hasExpiredCode = runCatching {
                val text = response.peekBody(16 * 1024).string()
                ERROR_CODE_8.containsMatchIn(text)
            }.getOrDefault(false)

            if (hasExpiredCode) {
                if (canSilentReLogin()) {
                    val recovered = synchronized(lock) {
                        val latestToken = currentSessionToken()
                        if (!tokenBefore.isNullOrBlank() &&
                            !latestToken.isNullOrBlank() &&
                            latestToken != tokenBefore
                        ) {
                            true
                        } else {
                            performSilentReLogin()
                        }
                    }
                    if (recovered) {
                        // 静默重连成功：关闭旧响应，带一次性重试标记重走后续拦截器完成本次请求
                        response.close()
                        val retried = request.newBuilder()
                            .header(CommonParamsInterceptor.HEADER_RETRIED, "1")
                            .build()
                        return chain.proceed(retried)
                    }
                }
                onFinalExpired()
            }
        }
        return response
    }

    private companion object {
        const val SESSION_HOST = "v3-api.china-qzxy.cn"
        // 精确匹配 errorCode":8，避免误伤 80/81 等数值。
        val ERROR_CODE_8 = Regex("\"errorCode\"\\s*:\\s*8(?![0-9])")
    }
}

/**
 * 诊断版专用：把每次请求/响应（含表单参数与响应体）写入 [com.qzxy.lite.core.DiagLog]。
 * 只在 diag 构建启用；写入前统一脱敏（手机号、loginCode、密码等）。
 */
class DiagHttpInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.currentTimeMillis()
        val reqBody = request.body?.let { body ->
            runCatching {
                val buffer = Buffer()
                body.writeTo(buffer)
                // 表单是 application/x-www-form-urlencoded 时 buffer 里就是 kv 串
                buffer.readUtf8()
            }.getOrNull()
        }
        val response = chain.proceed(request)
        val text = runCatching { response.peekBody(64 * 1024).string() }.getOrNull()
        com.qzxy.lite.core.DiagLog.http(
            method = request.method,
            url = request.url.toString(),
            requestBody = reqBody,
            code = response.code,
            responseBody = text,
            durationMs = System.currentTimeMillis() - started,
        )
        return response
    }
}

/**
 * 网络层装配：统一超时（连接 10s / 读取 30s）、公共参数拦截器、kotlinx-serialization 转换器。
 */
object NetworkModule {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun okHttp(
        sessionResolver: (host: String) -> Session?,
        currentSessionToken: () -> String? = { null },
        canSilentReLogin: () -> Boolean = { false },
        performSilentReLogin: () -> Boolean = { false },
        debug: Boolean = false,
        diag: Boolean = false,
        onSessionExpired: () -> Unit = {},
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(SimpleCookieJar())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                SessionExpiredInterceptor(
                    currentSessionToken = currentSessionToken,
                    canSilentReLogin = canSilentReLogin,
                    performSilentReLogin = performSilentReLogin,
                    onFinalExpired = onSessionExpired,
                )
            )
            .addInterceptor(CommonParamsInterceptor(sessionResolver, json))
            .apply {
                if (diag) addInterceptor(DiagHttpInterceptor())
                if (debug) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    })
                }
            }
            .build()

    fun retrofit(client: OkHttpClient, baseUrl: String = ApiConstants.WFW_API): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(
                json.asConverterFactory("application/json; charset=utf-8".toMediaType())
            )
            .build()
}

/**
 * 简单 CookieJar：按 host 与 cookie.name 增量合并保存并剔除过期 Cookie。
 * 避免单一 Set-Cookie 响应整包覆写掉阿里云 WAF Cookie（aliyungf_tc/acw_tc）。
 */
internal class SimpleCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val hostCookies = store.getOrPut(url.host) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            if (cookie.expiresAt < now || cookie.value.isEmpty()) {
                hostCookies.remove(cookie.name)
            } else {
                hostCookies[cookie.name] = cookie
            }
        }
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
        val hostCookies = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        hostCookies.entries.removeIf { (_, c) -> c.expiresAt < now }
        return hostCookies.values.filter { it.matches(url) }
    }
}
