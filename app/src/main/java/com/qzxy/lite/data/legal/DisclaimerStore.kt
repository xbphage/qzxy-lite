package com.qzxy.lite.data.legal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 免责声明与使用须知确认状态存储。 */
class DisclaimerStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _accepted = MutableStateFlow(prefs.getBoolean(KEY_ACCEPTED, false))

    /** 当前用户是否已阅读并按要求输入确认免责声明。 */
    val accepted: StateFlow<Boolean> = _accepted.asStateFlow()

    fun markAccepted() {
        prefs.edit().putBoolean(KEY_ACCEPTED, true).apply()
        _accepted.value = true
    }

    companion object {
        private const val PREFS_NAME = "qzxy_lite_legal"
        private const val KEY_ACCEPTED = "disclaimer_accepted_v1"
        const val REQUIRED_CONFIRM_TEXT = "我同意以上内容并清楚本软件可能无法正常运行"
    }
}
