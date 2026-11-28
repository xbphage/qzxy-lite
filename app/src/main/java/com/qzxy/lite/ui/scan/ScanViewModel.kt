package com.qzxy.lite.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qzxy.lite.ble.BleScanner
import com.qzxy.lite.ble.BleSighting
import com.qzxy.lite.core.dataOrThrow
import com.qzxy.lite.data.api.DeviceApi
import com.qzxy.lite.ui.common.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class ScanUiState(
    val devices: List<BleSighting> = emptyList(),
    /** 归一化 MAC → 后端查到的展示名（房间号等），查不到则无条目。 */
    val nameByMac: Map<String, String> = emptyMap(),
    /**
     * 归一化 MAC → 后端返回的设备记录。
     * 下单用的是服务端下发的 snCode/deviceSncode，**不是**本地归一化 MAC：
     * 归一化只用于蓝牙广播去重（C0→00），拿它当 SN 下单会被判「设备不在线」(306)。
     */
    val deviceByMac: Map<String, com.qzxy.lite.data.model.BathDevice> = emptyMap(),
    val scanning: Boolean = false,
    val error: String? = null,
)

/** 蓝牙搜索附近设备：扫描规则复刻原版（KLCXKJ 前缀 + C0→00 MAC 归一化 + 去重取最强 RSSI）。 */
class ScanViewModel(
    private val scanner: BleScanner,
    private val deviceApi: DeviceApi,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private val queried = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(scanning = true, error = null) }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan().collect { sighting ->
                    _state.update { st ->
                        val list = st.devices.toMutableList()
                        val idx = list.indexOfFirst { it.normalizedMac == sighting.normalizedMac }
                        if (idx >= 0) {
                            // 同一设备：保留更强 RSSI 的那次广播
                            if (sighting.rssi > list[idx].rssi) list[idx] = sighting
                        } else {
                            list.add(sighting)
                        }
                        list.sortByDescending { it.rssi }
                        st.copy(devices = list)
                    }
                    lookupName(sighting.normalizedMac)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(scanning = false, error = t.toUserMessage()) }
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false) }
    }

    /** 拿归一化 MAC 到后端换真实展示名 + 设备标识（DevName/snCode/deviceSncode）。 */
    private fun lookupName(mac: String) {
        if (!queried.add(mac)) return
        viewModelScope.launch {
            runCatching { deviceApi.deviceByMac(macAddress = mac).dataOrThrow() }
                .onSuccess { device ->
                    // 完整留存：下单要用服务端 snCode/deviceSncode，只有名字是不够的。
                    _state.update { it.copy(deviceByMac = it.deviceByMac + (mac to device)) }
                    val name = device.devName ?: device.deviceName ?: return@onSuccess
                    _state.update { it.copy(nameByMac = it.nameByMac + (mac to name)) }
                }
                .onFailure {
                    // 失败时允许后续再次扫描到该设备时重试识别，避免永久置灰
                    queried.remove(mac)
                }
        }
    }
}
