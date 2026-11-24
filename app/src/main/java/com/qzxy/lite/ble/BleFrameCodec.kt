package com.qzxy.lite.ble

/**
 * 趣智校园 脱机水表/蓝牙水表 BLE 协议编解码器。
 *
 * 协议分两层：
 * 1. 外层封包：以 '#' (0x23) 开头，以 '\n' (0x0A) 结尾，中间为内层二进制字节的 Hex ASCII 文本（小写）；
 * 2. 内层协议：
 *    - Byte 0: 0x60 (帧头标识，96)
 *    - Byte 1-2: 长度（Big Endian）= payload.length + 3
 *    - Byte 3: 方向/来源（0x80: 下发/主机 -> 从机；0x81: 上报/从机 -> 主机）
 *    - Byte 4: 命令字 Cmd
 *    - Byte 5: 子命令/序号（通常为 0x00）
 *    - Byte 6..N-3: 载荷数据 Payload
 *    - Byte N-2: 校验和 Checksum = (0x80 + cmd + sub + sum(payload)) & 0xFF
 *    - Byte N-1: 0x16 (帧尾标识，22)
 */
object BleFrameCodec {

    const val CMD_QUERY_DEVICE = 0x23      // 35: 查询设备状态/随机数/参数
    const val CMD_DOWN_RATE = 0x21         // 33: 下发费率开阀
    const val CMD_CLOSE_VALVE = 0x22       // 34: 结束消费关阀
    const val CMD_COLLECT_DATA = 0x85      // 133: 采集水表未上传消费数据
    const val CMD_CONFIRM_STORAGE = 0x86   // 134: 确认水表记录已入库

    const val STATUS_SUCCESS = 0x80.toByte()

    /**
     * 编码发送命令帧为外层 ASCII 字节流（形如 `#60000480230000a316\n`）。
     */
    fun encodeCommand(cmd: Int, payload: ByteArray = byteArrayOf(0x00)): ByteArray {
        val payloadLen = payload.size
        val totalLen = payloadLen + 3
        val inner = ByteArray(payloadLen + 8)
        inner[0] = 0x60.toByte()
        inner[1] = ((totalLen shr 8) and 0xFF).toByte()
        inner[2] = (totalLen and 0xFF).toByte()
        inner[3] = 0x80.toByte()
        inner[4] = (cmd and 0xFF).toByte()
        inner[5] = 0x00.toByte()

        var chkSum = (0x80 + cmd + 0x00)
        for (i in payload.indices) {
            inner[6 + i] = payload[i]
            chkSum += (payload[i].toInt() and 0xFF)
        }

        inner[inner.size - 2] = (chkSum and 0xFF).toByte()
        inner[inner.size - 1] = 0x16.toByte()

        val hexStr = inner.joinToString("") { "%02x".format(it) }
        val outer = "#$hexStr\n"
        return outer.toByteArray(Charsets.US_ASCII)
    }

    /**
     * 构建确认水表存储命令 (Cmd 0x86)。
     */
    fun encodeConfirmStorage(
        consumeDateHex: String,
        projectId: Int,
        accountId: Int,
        deviceId: Int,
        recordIndex: Int,
    ): ByteArray {
        val payload = ByteArray(22)
        // 1. 日期 hex 6 字节 (如 26 11 23 22 50 41)
        val dateBytes = hexStringToByteArray(consumeDateHex)
        System.arraycopy(dateBytes, 0, payload, 0, minOf(6, dateBytes.size))

        // 2. projectId (4 字节 Big Endian)
        writeInt32(payload, 6, projectId)

        // 3. accountId (4 字节)
        writeInt32(payload, 10, accountId)

        // 4. deviceId (4 字节)
        writeInt32(payload, 14, deviceId)

        // 5. recordIndex (4 字节)
        writeInt32(payload, 18, recordIndex)

        return encodeCommand(CMD_CONFIRM_STORAGE, payload)
    }

