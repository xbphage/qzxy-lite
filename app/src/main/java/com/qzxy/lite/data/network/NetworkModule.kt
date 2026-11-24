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
import java.util.concurrent.TimeUnit

/**
 * 会话失效拦截器：仅对 v3-api 的响应生效（wfwapi 不认 v3 会话，其 errorCode=8 不应清掉本地会话）。
 * 响应体出现 errorCode=8 时回调 onExpired（清会话 → UI 跳登录）。
 */
class SessionExpiredInterceptor(
    private val onExpired: () -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.isSuccessful && request.url.host == SESSION_HOST) {
            runCatching {
                val text = response.peekBody(16 * 1024).string()
                if (ERROR_CODE_8.containsMatchIn(text)) onExpired()
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
        debug: Boolean = false,
        diag: Boolean = false,
        onSessionExpired: () -> Unit = {},
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(SimpleCookieJar())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(CommonParamsInterceptor(sessionResolver, json))
            .addInterceptor(SessionExpiredInterceptor(onSessionExpired))
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
 * 简单 CookieJar：按 host 保存/携带 Cookie（进程内有效）。
 * 原版 App 带阿里云 WAF Cookie（aliyungf_tc/acw_tc），无 Cookie 的客户端其请求会被
 * WAF 拦截（实测：无 Cookie 时请求挂起无响应）。Cookie 非敏感（WAF 防护用）。
 */
private class SimpleCookieJar : CookieJar {
    private val store = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
        if (cookies.isNotEmpty()) store[url.host] = cookies
    }

    override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
        store[url.host] ?: emptyList()
}
