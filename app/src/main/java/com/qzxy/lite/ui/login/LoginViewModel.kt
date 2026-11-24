package com.qzxy.lite.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qzxy.lite.data.repository.AuthRepository
import com.qzxy.lite.data.session.SessionStore
import com.qzxy.lite.ui.common.UiState
import com.qzxy.lite.ui.common.toUserMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val telephone: String = "",
    val code: String = "",
    val password: String = "",
    val sendCodeState: UiState<Unit> = UiState.Idle,
    val loginState: UiState<Unit> = UiState.Idle,
    val countdown: Int = 0,
    /** UI 改版后密码登录是主表单，验证码收为切换路径；纯界面态，不参与数据层。 */
    val smsMode: Boolean = true,
)

/**
 * 登录（与原版一致，两种实测方式）：
 * - 验证码登录：手机号 + 短信验证码（v3-api registerAndLogin）；
 * - 密码登录：手机号 + 密码（wfwapi user/login，password=MD5(明文)，绕开验证码频控）。
 * 登录响应自动返回 projectId/loginCode/userId/accountId 并写入会话。
 */
class LoginViewModel(
    private val auth: AuthRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onTelephoneChange(v: String) =
        _state.update { it.copy(telephone = v.filter { c -> c.isDigit() }.take(11)) }

    fun onCodeChange(v: String) =
        _state.update { it.copy(code = v.filter { c -> c.isDigit() }.take(6)) }

    fun onPasswordChange(v: String) = _state.update { it.copy(password = v.take(32)) }

    /** 密码表单 ⇄ 验证码表单切换（清掉上一次的登录错误提示）。 */
    fun toggleMode() = _state.update { it.copy(smsMode = !it.smsMode, loginState = UiState.Idle) }

    fun sendCode() {
        val s = _state.value
        if (s.telephone.length != 11 || s.countdown > 0) return
        viewModelScope.launch {
            _state.update { it.copy(sendCodeState = UiState.Loading) }
            runCatching { auth.sendCode(s.telephone) }
                .onSuccess {
                    _state.update { it.copy(sendCodeState = UiState.Success(Unit)) }
                    startCountdown()
                }
                .onFailure { t ->
                    _state.update { it.copy(sendCodeState = UiState.Error(t.toUserMessage())) }
                }
        }
    }

    fun login(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.telephone.length != 11 || s.code.length < 4) return
        doLogin({ auth.login(s.telephone, s.code) }, onSuccess)
    }

    /** 密码登录（wfwapi user/login，type=0，password=MD5(明文)）。 */
    fun loginWithPassword(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.telephone.length != 11 || s.password.isBlank()) return
        doLogin({ auth.passwordLogin(s.telephone, s.password) }, onSuccess)
    }

    private fun doLogin(block: suspend () -> Unit, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loginState = UiState.Loading) }
            runCatching { block() }
                .onSuccess {
                    sessionStore.clearKickReason()
                    _state.update { it.copy(loginState = UiState.Success(Unit)) }
                    onSuccess()
                }
                .onFailure { t ->
                    _state.update { it.copy(loginState = UiState.Error(t.toUserMessage())) }
                }
        }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            for (i in 60 downTo 1) {
                _state.update { it.copy(countdown = i) }
                delay(1_000)
            }
        }
    }
}
