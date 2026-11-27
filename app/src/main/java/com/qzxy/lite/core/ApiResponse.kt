package com.qzxy.lite.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 服务端统一响应封装（notes/API.md §2，Observed）：
 * {"errorCode":0,"message":"...","data":{...}}
 * errorCode: 0=成功；-1=请求错误；-2=数据错误；message 可能叫 errorMessage。
 */
@Serializable
data class ApiResponse<T>(
    val errorCode: Int = 0,
    val message: String? = null,
    @SerialName("errorMessage") val errorMessage: String? = null,
    val data: T? = null,
) {
    val effectiveMessage: String
        get() = message ?: errorMessage ?: "未知错误（errorCode=$errorCode）"
}

/** 业务/网络异常：把 errorCode -1/-2 与 HTTP 错误区分开。 */
class ApiException(
    val code: Int,
    message: String,
) : Exception(message) {
    companion object {
        const val REQUEST_ERROR_CODE = -1
        const val DATA_ERROR_CODE = -2
        const val METHOD_ERROR_CODE = 5
        const val SESSION_EXPIRED_CODE = 8

        /**
         * 设备不在线（2026-11-21 实测截图确认：`业务错误（306）：设备不在线`）。
         * 由服务端判定——设备与服务器之间的连接（4G/心跳）已断开，
         * 客户端无法也不应该自行推断在线状态。
         */
        const val DEVICE_OFFLINE_CODE = 306

        /** 设备正在使用中（开阀防重复）。 */
        const val DEVICE_BUSY_CODE = 307

        /** 账户可用余额低于设备预扣金额。 */
        const val INSUFFICIENT_BALANCE_CODE = 204

        const val HTTP_ERROR_CODE = -100
        const val NETWORK_ERROR_CODE = -101
    }
}

/** 成功返回 data；失败抛 ApiException（-1/-2 语义见上）。 */
fun <T> ApiResponse<T>.dataOrThrow(): T {
    if (errorCode != 0) throw ApiException(errorCode, effectiveMessage)
    return data ?: throw ApiException(ApiException.DATA_ERROR_CODE, "data 为空：${effectiveMessage}")
}

/** 仅校验 errorCode==0，不要求 data 非空（发验证码/退出等 data=null 的成功响应）。 */
fun <T> ApiResponse<T>.successOrThrow() {
    if (errorCode != 0) throw ApiException(errorCode, effectiveMessage)
}

/**
 * 是否为"设备不在线"（errorCode=306，或文案含离线字样）。
 * 判定权在服务端：这是设备与服务器之间的连接状态，客户端只做识别与提示。
 */
fun isDeviceOffline(code: Int?, message: String?): Boolean {
    if (code == ApiException.DEVICE_OFFLINE_CODE) return true
    val m = message?.lowercase() ?: return false
    return m.contains("不在线") || m.contains("离线") || m.contains("未连接") ||
        m.contains("offline") || m.contains("not online")
}

fun isDeviceOfflineMessage(message: String?): Boolean = isDeviceOffline(null, message)
