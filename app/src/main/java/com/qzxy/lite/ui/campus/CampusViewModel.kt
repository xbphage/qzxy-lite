package com.qzxy.lite.ui.campus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qzxy.lite.data.device.SavedDeviceStore
import com.qzxy.lite.data.model.BathDevice
import com.qzxy.lite.data.repository.AuthRepository
import com.qzxy.lite.data.repository.BathRepository
import com.qzxy.lite.data.repository.CampusRepository
import com.qzxy.lite.data.session.SessionStore
import com.qzxy.lite.ui.common.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CampusUiState(
    val devices: List<BathDevice> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/** 浴室/设备列表页：优先显示本地保存的设备（扫描识别后落盘），其次拉服务端列表。 */
class CampusViewModel(
    private val campus: CampusRepository,
    private val auth: AuthRepository,
    private val savedDeviceStore: SavedDeviceStore,
    private val bath: BathRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CampusUiState())
    val state: StateFlow<CampusUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var usageJob: Job? = null

    init {
        load(initial = true)
    }

    fun load(initial: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val curProjectId = sessionStore.current()?.projectId.orEmpty()
            val saved = savedDeviceStore.devices.value.filter {
                it.projectId.isBlank() || curProjectId.isBlank() || it.projectId == curProjectId
            }
            val savedDevices = saved.map { d ->
                BathDevice(
                    snCode = d.snCode,
                    deviceSncode = d.deviceSncode.takeIf { it.isNotBlank() },
                    devName = d.name,
                    macAddress = d.mac,
                    deviceType = d.type,
                )
            }
            if (savedDevices.isNotEmpty()) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = null,
                        devices = savedDevices,
                    )
                }
                // 本地设备没有 usingStatus，向服务端逐台对账一次（307=使用中）。
                usageJob?.cancel()
                usageJob = viewModelScope.launch { refreshUsage(savedDevices) }
                if (initial) return@launch
            }
            _state.update {
                if (initial && savedDevices.isEmpty()) it.copy(loading = true, error = null)
                else it.copy(refreshing = true, error = null)
            }
            runCatching { campus.bathList() }
                .onSuccess { devices ->
                    _state.update { it.copy(loading = false, refreshing = false, devices = devices) }
                }
                .onFailure { t ->
                    if (savedDevices.isEmpty()) {
                        _state.update { it.copy(loading = false, refreshing = false, error = t.toUserMessage()) }
                    } else {
                        _state.update { it.copy(loading = false, refreshing = false) }
                    }
                }
        }
    }

    /**
     * 逐台查"是否在用"（using 返回 307 = 有进行中订单）。
     * 只对本地保存的少量设备调用，避免请求放大；失败静默，保留原状态。
     */
    private suspend fun refreshUsage(devices: List<BathDevice>) {
        for (device in devices) {
            val sn = device.sn ?: continue
            val rsp = runCatching { bath.usingOrderRaw(sn) }.getOrNull() ?: continue
            val busy = rsp.errorCode == 307
            _state.update { st ->
                st.copy(
                    devices = st.devices.map {
                        if (it.sn == sn) it.copy(usingStatus = if (busy) "1" else "0") else it
                    },
                )
            }
        }
    }

    /** 首页可见期间的轻量对账（只查在用状态，不拉列表）。 */
    fun refreshUsageStatus() {
        val devices = _state.value.devices
        if (devices.isEmpty()) return
        usageJob?.cancel()
        usageJob = viewModelScope.launch { refreshUsage(devices) }
    }

    /** 移除本地已保存的水表设备。 */
    fun removeSavedDevice(snCode: String) {
        savedDeviceStore.remove(snCode)
        _state.update { st -> st.copy(devices = st.devices.filterNot { it.snCode == snCode || it.sn == snCode }) }
    }

    /** 更换校园（换 projectId 后重拉配置）。 */
    fun bindProject(projectId: String) {
        if (projectId.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, message = null) }
            runCatching { auth.bindProject(projectId) }
                .onSuccess {
                    _state.update { it.copy(loading = false, message = "已绑定校园 $projectId") }
                    load()
                }
                .onFailure { t ->
                    _state.update { it.copy(loading = false, error = t.toUserMessage()) }
                }
        }
    }
}
