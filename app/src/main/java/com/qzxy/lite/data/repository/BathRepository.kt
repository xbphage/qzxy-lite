package com.qzxy.lite.data.repository

import android.util.Log
import com.klcxkj.jni.JniUtils
import com.qzxy.lite.ble.BleWaterMeterClient
import com.qzxy.lite.core.ApiException
import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.core.dataOrThrow
import com.qzxy.lite.core.successOrThrow
import com.qzxy.lite.data.api.DeviceApi
import com.qzxy.lite.data.api.OrderApi
import com.qzxy.lite.data.model.BathDevice
import com.qzxy.lite.data.model.BluetoothDownRateData
import com.qzxy.lite.data.model.BluetoothUploadData
import com.qzxy.lite.data.model.MoneyEstimate
import com.qzxy.lite.data.model.OrderResult
import com.qzxy.lite.data.session.SessionStore
import kotlinx.serialization.json.JsonObject

/**
 * 洗澡链路仓库：
 * 支持两种模式：
 * 1. 4G/TCP 在线水表：使用 tcpDevice/downRate 远程下发开阀；
 * 2. 蓝牙脱机水表（高校无网水表）：手机作为蓝牙网关，先调云端 downRate/bluetooth/rateOrder 签发开阀指令，
 *    再通过手机 BLE 将指令写入水表开阀；洗浴结束后 BLE 关阀、采集水表消费记录并回传给云端结算扣费。
 */