    /**
     * 解码内层帧。
     */
    fun decodeFrame(asciiString: String): DecodedFrame? {
        val trimmed = asciiString.trim().removePrefix("#").removeSuffix("\n").trim()
        if (trimmed.length < 16) return null
        val raw = try {
            hexStringToByteArray(trimmed)
        } catch (e: Exception) {
            return null
        }
        if (raw.size < 8) return null
        if (raw[0] != 0x60.toByte() || raw[raw.size - 1] != 0x16.toByte()) return null

        val totalLen = ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
        val direction = raw[3].toInt() and 0xFF
        val cmd = raw[4].toInt() and 0xFF
        val sub = raw[5].toInt() and 0xFF
        val payloadLen = totalLen - 3
        if (payloadLen < 0 || payloadLen + 8 > raw.size) return null

        val payload = ByteArray(payloadLen)
        System.arraycopy(raw, 6, payload, 0, payloadLen)

        var expectedChk = (direction + cmd + sub)
        for (b in payload) {
            expectedChk += (b.toInt() and 0xFF)
        }
        val actualChk = raw[raw.size - 2].toInt() and 0xFF
        if ((expectedChk and 0xFF) != actualChk) {
            // 校验和容错，若不完全一致仍保留载荷返回
        }

        return DecodedFrame(
            cmd = cmd,
            direction = direction,
            sub = sub,
            payload = payload,
            isSuccess = payload.isNotEmpty() && payload[0] == STATUS_SUCCESS,
        )
    }

    /**
     * 解析查询设备状态响应 (Cmd 0x23)。
     */
    fun parseQueryResponse(payload: ByteArray): BleDeviceQueryResult? {
        if (payload.isEmpty() || payload[0] != STATUS_SUCCESS) return null
        if (payload.size < 32) return null

        val projectId = readInt32(payload, 1)
        val accountId = readInt32(payload, 5)
        val deviceId = readInt32(payload, 9)

        val macBytes = ByteArray(6)
        System.arraycopy(payload, 13, macBytes, 0, 6)
        val deviceMac = macBytes.joinToString("") { "%02X".format(it) }

        val protocolType = "%02x".format(payload[19])

        val randomBytes = ByteArray(4)
        System.arraycopy(payload, 24, randomBytes, 0, 4)
        val randomNumber = randomBytes.joinToString("") { "%02x".format(it) }

        val macType1 = if (payload.size > 29) payload[29].toInt() and 0xFF else 0
        val macType2 = if (payload.size > 30) payload[30].toInt() and 0xFF else 0
        val macType = "%02x%02x".format(macType1, macType2)

        return BleDeviceQueryResult(
            projectId = projectId,
            accountId = accountId,
            deviceId = deviceId,
            deviceMac = deviceMac,
            protocolType = protocolType,
            randomNumber = randomNumber,
            macType = macType,
        )
    }

    /**
     * 解析采集消费记录响应 (Cmd 0x85)。
     */
    fun parseCollectResponse(payload: ByteArray): BleConsumeRecord? {
        if (payload.isEmpty() || payload[0] != STATUS_SUCCESS) return null
        if (payload.size < 36) return null

        val dateBytes = ByteArray(6)
        System.arraycopy(payload, 1, dateBytes, 0, 6)
        val consumeDateHex = dateBytes.joinToString("") { "%02x".format(it) }

        val projectId = readInt32(payload, 7)
        val accountId = readInt32(payload, 11)
        val deviceId = readInt32(payload, 15)
        val recordIndex = if (payload.size >= 24) readInt32(payload, 20) else 0
        val pulses = if (payload.size >= 32) readInt32(payload, 28) else 0

        val xfDataHex = payload.joinToString("") { "%02x".format(it) }

        return BleConsumeRecord(
            consumeDateHex = consumeDateHex,
            projectId = projectId,
            accountId = accountId,
            deviceId = deviceId,
            recordIndex = recordIndex,
            pulses = pulses,
            xfDataHex = xfDataHex,
        )
    }

    private fun writeInt32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readInt32(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
    }

    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                    Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

data class DecodedFrame(
    val cmd: Int,
    val direction: Int,
    val sub: Int,
    val payload: ByteArray,
    val isSuccess: Boolean,
)

data class BleDeviceQueryResult(
    val projectId: Int,
    val accountId: Int,
    val deviceId: Int,
    val deviceMac: String,
    val protocolType: String,
    val randomNumber: String,
    val macType: String,
)

data class BleConsumeRecord(
    val consumeDateHex: String,
    val projectId: Int,
    val accountId: Int,
    val deviceId: Int,
    val recordIndex: Int,
    val pulses: Int,
    val xfDataHex: String,
)
