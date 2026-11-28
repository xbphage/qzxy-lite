package com.qzxy.lite.tmall

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedWaterDevice(
    val resNo: String,
    val deviceId: String = "",
    val name: String = "饮水机",
    val location: String = "",
    val lastUsedAt: Long = 0L,
)

enum class WaterDeviceSortMode { RECENT, CUSTOM }

internal object WaterDeviceOrder {
    const val MAX_DEVICES = 30

    fun mode(value: String?): WaterDeviceSortMode =
        WaterDeviceSortMode.entries.firstOrNull { it.name == value } ?: WaterDeviceSortMode.RECENT

    fun recent(devices: List<SavedWaterDevice>): List<SavedWaterDevice> =
        devices.sortedByDescending { it.lastUsedAt }

    fun customIds(devices: List<SavedWaterDevice>, preferred: List<String>): List<String> {
        val existing = devices.map { it.resNo }.toSet()
        return (preferred.filter { it in existing } + existing).distinct()
    }

    fun canRemember(devices: List<SavedWaterDevice>, resNo: String): Boolean =
        devices.size < MAX_DEVICES || devices.any { it.resNo == resNo }
}

/**
 * 本地已保存的天猫校园饮水机列表存储（支持最近使用排序与自定义拖拽/调序，最多 30 台）。
 */
class TmallWaterDeviceStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _sortMode = MutableStateFlow(WaterDeviceOrder.mode(prefs.getString(SORT_MODE, null)))
    val sortMode: StateFlow<WaterDeviceSortMode> = _sortMode.asStateFlow()

    private val _devices = MutableStateFlow(orderedList())
    val devices: StateFlow<List<SavedWaterDevice>> = _devices.asStateFlow()

    @Synchronized
    fun remember(device: TmallWaterDevice): Boolean {
        val raw = readDevices().toMutableList()
        if (!WaterDeviceOrder.canRemember(raw, device.resNo)) return false
        val idx = raw.indexOfFirst { it.resNo == device.resNo }
        val old = if (idx >= 0) raw[idx] else null
        val entry = SavedWaterDevice(
            resNo = device.resNo,
            deviceId = device.deviceId.ifBlank { old?.deviceId.orEmpty() },
            name = device.name.ifBlank { old?.name ?: "饮水机" },
            location = listOfNotNull(
                device.building.ifBlank { null },
                device.address.ifBlank { null },
            ).joinToString(" · ").ifBlank { old?.location.orEmpty() },
            lastUsedAt = old?.lastUsedAt ?: 0L,
        )
        if (idx >= 0) raw[idx] = entry else raw.add(entry)
        save(raw)
        return true
    }

    @Synchronized
    fun touch(resNo: String) {
        val raw = readDevices().toMutableList()
        val idx = raw.indexOfFirst { it.resNo == resNo }
        if (idx < 0) return
        raw[idx] = raw[idx].copy(lastUsedAt = System.currentTimeMillis())
        save(raw)
    }

    @Synchronized
    fun remove(resNo: String) {
        save(readDevices().filterNot { it.resNo == resNo })
    }

    @Synchronized
    fun setSortMode(mode: WaterDeviceSortMode) {
        val editor = prefs.edit().putString(SORT_MODE, mode.name)
        if (mode == WaterDeviceSortMode.CUSTOM && readCustomOrder() == null) {
            val ids = WaterDeviceOrder.recent(readDevices()).map { it.resNo }
            editor.putString(CUSTOM_ORDER, json.encodeToString(ids))
        }
        editor.apply()
        _sortMode.value = mode
        _devices.value = orderedList()
    }

    @Synchronized
    fun saveCustomOrder(resNos: List<String>) {
        val ids = WaterDeviceOrder.customIds(readDevices(), resNos)
        prefs.edit().putString(CUSTOM_ORDER, json.encodeToString(ids)).apply()
        _devices.value = orderedList()
    }

    private fun orderedList(): List<SavedWaterDevice> {
        val raw = readDevices()
        if (_sortMode.value == WaterDeviceSortMode.RECENT) return WaterDeviceOrder.recent(raw)
        val byId = raw.associateBy { it.resNo }
        val ids = readCustomOrder() ?: WaterDeviceOrder.recent(raw).map { it.resNo }
        return WaterDeviceOrder.customIds(raw, ids).map(byId::getValue)
    }

    private fun readCustomOrder(): List<String>? {
        val raw = prefs.getString(CUSTOM_ORDER, null) ?: return null
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
    }

    private fun readDevices(): List<SavedWaterDevice> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedWaterDevice>>(raw)
                .filter { it.resNo.isNotBlank() }
                .distinctBy { it.resNo }
                .take(WaterDeviceOrder.MAX_DEVICES)
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<SavedWaterDevice>) {
        val editor = prefs.edit().putString(KEY_LIST, json.encodeToString(list))
        readCustomOrder()?.let { ids ->
            editor.putString(CUSTOM_ORDER, json.encodeToString(WaterDeviceOrder.customIds(list, ids)))
        }
        editor.apply()
        _devices.value = orderedList()
    }

    private companion object {
        const val PREF = "qzxy_tmall_water_devices"
        const val KEY_LIST = "list_v1"
        const val SORT_MODE = "sort_mode"
        const val CUSTOM_ORDER = "custom_order"
    }
}
