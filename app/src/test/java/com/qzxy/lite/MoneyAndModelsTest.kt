package com.qzxy.lite

import com.qzxy.lite.data.model.BathDevice
import com.qzxy.lite.data.model.BluetoothDownRateData
import com.qzxy.lite.data.model.BluetoothUploadData
import com.qzxy.lite.data.model.WalletInfo
import com.qzxy.lite.data.network.NetworkModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyAndModelsTest {

    private val json = NetworkModule.json

    @Test
    fun `BluetoothUploadData converts integer milli-yuan to yuan strings`() {
        val raw = """
            {
              "orderNo": 202611280001,
              "consumeMoney": 280,
              "balance": 49680
            }
        """.trimIndent()
        val parsed = json.decodeFromString(BluetoothUploadData.serializer(), raw)
        assertEquals("202611280001", parsed.orderNo)
        assertEquals("0.28", parsed.resolvedConsumeMoney)
        assertEquals("49.68", parsed.resolvedBalance)
    }

    @Test
    fun `BluetoothUploadData supports PostConsumeData upMoney fallback and yuan strings`() {
        val intPayload = """{"upMoney": 190, "upLeadMoney": 0, "perMoney": 5000}"""
        val intParsed = json.decodeFromString(BluetoothUploadData.serializer(), intPayload)
        assertEquals("0.19", intParsed.resolvedConsumeMoney)
        assertEquals("5", intParsed.perMoney)
        assertNull(intParsed.resolvedBalance)

        val strPayload = """{"orderNo": "99", "consumeMoney": "0.32", "balance": "49.68"}"""
        val strParsed = json.decodeFromString(BluetoothUploadData.serializer(), strPayload)
        assertEquals("0.32", strParsed.resolvedConsumeMoney)
        assertEquals("49.68", strParsed.resolvedBalance)
    }

    @Test
    fun `BluetoothDownRateData handles both numeric milli-yuan and string yuan`() {
        val numericRaw = """{"orderNo": 1001, "perMoney": 5000, "realMoney": 20000, "minMoney": 100}"""
        val numParsed = json.decodeFromString(BluetoothDownRateData.serializer(), numericRaw)
        assertEquals("5", numParsed.perMoney)
        assertEquals("20", numParsed.realMoney)
        assertEquals("0.1", numParsed.minMoney)

        val stringRaw = """{"orderNo": "1002", "perMoney": "5.00", "realMoney": "15.50", "minMoney": "0.10"}"""
        val strParsed = json.decodeFromString(BluetoothDownRateData.serializer(), stringRaw)
        assertEquals("5", strParsed.perMoney)
        assertEquals("15.5", strParsed.realMoney)
        assertEquals("0.1", strParsed.minMoney)
    }

    @Test
    fun `BathDevice decodes numeric withholdMoney and offline BLE flags`() {
        val raw = """
            {
              "deviceId": 1941,
              "DevID": 1941,
              "bigTypeId": 4,
              "smallTypeId": 1,
              "communicationTypeId": 0,
              "onlineStatusId": 0,
              "withholdMoney": 5.0,
              "deductionMethod": 0,
              "snCode": "C47F0E3E59C5"
            }
        """.trimIndent()
        val device = json.decodeFromString(BathDevice.serializer(), raw)
        assertEquals("1941", device.cloudDeviceId)
        assertEquals("4", device.resolvedBigTypeId)
        assertEquals("1", device.resolvedSmallTypeId)
        assertEquals("5.0", device.withholdMoney)
        assertEquals("0", device.deductionMethod)
        assertTrue(device.isOfflineBleOnly)

        val online = device.copy(communicationTypeId = "1", onlineStatusId = "1")
        assertFalse(online.isOfflineBleOnly)

        val legacy = json.decodeFromString(
            BathDevice.serializer(),
            """{"DevID": "2002", "Dsbtypeid": 5, "DevTypeID": 2}""",
        )
        assertEquals("2002", legacy.cloudDeviceId)
        assertEquals("5", legacy.resolvedBigTypeId)
        assertEquals("2", legacy.resolvedSmallTypeId)
    }

    @Test
    fun `WalletInfo displays money string or falls back to sum of real and given`() {
        val direct = json.decodeFromString(
            WalletInfo.serializer(),
            """{"accountRealMoney": 0, "accountGivenMoney": 0, "money": "19.810"}""",
        )
        assertEquals("19.81", direct.display)

        val fallback = json.decodeFromString(
            WalletInfo.serializer(),
            """{"accountRealMoney": 1500, "accountGivenMoney": 250}""",
        )
        assertEquals("1.75", fallback.display)
    }
}
