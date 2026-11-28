package com.qzxy.lite.ui.water

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qzxy.lite.core.DiagLog
import com.qzxy.lite.tmall.SavedWaterDevice
import com.qzxy.lite.tmall.TmallMtopError
import com.qzxy.lite.tmall.TmallQrCodec
import com.qzxy.lite.tmall.TmallWaterRepository
import com.qzxy.lite.tmall.WaterDeviceSortMode
import com.qzxy.lite.tmall.WaterQuickUse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WaterDeviceError(
    val resNo: String,
    val message: String,
)

data class ActiveWaterOrder(
    val resNo: String,
    val isvOrderId: String,
)

data class WaterUiState(
    val busy: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val loggedIn: Boolean = false,
    val campusReady: Boolean = true,
    val maskedAccount: String = "",
    val devices: List<SavedWaterDevice> = emptyList(),
    val sortMode: WaterDeviceSortMode = WaterDeviceSortMode.RECENT,
    val processingResNo: String? = null,
    val deviceError: WaterDeviceError? = null,
    val lastUnlockedResNo: String? = null,
    val activeOrder: ActiveWaterOrder? = null,
)

/**
 * 天猫校园饮水 ViewModel：管理饮水会话状态、扫码/手输设备录入、排序删除与一键解锁/停止。
 */
class WaterViewModel(
    private val repo: TmallWaterRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WaterUiState())
    val state: StateFlow<WaterUiState> = _state.asStateFlow()

    private val quickUseFlow = WaterQuickUse()
    private var probedForCookieCount = -1

    init {
        viewModelScope.launch {
            combine(
                repo.sessionStore.cookies,
                repo.deviceStore.devices,
                repo.deviceStore.sortMode,
            ) { cookies, devices, sortMode ->
                Triple(cookies, devices, sortMode)
            }.collect { (cookies, devices, sortMode) ->
                val isLogged = cookies.isNotEmpty()
                _state.update {
                    it.copy(
                        loggedIn = isLogged,
                        maskedAccount = if (isLogged) repo.sessionStore.maskedAccount else "",
                        devices = devices,
                        sortMode = sortMode,
                        campusReady = if (!isLogged) true else it.campusReady,
                    )
                }
                if (isLogged && probedForCookieCount != cookies.size) {
                    probedForCookieCount = cookies.size
                    probeCampus()
                } else if (!isLogged) {
                    probedForCookieCount = -1
                }
            }
        }
    }

    fun probeCampus() {
        if (!repo.sessionStore.isLoggedIn) return
        viewModelScope.launch {
            val probe = withContext(Dispatchers.IO) {
                runCatching { repo.probeCampusUser() }.getOrNull()
            } ?: return@launch
            DiagLog.d("tmall_water", "probe ok=${probe.ok} code=${probe.code}")
            _state.update { st ->
                if (!st.loggedIn) st
                else st.copy(
                    campusReady = probe.ok,
                    status = if (probe.ok) "天猫校园登录态就绪" else st.status,
                    error = if (!probe.ok) probe.message else st.error,
                )
            }
            if (probe.code == "FAIL_SYS_SESSION_EXPIRED") {
                repo.logout()
            }
        }
    }

    fun addDevice(rawInput: String) {
        if (_state.value.busy || !_state.value.loggedIn) return
        val scanned = TmallQrCodec.parse(rawInput)
        if (scanned == null || !scanned.valid) {
            _state.update { it.copy(error = "无法识别饮水机二维码或编号（支持扫码 URL、tmxy 编号或纯数字资源号）") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "正在识别饮水机…", error = null, deviceError = null) }
            try {
                val (device, added) = withContext(Dispatchers.IO) {
                    repo.fetchAndSaveDevice(scanned.code)
                }
                _state.update {
                    if (added) {
                        it.copy(status = "已添加 ${device.name}", error = null)
                    } else {
                        it.copy(status = "", error = "已保存上限 30 台饮水机，请先在「管理设备」中移除旧设备")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                reportFailure(t)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun quickUse(resNo: String) {
        if (_state.value.busy || !_state.value.loggedIn) return
        if (_state.value.devices.none { it.resNo == resNo }) return
        viewModelScope.launch {
            var sentUnlock = false
            _state.update {
                it.copy(
                    busy = true,
                    status = "检查设备…",
                    error = null,
                    processingResNo = resNo,
                    deviceError = null,
                    lastUnlockedResNo = null,
                )
            }
            try {
                val started = quickUseFlow.start(
                    resNo = resNo,
                    query = { code -> withContext(Dispatchers.IO) { repo.checkDeviceInfo(code) } },
                    unlock = { code ->
                        _state.update { it.copy(status = "解锁中…") }
                        sentUnlock = true
                        withContext(Dispatchers.IO) { repo.startWater(code) }
                    },
                ) ?: return@launch

                _state.update {
                    it.copy(
                        lastUnlockedResNo = resNo,
                        activeOrder = ActiveWaterOrder(resNo, started.isvOrderId),
                        status = "",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = if (sentUnlock && WaterQuickUse.unlockUnconfirmed(t)) {
                    "解锁结果未确认，请先确认饮水机是否出水中再试"
                } else {
                    explain(t)
                }
                reportFailure(t, WaterDeviceError(resNo, msg))
            } finally {
                _state.update { it.copy(busy = false, processingResNo = null) }
            }
        }
    }

    fun stopWater(resNo: String, isvOrderId: String) {
        if (_state.value.busy || !_state.value.loggedIn) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, processingResNo = resNo, status = "正在结束…", deviceError = null) }
            try {
                withContext(Dispatchers.IO) { repo.stopWater(resNo, isvOrderId) }
                _state.update {
                    it.copy(
                        activeOrder = null,
                        lastUnlockedResNo = null,
                        status = "已发送结束接水指令",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                reportFailure(t, WaterDeviceError(resNo, explain(t)))
            } finally {
                _state.update { it.copy(busy = false, processingResNo = null) }
            }
        }
    }

    fun setSortMode(mode: WaterDeviceSortMode) {
        if (_state.value.busy || _state.value.sortMode == mode) return
        repo.deviceStore.setSortMode(mode)
    }

    fun saveCustomOrder(resNos: List<String>) {
        if (_state.value.busy) return
        repo.deviceStore.saveCustomOrder(resNos)
    }

    fun removeDevice(resNo: String) {
        if (_state.value.busy) return
        repo.deviceStore.remove(resNo)
        _state.update {
            it.copy(
                deviceError = it.deviceError?.takeUnless { err -> err.resNo == resNo },
                lastUnlockedResNo = it.lastUnlockedResNo?.takeUnless { id -> id == resNo },
                activeOrder = it.activeOrder?.takeUnless { ord -> ord.resNo == resNo },
            )
        }
    }

    fun logout() {
        if (_state.value.busy) return
        repo.logout()
        _state.update { it.copy(status = "", error = null, activeOrder = null, lastUnlockedResNo = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null, deviceError = null) }
    }

    private fun reportFailure(t: Throwable, deviceError: WaterDeviceError? = null) {
        val expired = t is TmallMtopError && t.code == "FAIL_SYS_SESSION_EXPIRED"
        if (expired) repo.logout()
        _state.update {
            it.copy(
                status = "",
                deviceError = deviceError,
                error = when {
                    expired -> "天猫校园登录已过期，请重新登录"
                    deviceError == null -> explain(t)
                    else -> null
                },
            )
        }
    }

    private fun explain(t: Throwable): String = when (t) {
        is TmallMtopError -> "${t.human()}（${t.code}）"
        else -> t.message ?: t.javaClass.simpleName
    }
}
