package com.qzxy.lite.tmall

import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 天猫校园手机号短信验证码 + 人机滑块登录（迁移自 lmc2007/TMallKey AliLogin）。
 */
class TmallAliLogin(
    private val client: TmallMtopClient,
    private val screenSize: String = "1080x2400",
) {

    sealed interface SendResult {
        data object Sent : SendResult
        data class NeedCaptcha(val url: String) : SendResult
        data class Failed(val code: String, val message: String) : SendResult
    }

    fun sendSms(phone: String, umidToken: String = ""): SendResult = try {
        client.call(API_SEND_SMS, envelope(phone, umidToken))
        SendResult.Sent
    } catch (e: TmallMtopError) {
        val url = punishUrl(e)
        when {
            url != null -> SendResult.NeedCaptcha(url)
            e.code == "RGV587_ERROR" -> SendResult.Failed(
                e.code,
                "风控拦截且未返回滑块入口，请等待 10-15 分钟后重试或改用淘宝网页登录。",
            )
            else -> SendResult.Failed(e.code, e.human())
        }
    }

    fun login(phone: String, code: String, umidToken: String = ""): Result<Map<String, String>> = try {
        client.call(API_SMS_LOGIN, envelope(phone, umidToken, mapOf("smsCode" to code)))
        Result.success(client.export())
    } catch (e: TmallMtopError) {
        Result.failure(IllegalStateException("${e.code}：${e.human()}", e))
    }

    fun confirmCampusUser(): String? = try {
        client.call(API_CAMPUS_LOGIN, emptyMap())
        null
    } catch (e: TmallMtopError) {
        "${e.code}：${e.human()}"
    }

    private fun envelope(
        phone: String,
        umidToken: String,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val ext = linkedMapOf<String, Any?>(
            "apiVersion" to "2.0",
            "deviceName" to Build.MODEL,
            "sdkTraceId" to now.toString(),
        )
        val loginInfo = linkedMapOf<String, Any?>(
            "loginId" to phone,
            "loginType" to LOGIN_TYPE,
            "site" to 0,
            "locale" to "zh_CN",
            "countryCode" to "86",
            "phoneCode" to "86",
            "codeLength" to "6",
            "t" to now,
        )
        loginInfo.putAll(extra)
        val risk = linkedMapOf<String, Any?>(
            "umidToken" to umidToken,
            "osName" to "android",
            "osVersion" to Build.VERSION.RELEASE,
            "deviceModel" to Build.MODEL,
            "deviceBrand" to Build.MANUFACTURER,
            "deviceName" to Build.MODEL,
            "screenSize" to screenSize,
            "t" to now.toString(),
        )
        return linkedMapOf(
            "ext" to TmallJson.stringify(ext),
            "loginInfo" to TmallJson.stringify(loginInfo),
            "riskControlInfo" to TmallJson.stringify(risk),
        )
    }

    private fun punishUrl(e: TmallMtopError): String? = runCatching {
        val body = e.payload
        if (body.isBlank()) return@runCatching null
        val root = json.parseToJsonElement(body).jsonObject
        val dataEl = root["data"]
        val direct = when (dataEl) {
            is JsonObject -> {
                (dataEl["url"] as? JsonPrimitive)?.content.orEmpty()
                    .ifBlank { (dataEl["punishUrl"] as? JsonPrimitive)?.content.orEmpty() }
            }
            is JsonPrimitive -> runCatching {
                val nested = json.parseToJsonElement(dataEl.content).jsonObject
                (nested["url"] as? JsonPrimitive)?.content.orEmpty()
                    .ifBlank { (nested["punishUrl"] as? JsonPrimitive)?.content.orEmpty() }
            }.getOrDefault("")
            else -> ""
        }
        val candidate = direct.ifBlank { RE_PUNISH.find(body)?.value.orEmpty() }
        candidate.takeIf { it.startsWith("http") }
    }.getOrNull()

    companion object {
        const val LOGIN_TYPE = "taobao"
        const val API_SEND_SMS = "mtop.taobao.mloginService.smsSend"
        const val API_SMS_LOGIN = "mtop.taobao.mloginService.smsLogin"
        const val API_CAMPUS_LOGIN = "mtop.tmall.campus.member.user.login"

        private val json = Json { ignoreUnknownKeys = true }
        private val RE_PUNISH = Regex("""https://[^"]*punish[^"]*""")
    }
}
