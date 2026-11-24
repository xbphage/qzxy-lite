package com.qzxy.lite.data.repository

import com.qzxy.lite.core.ApiConstants
import com.qzxy.lite.core.ApiException
import com.qzxy.lite.core.DiagLog
import com.qzxy.lite.core.dataOrThrow
import com.qzxy.lite.core.successOrThrow
import com.qzxy.lite.data.api.AuthApi
import com.qzxy.lite.data.api.BindProjectRequest
import com.qzxy.lite.data.api.LoginRequest
import com.qzxy.lite.data.api.SendCodeRequest
import com.qzxy.lite.data.model.UserInfo
import com.qzxy.lite.data.network.NetworkModule
import com.qzxy.lite.data.session.Session
import com.qzxy.lite.data.session.SessionStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException

/**
 * 登录 / 会话仓库。两条实测登录路径：
 * - 验证码登录：v3-api registerAndLogin(POST 表单) → 签发 v3 会话；
 * - 密码登录：wfwapi user/login(POST 表单, password=MD5(明文), type=0) → 签发 wfwapi 会话。
 * 两个域名的会话相互独立（同 loginCode 跨域返回 errorCode=8，见 notes/samples/logcat_login.txt），
 * SessionStore 分开保存，拦截器按 host 取对应 loginCode。
 */
