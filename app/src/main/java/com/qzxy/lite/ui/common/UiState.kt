package com.qzxy.lite.ui.common

import com.qzxy.lite.core.ApiException
import com.qzxy.lite.core.isDeviceOffline
import java.io.IOException

/** 界面状态统一封装：所有屏幕状态用 StateFlow 持有。 */
sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

/** 异常 → 用户可读信息：-1 请求错误 / -2 数据错误 / HTTP 错误 / 网络错误分开。 */
fun Throwable.toUserMessage(): String = when (this) {
    is ApiException -> when {
        // 306 = 设备不在线，服务端判定（设备与服务器连接断开），不是用户手机网络问题。
        // 直接给可操作提示，避免把"业务错误（306）：设备不在线"原样甩给用户。
        isDeviceOffline(code, message) ->
            "设备不在线（306）：设备与服务器连接已断开，通常几分钟后自动恢复。" +
                "请确认设备通电、卡有信号，稍后重试；仍不行请联系宿管检查设备。"
        code == ApiException.DEVICE_BUSY_CODE ->
            "设备正在使用中（307）：该设备已有进行中的订单。"
        code == ApiException.REQUEST_ERROR_CODE -> "请求错误（-1）：$message"
        code == ApiException.DATA_ERROR_CODE -> "数据错误（-2）：$message"
        code == ApiException.HTTP_ERROR_CODE -> "服务端 HTTP 错误：$message"
        code == ApiException.NETWORK_ERROR_CODE -> "网络连接失败：$message"
        else -> "业务错误（$code）：$message"
    }
    is NotImplementedError -> message ?: "功能未实现"
    is IOException -> "网络异常：${message ?: "请检查网络"}"
    else -> message ?: "未知错误"
}
