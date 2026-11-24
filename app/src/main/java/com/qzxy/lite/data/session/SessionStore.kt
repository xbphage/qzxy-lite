package com.qzxy.lite.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话存储：
 * - [session] 主会话（登录态判断 + 公共参数来源）；
 * - [wfwapiLoginCode] wfwapi 域名专用 loginCode（密码登录 user/login 签发）。
 * 实测：v3-api 签发的 loginCode 在 wfwapi 上无效（errorCode=8），两个域名会话独立，
 * 因此拦截器按 host 取对应 loginCode（见 CommonParamsInterceptor）。
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "qzxy_lite_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore 损坏等极端场景：清掉旧文件重建，避免死循环崩溃。
        context.deleteSharedPreferences("qzxy_lite_session")
        throw IllegalStateException("会话加密存储初始化失败，已清除旧存储：${e.message}", e)
    }

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _wfwapiLoginCode = MutableStateFlow(prefs.getString(KEY_WFWAPI_LOGIN_CODE, null))
    val wfwapiLoginCode: StateFlow<String?> = _wfwapiLoginCode.asStateFlow()

    init {
        // 进程重启后恢复 wfwapi 会话码
        _wfwapiLoginCode.value = prefs.getString(KEY_WFWAPI_LOGIN_CODE, null)
    }

    fun current(): Session? = _session.value

    fun update(session: Session) {
        prefs.edit()
            .putString(KEY_LOGIN_CODE, session.loginCode)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_ACCOUNT_ID, session.accountId)
            .putString(KEY_PROJECT_ID, session.projectId)
            .putString(KEY_TELEPHONE, session.telephone)
            .apply()
        _session.value = session
    }

    /** 局部更新（如绑定校园后只改 projectId）。 */
    fun patch(transform: (Session) -> Session) {
        val cur = _session.value ?: Session()
        update(transform(cur))
    }

    /** 记录 wfwapi 域名会话码（密码登录 user/login 签发）。 */
    fun setWfwapiLoginCode(code: String) {
        prefs.edit().putString(KEY_WFWAPI_LOGIN_CODE, code).apply()
        _wfwapiLoginCode.value = code
    }

    /** 清会话；[reason] 非空时记录失效原因（如被原版 App 顶掉），供登录页提示。 */
    fun clear(reason: String? = null) {
        prefs.edit().clear().apply()
        kickPrefs.edit().putString(KEY_KICK_REASON, reason).apply()
        _session.value = null
        _wfwapiLoginCode.value = null
        _kickReason.value = reason
    }

    /** 清除失效原因提示（重新登录成功后调用）。 */
    fun clearKickReason() {
        kickPrefs.edit().remove(KEY_KICK_REASON).apply()
        _kickReason.value = null
    }

    private fun readSession(): Session? {
        val loginCode = prefs.getString(KEY_LOGIN_CODE, null) ?: return null
        if (loginCode.isBlank()) return null
        return Session(
            loginCode = loginCode,
            userId = prefs.getString(KEY_USER_ID, "").orEmpty(),
            accountId = prefs.getString(KEY_ACCOUNT_ID, "").orEmpty(),
            projectId = prefs.getString(KEY_PROJECT_ID, "").orEmpty(),
            telephone = prefs.getString(KEY_TELEPHONE, "").orEmpty(),
        )
    }

    // 会话被顶掉/失效的原因（明文存储即可，非敏感），登录页据此提示。
    private val kickPrefs = context.getSharedPreferences("qzxy_lite_kick", Context.MODE_PRIVATE)
    private val _kickReason = MutableStateFlow(kickPrefs.getString(KEY_KICK_REASON, null))
    val kickReason: StateFlow<String?> = _kickReason.asStateFlow()

    private companion object {
        const val KEY_LOGIN_CODE = "loginCode"
        const val KEY_USER_ID = "userId"
        const val KEY_ACCOUNT_ID = "accountId"
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_TELEPHONE = "telephone"
        const val KEY_WFWAPI_LOGIN_CODE = "wfwapiLoginCode"
        const val KEY_KICK_REASON = "kickReason"
    }
}
