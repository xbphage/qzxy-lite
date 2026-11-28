package com.qzxy.lite

import com.qzxy.lite.core.md5Hex
import com.qzxy.lite.tmall.CampusProbeResult
import com.qzxy.lite.tmall.CookieRow
import com.qzxy.lite.tmall.SavedWaterDevice
import com.qzxy.lite.tmall.TmallAliLogin
import com.qzxy.lite.tmall.TmallJson
import com.qzxy.lite.tmall.TmallMtopClient
import com.qzxy.lite.tmall.TmallMtopError
import com.qzxy.lite.tmall.TmallQrCodec
import com.qzxy.lite.tmall.TmallWalletAccount
import com.qzxy.lite.tmall.TmallWaterApi
import com.qzxy.lite.tmall.TmallWaterDevice
import com.qzxy.lite.tmall.TmallWaterSessionStore
import com.qzxy.lite.tmall.WaterDeviceOrder
import com.qzxy.lite.tmall.WaterDeviceSortMode
import com.qzxy.lite.tmall.WaterQuickUse
import com.qzxy.lite.tmall.WaterStartResult
import com.qzxy.lite.tmall.mergeTmallCookieRows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TmallWaterTest {

    @Test
    fun `TmallQrCodec parses confong url query param bare tmxy and numeric resNo`() {
        val urlQr = TmallQrCodec.parse("https://share.confong.cn/xh?id=tmxy869451084947543")
        assertNotNull(urlQr)
        assertEquals("tmxy869451084947543", urlQr!!.code)

        val deviceCodeQr = TmallQrCodec.parse("https://campus.example.com/scan?from=app&deviceCode=630102040")
        assertNotNull(deviceCodeQr)
        assertEquals("630102040", deviceCodeQr!!.code)

        val bareTmxy = TmallQrCodec.parse("  tmxy869451084947543  ")
        assertEquals("tmxy869451084947543", bareTmxy!!.code)

        val bareNum = TmallQrCodec.parse("630102040")
        assertEquals("630102040", bareNum!!.code)

        assertNull(TmallQrCodec.parse(""))
        assertNull(TmallQrCodec.parse("random-text-without-id"))
        assertNull(TmallQrCodec.parse("12345"))
    }

    @Test
    fun `TmallMtopClient signs parses tokens and keeps confong cookie2 isolated`() {
        assertEquals("deadbeef", TmallMtopClient.parseToken("deadbeef_1700000000000"))
        assertEquals("", TmallMtopClient.parseToken(null))
        assertEquals(
            md5Hex("tok1&1700000&12574478&{}"),
            TmallMtopClient.sign("tok1", "1700000", "12574478", "{}"),
        )

        val compact = TmallJson.stringify(mapOf("a" to 1, "b" to "你好"))
        assertEquals("""{"a":1,"b":"你好"}""", compact)

        val client = TmallMtopClient(
            cookies = linkedMapOf("cookie2" to "cm_val"),
        )
        client.absorb(
            listOf(
                "cookie2=tb_val; Path=/; Domain=.taobao.com",
                "_m_h5_tk=abc_999; Path=/; Domain=.taobao.com",
            ),
        )
        assertEquals("cm_val", client.export()["cookie2"])
        assertEquals("abc", client.token())

        client.absorb(listOf("cookie2=cm_new; Path=/; Domain=.confong.cn"))
        assertEquals("cm_new", client.export()["cookie2"])
    }

    @Test
    fun `mergeTmallCookieRows prioritizes confong cookie2 and forStorage drops tracking cookies`() {
        val merged = mergeTmallCookieRows(
            listOf(
                CookieRow(".confong.cn", "cookie2", "cm_secret"),
                CookieRow(".taobao.com", "cookie2", "tb_secret"),
                CookieRow(".taobao.com", "unb", "1234567890"),
                CookieRow(".taobao.com", "tfstk", "tracking_noise"),
            ),
        )
        assertEquals("cm_secret", merged["cookie2"])
        val cleaned = TmallWaterSessionStore.forStorage(merged)
        assertEquals("cm_secret", cleaned["cookie2"])
        assertEquals("1234567890", cleaned["unb"])
        assertFalse(cleaned.containsKey("tfstk"))

        assertEquals("12345***901", TmallWaterSessionStore.maskAccount("12345678901"))
        assertEquals("123456", TmallWaterSessionStore.maskAccount("123456"))
    }

    @Test
    fun `TmallMtopClient auto-retries FAIL_SYS_TOKEN_EMPTY handshake and parses WaterApi responses`() {
        var calls = 0
        val seenSigns = mutableListOf<String>()
        val client = TmallMtopClient(
            cookies = linkedMapOf("cookie2" to "cm_token", "unb" to "1008611"),
            transport = { url, _ ->
                calls++
                val sign = Regex("sign=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
                seenSigns.add(sign)
                if (calls == 1) {
                    """{"ret":["FAIL_SYS_TOKEN_EMPTY::令牌为空"]}""" to listOf(
                        "_m_h5_tk=freshToken_1700000; Domain=.taobao.com; Path=/",
                    )
                } else {
                    """
                    {
                      "ret": ["SUCCESS::调用成功"],
                      "data": {
                        "data": {
                          "deviceResponse": {
                            "deviceCode": "630102040",
                            "deviceId": "dev-99",
                            "deviceName": "三楼直饮水机",
                            "address": "3F东侧",
                            "buildingName": "学五公寓",
                            "campusAreaName": "主校区",
                            "onlineStatus": "ONLINE",
                            "deviceCanUse": true,
                            "faultStatus": "WELL_FUNCTIONING",
                            "runningStatus": "IDLE"
                          },
                          "bizTimeConfig": { "isInBusinessTime": true }
                        }
                      }
                    }
                    """.trimIndent() to emptyList()
                }
            },
        )

        val device = TmallWaterApi(client).deviceInfo("tmxy869451084947543")
        assertEquals(2, calls)
        assertEquals("freshToken", client.token())
        assertTrue(seenSigns[0] != seenSigns[1])
        assertEquals("630102040", device.resNo)
        assertEquals("三楼直饮水机", device.name)
        assertTrue(device.unlockable)
    }

    @Test
    fun `TmallWaterApi probeCampusUser retries once on GET_CAMPUS_USER_ERROR and start parses order`() {
        var attempts = 0
        val client = TmallMtopClient(
            cookies = linkedMapOf("_m_h5_tk" to "tok_1"),
            transport = { url, _ ->
                attempts++
                when {
                    url.contains(TmallWaterApi.API_CAMPUS_USER_GET) && attempts == 1 ->
                        """{"ret":["GET_CAMPUS_USER_ERROR::暂未获取校园用户"]}""" to emptyList()
                    url.contains(TmallWaterApi.API_CAMPUS_USER_GET) ->
                        """{"ret":["SUCCESS::调用成功"],"data":{"campusName":"主校区"}}""" to emptyList()
                    url.contains(TmallWaterApi.API_WATER_START) ->
                        """{"ret":["SUCCESS::调用成功"],"data":{"isvOrderId":"ORD-001","shareServiceInfoId":"SRV-01"}}""" to emptyList()
                    else ->
                        """{"ret":["SUCCESS::调用成功"],"data":{}}""" to emptyList()
                }
            },
        )
        val api = TmallWaterApi(client)
        val probe = api.probeCampusUser()
        assertEquals(CampusProbeResult(true, "SUCCESS", "校园身份正常"), probe)
        assertEquals(2, attempts)

        val start = api.start("630102040")
        assertEquals("ORD-001", start.isvOrderId)
        assertEquals("SRV-01", start.serviceInfoId)
    }

    @Test
    fun `TmallAliLogin detects punish slider captcha URL on sendSms`() {
        val client = TmallMtopClient(
            transport = { _, _ ->
                """{"ret":["RGV587_ERROR::风控校验"],"data":{"url":"https://h5api.m.taobao.com/h5/punish?x5=abc"}}""" to emptyList()
            },
        )
        val res = TmallAliLogin(client).sendSms("13800138000", "umid-test")
        assertEquals(
            TmallAliLogin.SendResult.NeedCaptcha("https://h5api.m.taobao.com/h5/punish?x5=abc"),
            res,
        )
    }

    @Test
    fun `WaterDeviceOrder sorts recent custom and enforces max device cap`() {
        val list = listOf(
            SavedWaterDevice("d1", name = "A", lastUsedAt = 100L),
            SavedWaterDevice("d2", name = "B", lastUsedAt = 300L),
            SavedWaterDevice("d3", name = "C", lastUsedAt = 200L),
        )
        assertEquals(listOf("d2", "d3", "d1"), WaterDeviceOrder.recent(list).map { it.resNo })
        assertEquals(listOf("d3", "d1", "d2"), WaterDeviceOrder.customIds(list, listOf("d3", "missing", "d1")))
        assertEquals(listOf("d3", "d1", "d2"), WaterDeviceOrder.pinToTop(list, "d3").map { it.resNo })
        assertEquals(listOf("d1", "d2", "d3"), WaterDeviceOrder.pinToTop(list, "not-found").map { it.resNo })
        assertEquals(WaterDeviceSortMode.RECENT, WaterDeviceOrder.mode("UNKNOWN"))

        val maxList = (1..WaterDeviceOrder.MAX_DEVICES).map { SavedWaterDevice("dev$it") }
        assertFalse(WaterDeviceOrder.canRemember(maxList, "dev31"))
        assertTrue(WaterDeviceOrder.canRemember(maxList, "dev5"))
    }

    @Test
    fun `WaterQuickUse guards concurrent starts validates device health and detects network uncertainty`() = runBlocking {
        val flow = WaterQuickUse()
        var nestedResult: WaterStartResult? = WaterStartResult("should-not-happen", "", "")
        val dev = TmallWaterDevice(
            query = "630102040",
            resNo = "630102040",
            deviceId = "d1",
            name = "饮水机",
            address = "1F",
            building = "A栋",
            campus = "主区",
            online = true,
            canUse = true,
            inBusinessTime = true,
            runningStatus = "IDLE",
            faultText = "",
        )
        val res = flow.start(
            resNo = "630102040",
            query = { resNo ->
                nestedResult = flow.start(resNo, query = { dev }, unlock = { WaterStartResult("x", "", "") })
                dev
            },
            unlock = { WaterStartResult("ORD-2", "SRV-2", "{}") },
        )
        assertNull(nestedResult)
        assertEquals("ORD-2", res!!.isvOrderId)

        try {
            flow.start(
                resNo = "630102040",
                query = { dev.copy(online = false) },
                unlock = { fail("should not unlock offline device"); WaterStartResult("", "", "") },
            )
            fail("expected exception for offline device")
        } catch (e: IllegalStateException) {
            assertEquals("设备当前离线", e.message)
        }

        assertTrue(WaterQuickUse.unlockUnconfirmed(IOException("timeout")))
        assertTrue(WaterQuickUse.unlockUnconfirmed(TmallMtopError(listOf("NETWORK_ERROR::reset"))))
        assertFalse(WaterQuickUse.unlockUnconfirmed(TmallMtopError(listOf("1048::订单未找到"))))
    }

    @Test
    fun `TmallWaterApi parses wallet accounts device fund info and recent records in fen`() {
        val client = TmallMtopClient(
            cookies = linkedMapOf("_m_h5_tk" to "tok_w1"),
            transport = { url, _ ->
                when {
                    url.contains(TmallWaterApi.API_DEVICE_INFO) ->
                        """
                        {
                          "ret": ["SUCCESS::调用成功"],
                          "data": {
                            "data": {
                              "deviceResponse": {
                                "deviceCode": "630102040",
                                "deviceId": "869451084947543",
                                "deviceName": "直饮水",
                                "campusAreaId": 500960,
                                "merchantId": 14735,
                                "relationshipId": 10915,
                                "onlineStatus": "ONLINE",
                                "deviceCanUse": true
                              },
                              "deviceBusinessAttributeDTO": { "businessType": "WASH_AND_CARE" },
                              "bizTimeConfig": { "isInBusinessTime": true }
                            }
                          }
                        }
                        """.trimIndent() to emptyList()
                    url.contains(TmallWaterApi.API_FUND_ACCOUNT_LIST) ->
                        """
                        {
                          "ret": ["SUCCESS::调用成功"],
                          "data": {
                            "response": [
                              {
                                "id": 101,
                                "fundName": "直饮水钱包",
                                "relationShipId": 10915,
                                "fundPlatform": "TMXY",
                                "campusAreaCode": "500960",
                                "subjectCode": "WATER",
                                "canRecharge": true,
                                "fundAccount": {
                                  "fundAccountId": "9001",
                                  "totalUsableAmount": 1250,
                                  "totalFreezeAmount": 50,
                                  "statusName": "FROZEN"
                                }
                              }
                            ]
                          }
                        }
                        """.trimIndent() to emptyList()
                    url.contains(TmallWaterApi.API_FUND_DEVICE_INFO) ->
                        """
                        {
                          "ret": ["SUCCESS::调用成功"],
                          "data": {
                            "freezeAmount": 200,
                            "waterActivity": { "hasActivity": true, "name": "校园直饮水" },
                            "fundAccreditAndAccountGetResponse": {
                              "fundName": "宿舍水卡",
                              "relationShipId": 10915,
                              "fundPlatform": "TMXY",
                              "subjectCode": "WATER",
                              "canRecharge": true,
                              "fundAccount": {
                                "fundAccreditId": "202",
                                "fundAccountId": "9002",
                                "totalUsableAmount": 805
                              }
                            }
                          }
                        }
                        """.trimIndent() to emptyList()
                    url.contains(TmallWaterApi.API_FUND_ACCOUNT_HOMEPAGE) ->
                        """
                        {
                          "ret": ["SUCCESS::调用成功"],
                          "data": {
                            "response": {
                              "accountResponse": {
                                "id": 101,
                                "fundName": "直饮水钱包",
                                "fundAccount": {
                                  "fundAccountId": "9001",
                                  "totalUsableAmount": 1200,
                                  "totalFreezeAmount": 0,
                                  "statusName": "NORMAL"
                                }
                              },
                              "orderResponse": {
                                "data": [
                                  {
                                    "totalAmountStr": "-0.50",
                                    "sourceInfo": "三楼饮水机消费",
                                    "outOrderId": "ORD-88",
                                    "createTimeStr": "2026-11-28 10:15:00",
                                    "attr": { "remark": "200ml" }
                                  }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent() to emptyList()
                    else -> """{"ret":["SUCCESS::调用成功"],"data":{}}""" to emptyList()
                }
            },
        )
        val api = TmallWaterApi(client)

        val dev = api.deviceInfo("tmxy869451084947543")
        assertEquals("500960", dev.campusAreaId)
        assertEquals("14735", dev.merchantId)
        assertEquals("10915", dev.relationshipId)
        assertEquals("WASH_AND_CARE", dev.businessType)

        val accounts = api.fundAccountList()
        assertEquals(1, accounts.size)
        assertEquals("12.50", accounts[0].usableDisplay)
        assertEquals("0.50", accounts[0].freezeDisplay)
        assertTrue(accounts[0].isWithdrawingFrozen)

        val (devWallet, actName) = api.fundDeviceInfo(dev)
        assertEquals("校园直饮水", actName)
        assertEquals("8.05", devWallet!!.usableDisplay)
        assertEquals("2.00", devWallet.freezeDisplay)

        val (refreshed, records) = api.fundAccountHomePage(accounts[0])
        assertEquals("12.00", refreshed!!.usableDisplay)
        assertFalse(refreshed.isWithdrawingFrozen)
        assertEquals(1, records.size)
        assertEquals("-0.50", records[0].amountDisplay)
        assertEquals("三楼饮水机消费", records[0].sourceInfo)
        assertEquals("200ml", records[0].remark)

        val neg = TmallWalletAccount(fundAccreditId = "1", fundName = "测试", usableFen = -5)
        assertEquals("-0.05", neg.usableDisplay)
    }
}
