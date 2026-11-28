package com.qzxy.lite.data.device

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 已保存（绑定过）的设备：扫描识别后落盘，首页直接显示，无需重复扫描。 */
@Serializable
data class SavedDevice(
    val snCode: String,
    /** 设备第二标识（原版开阀请求同发）；旧数据反序列化时为默认空串。 */
    val deviceSncode: String = "",
    val name: String,
    val type: String = "NET",
    val mac: String = "",
    val rssi: Int = 0,
    /** 归属校园 ID；旧数据反序列化为空串表示跨校区可见，新保存设备按校园隔离展示。 */
    val projectId: String = "",
)

/**
 * 本地设备库：BLE 扫描识别成功后保存，首页直接展示（与原版"添加设备后长期使用"一致）。
 * 存 SharedPreferences JSON（设备信息非敏感）。
 */
class SavedDeviceStore(context: Context) {

    private val prefs = context.getSharedPreferences("qzxy_lite_devices", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<SavedDevice>> = _devices.asStateFlow()

    private fun load(): List<SavedDevice> = try {
        json.decodeFromString(prefs.getString(KEY, "[]") ?: "[]")
    } catch (e: Exception) {
        emptyList()
    }

    /** 添加/更新（按 snCode 去重，新的在前面，线程安全写入）。 */
    @Synchronized
    fun add(device: SavedDevice) {
        val list = listOf(device) + _devices.value.filterNot { it.snCode == device.snCode }
        save(list)
    }

    /** 按 snCode 移除已保存的设备。 */
    @Synchronized
    fun remove(snCode: String) {
        val list = _devices.value.filterNot { it.snCode == snCode }
        save(list)
    }

    private fun save(list: List<SavedDevice>) {
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
        _devices.value = list
    }

    private companion object {
        const val KEY = "devices"
    }
}
