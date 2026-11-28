package com.qzxy.lite

import com.klcxkj.jni.JniUtils
import com.qzxy.lite.ble.BleFrameCodec
import com.qzxy.lite.core.formatColonMac
import com.qzxy.lite.core.hexToByteArray
import com.qzxy.lite.core.md5Hex
import com.qzxy.lite.core.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAndCryptoTest {

    @Test
    fun `md5Hex and hex conversions are consistent`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
        val bytes = byteArrayOf(0x01, 0xAB.toByte(), 0xFF.toByte())
        assertEquals("01abff", bytes.toHexString())
        assertEquals("01ABFF", bytes.toHexString(uppercase = true))
        assertArrayEquals(bytes, "01:AB:FF".hexToByteArray())
        assertEquals("C4:7F:0E:3E:59:C5", formatColonMac("c47f0e3e59c5"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToByteArray rejects odd length hex strings`() {
        "abc".hexToByteArray()
    }

    @Test
    fun `BleFrameCodec encodes and decodes query command frame`() {
        val encoded = BleFrameCodec.encodeCommand(BleFrameCodec.CMD_QUERY_DEVICE)
        val ascii = String(encoded, Charsets.US_ASCII)
        assertEquals("#60000480230000a316\n", ascii)

        val decoded = BleFrameCodec.decodeFrame(ascii)
        assertNotNull(decoded)
        assertEquals(BleFrameCodec.CMD_QUERY_DEVICE, decoded!!.cmd)
        assertEquals(0x80, decoded.direction)
        assertTrue(decoded.checksumValid)
        assertArrayEquals(byteArrayOf(0x00), decoded.payload)
    }

    @Test
    fun `BleFrameCodec parses 0x23 query response payload`() {
        val payload = ByteArray(32)
        payload[0] = BleFrameCodec.STATUS_SUCCESS
        // projectId = 201 (0x000000C9)
        payload[4] = 0xC9.toByte()
        // accountId = 21864 (0x00005568)
        payload[7] = 0x55
        payload[8] = 0x68
        // deviceId = 1941 (0x00000795)
        payload[11] = 0x07
        payload[12] = 0x95.toByte()
        // mac = C4 7F 0E 3E 59 C5
        byteArrayOf(0xC4.toByte(), 0x7F, 0x0E, 0x3E, 0x59, 0xC5.toByte())
            .copyInto(payload, destinationOffset = 13)
        // protocolType = 0x10
        payload[19] = 0x10
        // randomNumber = E1 00 00 00
        payload[24] = 0xE1.toByte()
        // valve open = 1, macType = 00 01
        payload[28] = 0x01
        payload[30] = 0x01

        val result = BleFrameCodec.parseQueryResponse(payload)
        assertNotNull(result)
        assertEquals(201, result!!.projectId)
        assertEquals(21864, result.accountId)
        assertEquals(1941, result.deviceId)
        assertEquals("C47F0E3E59C5", result.deviceMac)
        assertEquals("10", result.protocolType)
        assertEquals("e1000000", result.randomNumber)
        assertEquals("0001", result.macType)
        assertTrue(result.isValveOpen)
    }

    @Test
    fun `JniUtils pure Kotlin double-MD5 signature matches specification`() {
        val loginCode = "c4ca4238a0b923820dcc509a6f75849b"
        val params = mapOf(
            "telephone" to "13800000000",
            "deviceId" to "1941",
            "xfModel" to "0",
            "randomNumber" to "e1000000",
        )
        val expectedRaw = "deviceId1941randomNumbere1000000telephone13800000000xfModel0&key=$loginCode"
        val expected = md5Hex("${md5Hex(expectedRaw)}&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744")
        assertEquals(expected, JniUtils.sign(loginCode, params))
        assertEquals("sign-kailu=", JniUtils.getSk())
    }
}
