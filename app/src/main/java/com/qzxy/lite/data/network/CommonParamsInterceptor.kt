package com.qzxy.lite.data.network

import com.qzxy.lite.core.ApiConstants
import com.qzxy.lite.data.session.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * 公共参数 + 请求头拦截器（对应官方封装 t0()/u0()，notes/API.md §2 Observed）：
 * - 所有 china-qzxy.cn 域名请求自动附加 loginCode/userId/accountId/projectId/telephone/telPhone/phoneSystem/version；
 * - GET 拼进 query；表单 POST 附加字段；JSON POST 合并进 JSON 对象；其余（如文件上传）不改写；
 * - 请求头注入 Config-Project: <projectId> 与 Config-Keys；
 * - **按域名取会话**：v3-api 与 wfwapi 会话相互独立（实测同 loginCode 在 wfwapi 返回 errorCode=8），
 *   wfwapi 请求优先使用密码登录（user/login）签发的 wfwapiLoginCode。
 */
class CommonParamsInterceptor(
    private val sessionResolver: (host: String) -> Session?,
    private val json: Json = Json,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.endsWith(HOST_SUFFIX)) return chain.proceed(request)

        val session = sessionResolver(request.url.host) ?: return chain.proceed(request)
        val builder = request.newBuilder()

        if (session.projectId.isNotBlank()) {
            builder.header(HEADER_PROJECT, session.projectId)
        }
        builder.header(HEADER_KEYS, ApiConstants.CONFIG_KEYS)

        val params = session.asCommonParams().filterValues { it.isNotBlank() }
        when (request.method) {
            "GET", "HEAD", "DELETE" -> {
                val url = request.url.newBuilder().apply {
                    // 请求已显式声明的业务参数不重复注入（如登录链自带的 phoneSystem/version）。
                    params.forEach { (k, v) ->
                        if (request.url.queryParameter(k) == null) addQueryParameter(k, v)
                    }
                }.build()
                builder.url(url)
            }

            else -> rewriteBody(builder, request, params)
        }
        return chain.proceed(builder.build())
    }

    private fun rewriteBody(builder: Request.Builder, request: Request, params: Map<String, String>) {
        val body = request.body
        when {
            body is FormBody -> {
                val form = FormBody.Builder()
                val existing = buildSet {
                    for (i in 0 until body.size) {
                        add(body.name(i))
                        form.add(body.name(i), body.value(i))
                    }
                }
                params.forEach { (k, v) -> if (k !in existing) form.add(k, v) }
                builder.method(request.method, form.build())
            }

            body != null && body.isJson() -> {
                val buffer = Buffer()
                body.writeTo(buffer)
                val original: JsonObject? = runCatching {
                    json.parseToJsonElement(buffer.readUtf8()).jsonObject
                }.getOrNull()
                val merged = buildJsonObject {
                    original?.forEach { (k, v) -> put(k, v) }
                    params.forEach { (k, v) -> if (original?.containsKey(k) != true) put(k, JsonPrimitive(v)) }
                }
                builder.method(request.method, merged.toString().toRequestBody(JSON_MEDIA_TYPE))
            }

            // 无 body 的 POST/PUT：构造仅含公共参数的 JSON body（与官方 JSON POST 形态一致）。
            body == null -> {
                val merged = buildJsonObject {
                    params.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                }
                builder.method(request.method, merged.toString().toRequestBody(JSON_MEDIA_TYPE))
            }

            // 其他 body（multipart 文件上传等）保持原样。
            else -> Unit
        }
    }

    private fun RequestBody.isJson(): Boolean {
        val mediaType = contentType() ?: return false
        return mediaType.type == "application" && mediaType.subtype.contains("json")
    }

    private companion object {
        const val HOST_SUFFIX = "china-qzxy.cn"
        const val HEADER_PROJECT = "Config-Project"
        const val HEADER_KEYS = "Config-Keys"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
