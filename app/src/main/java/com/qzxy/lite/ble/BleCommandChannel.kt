package com.qzxy.lite.ble

/**
 * 蓝牙下发抽象层。
 *
 * 已由 [BleWaterMeterClient] 与 [BleFrameCodec] 完整还原并实现：
 * - 协议特征值：Service 0xFF00, Notify 0xFF01, Write 0xFF02 (降级 0xFEE7/0xFEA2/0xFEA1)
 * - 帧格式：#60...16\n 封装与校验和计算
 * - 原版 JNI 参数签名已通过 libklcxkjencry.so 及降级 MD5 算法对齐
 */
interface BleCommandChannel {
    suspend fun send(deviceMac: String, command: ByteArray): Result<Unit>
    suspend fun read(deviceMac: String): Result<ByteArray>
}

/** 基于 BleWaterMeterClient 的正式实现。 */
class BleWaterMeterCommandChannel(
    private val client: BleWaterMeterClient,
) : BleCommandChannel {
    override suspend fun send(deviceMac: String, command: ByteArray): Result<Unit> {
        val hex = command.joinToString("") { "%02x".format(it) }
        return client.downRate(hex)
    }

    override suspend fun read(deviceMac: String): Result<ByteArray> {
        return client.collectData().map { BleFrameCodec.hexStringToByteArray(it.xfDataHex) }
    }
}