class AuthRepository(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
) {

    /** secret = MD5(手机号前3位 + 手机号后4位 + "klcx")（smali 核实 + 实测）。 */
    fun verificationSecret(telephone: String): String = md5Hex(
        telephone.take(3) + telephone.takeLast(4) + "klcx"
    )

    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 密码登录（绕开验证码频控）：POST v3-api/user/login，password=MD5(明文)。
     * 响应 data 即 user_info；签发的 loginCode 存为主会话（v3）。
     * [keepLoggedIn] 是否保持登录（对标原版记住登录态，加密存储密码 MD5 供静默重连）。
     */
    suspend fun passwordLogin(
        telephone: String,
        password: String,
        keepLoggedIn: Boolean = sessionStore.keepLoggedIn,
    ): UserInfo {
        val passMd5 = md5Hex(password)
        val result = authApi.passwordLogin(
            telephone = telephone,
            passwordMd5 = passMd5,
        ).dataOrThrow()
        val info = result.merged()
        val loginCode = info.loginCode
        if (loginCode.isNullOrBlank()) {
            throw ApiException(ApiException.DATA_ERROR_CODE, "登录响应未返回 loginCode（响应结构见 notes/samples）")
        }
        sessionStore.update(
            Session(
                loginCode = loginCode,
                userId = info.userId.orEmpty(),
                accountId = info.accountId.orEmpty(),
                projectId = info.projectId.orEmpty(),
                telephone = telephone,
            ),
            passwordMd5 = passMd5,
            keepLoggedIn = keepLoggedIn,
        )
        sessionStore.lastRenewalTime = System.currentTimeMillis()
        return info
    }

    /**
     * 发送短信验证码：GET → POST JSON → POST 表单，逐个尝试；
     * 命中业务错误（如"发送频繁"）立即抛出，不继续换形态。
     * 注意：成功响应 data=null，必须用 successOrThrow（dataOrThrow 会误报 -2）。
     */
    suspend fun sendCode(telephone: String) {
        val secret = verificationSecret(telephone)
        val attempts = mutableListOf<String>()
        try {
            authApi.sendVerificationCode(secret = secret, telephone = telephone).successOrThrow()
            return
        } catch (e: Exception) {
            attempts += "GET: ${e.message}"
            if (!e.isShapeMismatch()) throw e
        }
        try {
            authApi.sendVerificationCodePost(SendCodeRequest(secret = secret, telephone = telephone))
                .successOrThrow()
            return
        } catch (e: Exception) {
            attempts += "POST-JSON: ${e.message}"
            if (!e.isShapeMismatch()) throw e
        }
        try {
            authApi.sendVerificationCodeForm(secret = secret, telephone = telephone).successOrThrow()
        } catch (e: Exception) {
            attempts += "POST-FORM: ${e.message}"
            throw IllegalStateException("验证码发送失败（三种形态均被拒）：${attempts.joinToString("；")}", e)
        }
    }

    /** 验证码登录：**POST 表单**（实测成功形态；JSON 被签名校验拒绝）→ 响应 data 自带全套 id，直接落会话。 */
    suspend fun login(
        telephone: String,
        smsCode: String,
        keepLoggedIn: Boolean = sessionStore.keepLoggedIn,
    ): UserInfo {
        val attempts = mutableListOf<String>()
        val result = try {
            authApi.registerAndLoginForm(
                telephone = telephone,
                smsCode = smsCode,
            ).dataOrThrow()
        } catch (e: Exception) {
            attempts += "POST-FORM: ${e.message}"
            if (!e.isShapeMismatch()) throw e
            try {
                authApi.registerAndLogin(LoginRequest(telephone = telephone, smsCode = smsCode)).dataOrThrow()
            } catch (e2: Exception) {
                attempts += "POST-JSON: ${e2.message}"
                throw IllegalStateException("登录失败（两种 POST 形态均被拒）：${attempts.joinToString("；")}", e2)
            }
        }
        val info = result.merged()
        val loginCode = info.loginCode
        if (loginCode.isNullOrBlank()) {
            throw ApiException(ApiException.DATA_ERROR_CODE, "登录响应未返回 loginCode（响应结构见 notes/samples）")
        }
        sessionStore.update(
            Session(
                loginCode = loginCode,
                userId = info.userId.orEmpty(),
                accountId = info.accountId.orEmpty(),
                projectId = info.projectId.orEmpty(),
                telephone = telephone,
            ),
            keepLoggedIn = keepLoggedIn,
        )
        sessionStore.lastRenewalTime = System.currentTimeMillis()
        return info
    }

    /**
     * 会话缓存主动续期（对标原版 v0.j().K(context) 调用的 user/login/cache/renewal）。
     * 调用成功后服务端 Redis 会重新重置 token 的过期时间。
     */
    suspend fun renewLoginCache(): Boolean {
        val cur = sessionStore.current() ?: return false
        if (cur.accountId.isBlank() || cur.projectId.isBlank()) return false
        return try {
            authApi.renewLoginCache(cur.accountId, cur.projectId).successOrThrow()
            sessionStore.lastRenewalTime = System.currentTimeMillis()
            DiagLog.d("Auth", "会话主动续期成功 (user/login/cache/renewal)")
            true
        } catch (e: Exception) {
            DiagLog.d("Auth", "会话主动续期失败: ${e.message}")
            false
        }
    }

    /**
     * 启动/前台周期性检查：若距离上次成功续期已超过 24 小时，且开启了保持登录，则在后台无感续期。
     */
    suspend fun checkAndRenewSession() {
        val cur = sessionStore.current() ?: return
        if (!sessionStore.keepLoggedIn || !cur.isLoggedIn) return
        val last = sessionStore.lastRenewalTime
        val now = System.currentTimeMillis()
        // 超过 24 小时（或从没续期过）则向服务端续期一次
        if (last <= 0L || (now - last) >= 24 * 3600 * 1000L) {
            renewLoginCache()
        }
    }

    /**
     * 同步静默重新登录（供网络拦截器捕获到 errorCode=8 时调用）。
     * 尝试用本地加密保存的凭据换取新 loginCode 并更新 sessionStore。
     * @return true 表示换取成功，外部拦截器可无感重试当前网络请求；false 表示换取失败。
     */
    fun performSilentReLoginSync(): Boolean {
        val cur = sessionStore.current()
        val phone = cur?.telephone?.takeIf { it.isNotBlank() }
            ?: sessionStore.lastTelephone.takeIf { it.isNotBlank() }
            ?: return false
        val passMd5 = sessionStore.savedPasswordMd5 ?: return false
        if (!sessionStore.keepLoggedIn) return false

        return try {
            // 采用纯净独立的 OkHttpClient 发送密码登录请求，避免循环进入拦截器
            val form = FormBody.Builder()
                .add("telephone", phone)
                .add("password", passMd5)
                .add("type", "0")
                .add("phoneSystem", "android")
                .add("version", ApiConstants.APP_VERSION)
                .build()

            val req = Request.Builder()
                .url(ApiConstants.V3_API + "user/login")
                .post(form)
                .header("Config-Keys", ApiConstants.CONFIG_KEYS)
                .build()

            val directClient = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val resp = directClient.newCall(req).execute()
            if (!resp.isSuccessful) return false
            val body = resp.body?.string() ?: return false
            val root = NetworkModule.json.parseToJsonElement(body).jsonObject
            val errorCode = root["errorCode"]?.toString()?.toIntOrNull() ?: -1
            if (errorCode != 0 && errorCode != 2) {
                // 密码错误等情况，清空无效的密码缓存
                if (errorCode == 12) sessionStore.clearCredentials()
                return false
            }

            val data = root["data"]?.jsonObject ?: return false
            val loginCode = data["loginCode"]?.toString()?.trim('"') ?: return false
            val userId = data["userId"]?.toString()?.trim('"') ?: cur?.userId.orEmpty()
            val accountId = data["accountId"]?.toString()?.trim('"') ?: cur?.accountId.orEmpty()
            val projectId = data["projectId"]?.toString()?.trim('"') ?: cur?.projectId.orEmpty()

            sessionStore.update(
                Session(
                    loginCode = loginCode,
                    userId = userId,
                    accountId = accountId,
                    projectId = projectId,
                    telephone = phone,
                ),
                passwordMd5 = passMd5,
                keepLoggedIn = true,
            )
            sessionStore.lastRenewalTime = System.currentTimeMillis()
            sessionStore.clearKickReason()
            DiagLog.d("Auth", "静默重新登录成功，新会话 loginCode 已无感续签")
            true
        } catch (e: Exception) {
            DiagLog.d("Auth", "静默重连异常: ${e.message}")
            false
        }
    }

    /** 拉取用户信息并把非空字段回填会话（登录链第 4 步校验）。 */
    suspend fun refreshUserInfo(): UserInfo {
        val info = authApi.userInfo().dataOrThrow()
        sessionStore.patch { cur ->
            cur.copy(
                userId = info.userId?.takeIf { it.isNotBlank() } ?: cur.userId,
                accountId = info.accountId?.takeIf { it.isNotBlank() } ?: cur.accountId,
                projectId = info.projectId?.takeIf { it.isNotBlank() } ?: cur.projectId,
                telephone = info.telephone?.takeIf { it.isNotBlank() }
                    ?: info.telPhone?.takeIf { it.isNotBlank() } ?: cur.telephone,
            )
        }
        return info
    }

    /** 绑定/更换校园：换 projectId 后由 UI 重拉区域/设备配置。 */
    suspend fun bindProject(projectId: String) {
        authApi.bindProject(BindProjectRequest(projectId)).successOrThrow()
        sessionStore.patch { it.copy(projectId = projectId) }
    }

    /** 退出：尽力通知服务端，本地会话必清。 */
    suspend fun logout() {
        runCatching { authApi.logout().successOrThrow() }
        sessionStore.clear()
    }

    /**
     * 形态不匹配 ⇒ 换下一形态重试：HTTP 404/405/415、errorCode=5（请求方式错误）、
     * errorCode=1（参数校验不通过，可能是参数名/载体不对）。其余（业务错误）直接上抛。
     */
    private fun Exception.isShapeMismatch(): Boolean = when (this) {
        is HttpException -> code() == 404 || code() == 405 || code() == 415
        is ApiException -> code == ApiException.METHOD_ERROR_CODE || code == 1
        else -> false
    }
}
