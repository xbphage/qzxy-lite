package com.qzxy.lite.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.qzxy.lite.BuildConfig
import com.qzxy.lite.core.DiagLog
import com.qzxy.lite.core.formatColonMac
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 趣智校园 脱机水表 BLE GATT 客户端。
 *
 * 负责：
 * 1. 建立 GATT 连接，发现 0xFF00/0xFF01/0xFF02 (或 0xFEE7/0xFEA2/0xFEA1)；
 * 2. 开启 0x2902 通知；
 * 3. 20 字节分包下发命令流；
 * 4. 拼包接收回包通知流；
 * 5. 封装开阀、关阀、采集、存储确认等高层协议。
 */
class BleWaterMeterClient(
    private val context: Context,
) {
    private val mutex = Mutex()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null

    /** 当前是否已建立 GATT 连接并就绪通知/写入特征值。 */
    val isConnected: Boolean
        get() = bluetoothGatt != null && writeChar != null && notifyChar != null

    private var connectionDeferred: CompletableDeferred<Boolean>? = null
    private var readyDeferred: CompletableDeferred<Boolean>? = null

    // 累加接收缓冲区（拆包 + 去噪 + 上限保护）
    private val framingBuffer = BleLineFramingBuffer()
    private val receivedFrames = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) return
            if (BuildConfig.DEBUG) Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionDeferred?.complete(true)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionDeferred?.complete(false)
                readyDeferred?.complete(false)
                cleanup()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (BuildConfig.DEBUG) Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                readyDeferred?.complete(false)
                return
            }

            // 查找对应服务与特征值
            var foundService = gatt.getService(UUID_SERVICE_FF00)
            var wChar = foundService?.getCharacteristic(UUID_WRITE_FF02)
            var nChar = foundService?.getCharacteristic(UUID_NOTIFY_FF01)

            if (foundService == null || wChar == null || nChar == null) {
                // 降级尝试 FEE7
                foundService = gatt.getService(UUID_SERVICE_FEE7)
                wChar = foundService?.getCharacteristic(UUID_WRITE_FEA1)
                nChar = foundService?.getCharacteristic(UUID_NOTIFY_FEA2)
            }

            if (foundService != null && wChar != null && nChar != null) {
                writeChar = wChar
                notifyChar = nChar

                // 开启通知
                gatt.setCharacteristicNotification(nChar, true)
                val descriptor = nChar.getDescriptor(UUID_CCCD)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                } else {
                    readyDeferred?.complete(true)
                }
            } else {
                if (BuildConfig.DEBUG) Log.w(TAG, "Suitable BLE services/characteristics not found!")
                readyDeferred?.complete(false)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (BuildConfig.DEBUG) Log.d(TAG, "onDescriptorWrite status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readyDeferred?.complete(true)
            } else {
                readyDeferred?.complete(false)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== bluetoothGatt) return
            @Suppress("DEPRECATION")
            onRxBytes(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== bluetoothGatt) return
            onRxBytes(value)
        }
    }

    private fun onRxBytes(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val lines = synchronized(framingBuffer) { framingBuffer.append(bytes) }
        for (line in lines) {
            if (BuildConfig.DEBUG) Log.d(TAG, "RX Frame cmd=${line.take(20).trim()}")
            DiagLog.d("ble", "RX ${line.trim()}")
            receivedFrames.tryEmit(line)
        }
    }

    /**
     * 连接水表并等待服务发现和通知使能就绪。
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceMac: String, timeoutMs: Long = 10000L): Result<Unit> = mutex.withLock {
        val adapter = bluetoothAdapter ?: return Result.failure(IllegalStateException("设备不支持蓝牙"))
        if (!adapter.isEnabled) return Result.failure(IllegalStateException("蓝牙未开启，请先打开手机蓝牙"))

        val cleanMac = formatColonMac(deviceMac)
        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to BLE water meter: $cleanMac")

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(cleanMac)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("无效的蓝牙 MAC 地址: $deviceMac"))
        }

        cleanup()
        val connDef = CompletableDeferred<Boolean>()
        val readyDef = CompletableDeferred<Boolean>()
        connectionDeferred = connDef
        readyDeferred = readyDef

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
        bluetoothGatt = gatt

        val ready = withTimeoutOrNull(timeoutMs) {
            readyDef.await()
        }

        if (ready == true) {
            Result.success(Unit)
        } else {
            cleanup()
            Result.failure(IllegalStateException("水表蓝牙连接超时，请确认靠近设备"))
        }
    }

    /**
     * 写入分包数据（20 字节切片），并对控制器忙状态做短重试。
     */
    @SuppressLint("MissingPermission")
    private suspend fun writeRaw(data: ByteArray) {
        val gatt = bluetoothGatt ?: throw IllegalStateException("BLE not connected")
        val char = writeChar ?: throw IllegalStateException("Write characteristic not available")

        val chunkSize = 20
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            offset = end

            var sent = false
            for (attempt in 0 until 3) {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        char,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    char.value = chunk
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(char)
                }
                if (ok) {
                    sent = true
                    break
                }
                delay(25)
            }
            if (!sent) throw IllegalStateException("蓝牙写入特征值失败（写入忙或连接已断开）")
            delay(20)
        }
    }

    /**
     * 发送命令并同步订阅期待的响应帧。
     * 先用 UNDISPATCHED 启动订阅再发送数据，避免 MCU 在写包间隔内快速回包导致漏帧；
     * 同时强制校验帧校验和（checksumValid），防止无线干扰坏帧污染业务解析。
     */
    private suspend fun sendAndAwaitFrame(
        cmd: Int,
        cmdBytes: ByteArray,
        timeoutMs: Long,
        timeoutMessage: String,
    ): Result<DecodedFrame> = coroutineScope {
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMs) {
                receivedFrames
                    .mapNotNull { BleFrameCodec.decodeFrame(it) }
                    .first { it.cmd == cmd && it.checksumValid }
            }
        }
        try {
            writeRaw(cmdBytes)
        } catch (t: Throwable) {
            deferred.cancel()
            return@coroutineScope Result.failure(t)
        }
        val frame = deferred.await()
            ?: return@coroutineScope Result.failure(IllegalStateException(timeoutMessage))
        Result.success(frame)
    }

    /** 查询水表参数与随机数 (Cmd 0x23)。 */
    suspend fun queryDevice(timeoutMs: Long = 4000L): Result<BleDeviceQueryResult> {
        val cmdBytes = BleFrameCodec.encodeCommand(BleFrameCodec.CMD_QUERY_DEVICE, byteArrayOf(0x00))
        val decoded = sendAndAwaitFrame(
            cmd = BleFrameCodec.CMD_QUERY_DEVICE,
            cmdBytes = cmdBytes,
            timeoutMs = timeoutMs,
            timeoutMessage = "查询水表参数响应超时",
        ).getOrElse { return Result.failure(it) }
        val result = BleFrameCodec.parseQueryResponse(decoded.payload)
        return result?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("解析水表响应失败"))
    }

    /** 下发费率开阀 (Cmd 0x21)。 */
    suspend fun downRate(downDataHex: String, timeoutMs: Long = 5000L): Result<Unit> {
        val payload = runCatching { BleFrameCodec.hexStringToByteArray(downDataHex) }
            .getOrElse { return Result.failure(IllegalStateException("开阀指令格式无效: ${it.message}")) }
        val cmdBytes = BleFrameCodec.encodeCommand(BleFrameCodec.CMD_DOWN_RATE, payload)
        val decoded = sendAndAwaitFrame(
            cmd = BleFrameCodec.CMD_DOWN_RATE,
            cmdBytes = cmdBytes,
            timeoutMs = timeoutMs,
            timeoutMessage = "水表下发费率开阀响应超时",
        ).getOrElse { return Result.failure(it) }
        return if (decoded.isSuccess) {
            Result.success(Unit)
        } else {
            val errByte = decoded.payload.getOrNull(0)?.toInt()?.and(0xFF)?.toString(16)
            Result.failure(IllegalStateException("水表开阀失败（错误码 0x$errByte）"))
        }
    }

    /** 结束消费关阀 (Cmd 0x22)。 */
    suspend fun closeValve(timeoutMs: Long = 4000L): Result<Unit> {
        val cmdBytes = BleFrameCodec.encodeCommand(BleFrameCodec.CMD_CLOSE_VALVE, byteArrayOf(0x00))
        val decoded = sendAndAwaitFrame(
            cmd = BleFrameCodec.CMD_CLOSE_VALVE,
            cmdBytes = cmdBytes,
            timeoutMs = timeoutMs,
            timeoutMessage = "水表关阀响应超时",
        ).getOrElse { return Result.failure(it) }
        return if (decoded.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("水表关阀返回未成功"))
        }
    }

    /** 采集消费账单数据 (Cmd 0x85)。 */
    suspend fun collectData(timeoutMs: Long = 4000L): Result<BleConsumeRecord> {
        val cmdBytes = BleFrameCodec.encodeCommand(BleFrameCodec.CMD_COLLECT_DATA, byteArrayOf(0x00))
        val decoded = sendAndAwaitFrame(
            cmd = BleFrameCodec.CMD_COLLECT_DATA,
            cmdBytes = cmdBytes,
            timeoutMs = timeoutMs,
            timeoutMessage = "采集水表消费账单响应超时",
        ).getOrElse { return Result.failure(it) }
        val result = BleFrameCodec.parseCollectResponse(decoded.payload)
        return result?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("解析水表消费账单失败"))
    }

    /** 确认水表记录入库存储 (Cmd 0x86)。 */
    suspend fun confirmStorage(
        consumeDateHex: String,
        projectId: Int,
        accountId: Int,
        deviceId: Int,
        recordIndex: Int,
        timeoutMs: Long = 3000L,
    ): Result<Unit> {
        val cmdBytes = BleFrameCodec.encodeConfirmStorage(
            consumeDateHex = consumeDateHex,
            projectId = projectId,
            accountId = accountId,
            deviceId = deviceId,
            recordIndex = recordIndex,
        )
        val decoded = sendAndAwaitFrame(
            cmd = BleFrameCodec.CMD_CONFIRM_STORAGE,
            cmdBytes = cmdBytes,
            timeoutMs = timeoutMs,
            timeoutMessage = "确认水表存储超时",
        ).getOrElse { return Result.failure(it) }
        return if (decoded.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("确认水表存储失败"))
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        cleanup()
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        val oldGatt = bluetoothGatt
        // 先清空当前引用再关闭旧 GATT，避免同步回调进入产生递归 cleanup
        bluetoothGatt = null
        writeChar = null
        notifyChar = null
        connectionDeferred?.cancel()
        connectionDeferred = null
        readyDeferred?.cancel()
        readyDeferred = null
        runCatching { oldGatt?.disconnect() }
        runCatching { oldGatt?.close() }
        synchronized(framingBuffer) { framingBuffer.clear() }
    }

    companion object {
        private const val TAG = "BleWaterMeterClient"

        val UUID_SERVICE_FF00: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
        val UUID_NOTIFY_FF01: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val UUID_WRITE_FF02: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")

        val UUID_SERVICE_FEE7: UUID = UUID.fromString("0000FEE7-0000-1000-8000-00805F9B34FB")
        val UUID_WRITE_FEA1: UUID = UUID.fromString("0000FEA1-0000-1000-8000-00805F9B34FB")
        val UUID_NOTIFY_FEA2: UUID = UUID.fromString("0000FEA2-0000-1000-8000-00805F9B34FB")

        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
