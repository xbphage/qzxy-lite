package com.qzxy.lite.data.session

import kotlinx.serialization.Serializable

/**
 * 会话上下文：登录后各请求公共参数的取值来源。
 * loginCode 是会话凭据（wfwapi 体系；prepay-user 是另一套，勿混用）。
 */
@Serializable
data class Session(
    val loginCode: String = "",
    val userId: String = "",
    val accountId: String = "",
    val projectId: String = "",
    val telephone: String = "",
) {
    val isLoggedIn: Boolean get() = loginCode.isNotBlank()

    /** 公共参数表（notes/API.md §2 t0() Observed）。 */
    fun asCommonParams(): Map<String, String> = mapOf(
        "loginCode" to loginCode,
        "userId" to userId,
        "accountId" to accountId,
        "projectId" to projectId,
        "telephone" to telephone,
        "telPhone" to telephone,
        "phoneSystem" to "android",
        "version" to com.qzxy.lite.core.ApiConstants.APP_VERSION,
    )
}