class BathRepository(
    private val orderApi: OrderApi,
    private val bleClient: BleWaterMeterClient? = null,
    private val sessionStore: SessionStore? = null,
    private val deviceApi: DeviceApi? = null,
) {

    /** 记录最近一次 0x23 查询得到的水表动态随机数，供关阀结算上报使用 */
    @Volatile
    private var lastRandomNumber: String? = null

    // ==================== 1. TCP 在线水表链路 ====================

    /** 当前使用中订单（原始响应，含 307 时 data.orderNo）。 */
    suspend fun usingOrderRaw(
        snCode: String,
        deviceSncode: String = snCode,
    ): ApiResponse<JsonObject> =
        orderApi.usingRateOrder(xfModel = "0", snCode = snCode, deviceSncode = deviceSncode)

    /** 开阀下单（原始响应；成功时 data 可能为 null，orderNo 要从轮询里取）。 */
    suspend fun openRaw(
        snCode: String,
        deviceSncode: String = snCode,
        xfModel: String = "0",
    ): ApiResponse<OrderResult> =
        orderApi.tcpRateOrder(xfModel = xfModel, snCode = snCode, deviceSncode = deviceSncode)

    /** 开机结果轮询（原始响应；errorCode=1"设备通讯中"=仍在执行，需继续轮询）。 */
    suspend fun pollOpenRaw(
        snCode: String,
        deviceSncode: String = snCode,
        xfModel: String = "0",
    ): ApiResponse<OrderResult> =
        orderApi.tcpDownRateResult(xfModel = xfModel, snCode = snCode, deviceSncode = deviceSncode)

    /** 关阀下单（成功时 data=null，用 successOrThrow，勿用 dataOrThrow）。 */
    suspend fun close(snCode: String, orderNo: String): Unit =
        orderApi.tcpCloseOrder(snCode = snCode, orderNo = orderNo).successOrThrow()

    /** 关阀结果轮询（原始响应）。 */
    suspend fun closeResultRaw(snCode: String, orderNo: String): ApiResponse<OrderResult> =
        orderApi.closeOrderResultQuery(snCode = snCode, orderNo = orderNo)

    /**
     * 消费结果/账单。
     * 金额单位：JSON 整数=0.001 元（190=¥0.19），JSON 字符串=元（"0.39"=¥0.39）；
     * 换算统一在 core/Money.kt，不要在这里或 UI 里按「分」除以 100。
     * 关阀瞬间设备数据未上传时 consumeMoney 可能为 0，表示「尚未结算」而非「消费 0 元」。
     */
    suspend fun consumeResult(orderNo: String): JsonObject =
        orderApi.consumeResult(orderNo = orderNo).dataOrThrow()

    /** 预估消费（原版流程未用，备用）。 */
    suspend fun estimate(snCode: String): MoneyEstimate =
        orderApi.perMoney(snCode = snCode).dataOrThrow()

    // ==================== 2. 蓝牙脱机水表链路 ====================

    /**
     * 蓝牙脱机开阀全流程：
     * ① 连接设备蓝牙 GATT
     * ② 发送 Cmd 0x23 查询水表参数、随机数及 MAC
     * ③ 组装参数与 JNI 签名向服务端申请下发费率指令
     * ④ 将服务端返回的 downData 指令通过 BLE 写入水表开阀
     */
    suspend fun openBle(
        deviceMac: String,
        bigTypeId: String = "1",
        smallTypeId: String = "1",
        couponId: String? = null,
        onBleStatus: (String) -> Unit = {},
    ): BluetoothDownRateData {
        val client = bleClient ?: throw IllegalStateException("BLE client not initialized")
        val session = sessionStore?.current() ?: throw IllegalStateException("未登录，请先登录")
        val phone = session.telephone

        onBleStatus("正在连接水表蓝牙...")
        client.connect(deviceMac).getOrThrow()

        onBleStatus("正在读取水表状态与认证参数...")
        val queryResult = client.queryDevice().getOrThrow()
        Log.d(TAG, "BLE Query success: $queryResult")

        onBleStatus("正在向云端获取设备信息...")
        // 官方链路对比：官方在进入开阀流程前，必须先获取云端登记的 DeviceInfo (包含 DevID, Dsbtypeid 等)。
        // 水表 BLE 0x23 响应中包含的 deviceId (即 bArrA[9..12]) 是水表 EEPROM 内置的物理芯片/出厂序号，
        // 云端数据库中按 DevID 查找设备。若直接向接口发送水表内部物理 ID，云端因找不到设备记录报错 519 "找不到设备"！
        val cloudDevice: BathDevice? = runCatching {
            deviceApi?.deviceByMac(macAddress = queryResult.deviceMac)?.data
        }.getOrNull() ?: runCatching {
            val uncoloned = queryResult.deviceMac.replace(":", "")
            deviceApi?.deviceByMac(macAddress = uncoloned)?.data
        }.getOrNull() ?: runCatching {
            deviceApi?.deviceByMac(macAddress = deviceMac)?.data
        }.getOrNull()

        val cloudDevId = cloudDevice?.cloudDeviceId
        val deviceIdStr = if (!cloudDevId.isNullOrBlank()) {
            Log.d(TAG, "Using cloud registered DevID: $cloudDevId (BLE EEPROM ID was ${queryResult.deviceId})")
            cloudDevId
        } else {
            Log.w(TAG, "Cloud DevID not found for ${queryResult.deviceMac}, falling back to BLE EEPROM ID: ${queryResult.deviceId}")
            queryResult.deviceId.toString()
        }

        // 官方沐浴水表大类 ID 默认是 4 (Dsbtypeid=4)，小类默认是 1 (DevTypeID=1)
        val resolvedBigTypeId = cloudDevice?.resolvedBigTypeId ?: if (bigTypeId == "1") "4" else bigTypeId
        val resolvedSmallTypeId = cloudDevice?.resolvedSmallTypeId ?: smallTypeId

        onBleStatus("正在向云端申请开阀授权指令...")
        val macWithoutColons = queryResult.deviceMac.replace(":", "").uppercase()
        val randomNumber = queryResult.randomNumber
        lastRandomNumber = randomNumber
        val protocolType = queryResult.protocolType
        val macType = queryResult.macType

        // 构造签名参数并执行签名（使用云端 DevID）
        val signParams = mapOf(
            "telephone" to phone,
            "deviceId" to deviceIdStr,
            "xfModel" to "0",
            "randomNumber" to randomNumber,
        )
        val signature = JniUtils.sign(session.loginCode, signParams)

        // 请求云端开阀指令（仅网络/HTTP/网关错误时回退离线端点；业务错误如 204 金额不足、531 防连点直接透传）
        val downRateResp = try {
            val r = orderApi.bluetoothRateOrder(
                deviceId = deviceIdStr,
                macAddress = macWithoutColons,
                macType = macType,
                bigTypeId = resolvedBigTypeId,
                smallTypeId = resolvedSmallTypeId,
                protocolType = protocolType,
                randomNumber = randomNumber,
                signature = signature,
                xfModel = "0",
                couponId = couponId,
            )
            if (r.errorCode == 0 && r.data != null) {
                r
            } else if (r.errorCode == ApiException.INSUFFICIENT_BALANCE_CODE) {
                val withholdHint = cloudDevice?.withholdMoney
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "（设备开阀需预扣 ¥$it，请确认账户余额充足或前往钱包充值）" }
                    .orEmpty()
                throw ApiException(r.errorCode, "${r.effectiveMessage}$withholdHint")
            } else {
                throw ApiException(r.errorCode, r.effectiveMessage)
            }
        } catch (e: Exception) {
            if (!shouldFallbackToOffline(e)) throw e
            Log.w(TAG, "bluetoothRateOrder network/gateway failed (${e.message}), trying offline endpoint")
            orderApi.offlineBluetoothRateOrder(
                deviceId = deviceIdStr,
                macAddress = macWithoutColons,
                macType = macType,
                bigTypeId = resolvedBigTypeId,
                smallTypeId = resolvedSmallTypeId,
                protocolType = protocolType,
                randomNumber = randomNumber,
                signature = signature,
                xfModel = "0",
                couponId = couponId,
            )
        }

        val rateData = downRateResp.dataOrThrow()
        val downDataHex = rateData.downData ?: throw IllegalStateException("服务端未下发开阀指令")

        onBleStatus("正在向水表写入开阀指令...")
        val writeResult = client.downRate(downDataHex)
        if (writeResult.isFailure) {
            // 上报开阀失败
            rateData.orderNo?.let { orderNo ->
                runCatching { orderApi.bluetoothUploadFail(orderNo, "BLE_WRITE_FAILED") }
            }
            throw writeResult.exceptionOrNull() ?: IllegalStateException("开阀指令下发失败")
        }

        onBleStatus("水表已开阀出水")
        return rateData
    }

    /**
     * 蓝牙脱机关阀全流程：
     * ① 向水表发送 Cmd 0x22 关阀
     * ② 向水表发送 Cmd 0x85 采集本次消费记录 (xfData)
     * ③ 组装签名上传云端结算扣款 (upload/bluetooth/data)
     * ④ 向水表发送 Cmd 0x86 确认记录入库
     * ⑤ 断开蓝牙连接
     */
    suspend fun closeBle(
        deviceMac: String,
        orderNo: String?,
        onBleStatus: (String) -> Unit = {},
    ): BluetoothUploadData {
        val client = bleClient ?: throw IllegalStateException("BLE client not initialized")
        val session = sessionStore?.current() ?: throw IllegalStateException("未登录，请先登录")
        val phone = session.telephone

        onBleStatus("正在发送关阀指令...")
        client.closeValve().getOrThrow()

        onBleStatus("正在读取水表消费账单...")
        val record = client.collectData().getOrThrow()
        Log.d(TAG, "BLE Collect record: $record")

        onBleStatus("正在上传水表账单并结算扣费...")
        val signParams = mapOf(
            "loginCode" to session.loginCode,
            "telephone" to phone,
            "xfData" to record.xfDataHex,
        )
        val signature = JniUtils.sign(session.loginCode, signParams)
        val uploadRandomNumber = lastRandomNumber ?: "00000000"

        val uploadResp = try {
            val r = orderApi.bluetoothUploadData(
                protocolType = "10",
                randomNumber = uploadRandomNumber,
                xfData = record.xfDataHex,
                signature = signature,
            )
            if (r.errorCode == 0 && r.data != null) r else throw ApiException(r.errorCode, r.effectiveMessage)
        } catch (e: Exception) {
            if (!shouldFallbackToOffline(e)) throw e
            Log.w(TAG, "bluetoothUploadData network/gateway failed (${e.message}), trying offline endpoint")
            orderApi.offlineBluetoothUploadData(
                protocolType = "10",
                randomNumber = uploadRandomNumber,
                xfData = record.xfDataHex,
                signature = signature,
            )
        }

        // 确认水表存储
        onBleStatus("正在同步水表确认状态...")
        runCatching {
            client.confirmStorage(
                consumeDateHex = record.consumeDateHex,
                projectId = record.projectId,
                accountId = record.accountId,
                deviceId = record.deviceId,
                recordIndex = record.recordIndex,
            )
        }

        if (!orderNo.isNullOrBlank()) {
            runCatching { orderApi.bathroomOrderClosed(orderNo) }
        }

        client.disconnect()
        onBleStatus("结账完成")
        return uploadResp.dataOrThrow()
    }

    fun disconnectBle() {
        bleClient?.disconnect()
    }

    /** 仅网络异常、HTTP 失败或网关错误 502502 时回退到离线网关，业务错误码（如 204 余额不足、531 防连点）直接透传。 */
    private fun shouldFallbackToOffline(e: Throwable): Boolean = when (e) {
        is ApiException -> e.code == 502502 ||
            e.code == ApiException.NETWORK_ERROR_CODE ||
            e.code == ApiException.HTTP_ERROR_CODE
        else -> true
    }

    companion object {
        private const val TAG = "BathRepository"
    }
}
