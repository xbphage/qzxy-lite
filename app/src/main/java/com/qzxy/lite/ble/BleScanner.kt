package com.qzxy.lite.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 一次 BLE 广播 sightings（已按原版规则过滤+归一化）。 */
data class BleSighting(
    val normalizedMac: String,
    val broadcastMac: String,
    val advName: String,
    val rssi: Int,
)

/**
 * BLE 扫描器：复刻原版 SearchDeviceListActivity 的识别规则（notes/BLE-RECOGNITION.md）：
 * 1) 广播名以 `KLCXKJ` 开头才认；2) rssi==0 丢弃；3) 归一化 MAC（前缀 C0→00）后按 MAC 去重。
 * 每条 sighting 都会发射（含重复），去重/取最强 RSSI 由上层维护。
 */
class BleScanner(context: Context) {

    private val appContext = context.applicationContext

    fun scan(): Flow<BleSighting> = callbackFlow {
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            close(IllegalStateException("蓝牙未开启或不可用"))
            return@callbackFlow
        }

        // 与原版一致：不在 ScanFilter 里过滤，而是在回调里按前缀过滤（BluetoothKit 同款行为）。

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.rssi == 0) return
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (name == null || !name.startsWith(ADV_PREFIX)) return
                val raw = result.device.address.uppercase()
                val normalized = normalizeMac(raw)
                trySend(BleSighting(normalized, raw, name, result.rssi))
            }
        }

        scanner.startScan(callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    companion object {
        const val ADV_PREFIX = "KLCXKJ"

        /** 原版归一化规则：广播 MAC 前 2 位为 C0 时替换为 00。 */
        fun normalizeMac(raw: String): String {
            val mac = raw.uppercase()
            return if (mac.take(2) == "C0") "00" + mac.substring(2) else mac
        }
    }
}
