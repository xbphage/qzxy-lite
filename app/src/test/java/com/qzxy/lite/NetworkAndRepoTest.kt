package com.qzxy.lite

import com.qzxy.lite.core.ApiException
import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.data.api.OrderApi
import com.qzxy.lite.data.model.BluetoothDownRateData
import com.qzxy.lite.data.model.BluetoothUploadData
import com.qzxy.lite.data.model.OrderResult
import com.qzxy.lite.data.network.CommonParamsInterceptor
import com.qzxy.lite.data.network.SessionExpiredInterceptor
import com.qzxy.lite.data.network.SimpleCookieJar
import com.qzxy.lite.data.repository.BathRepository
import com.qzxy.lite.data.session.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkAndRepoTest {

    @Test
    fun `CommonParamsInterceptor only injects params for exact china-qzxy domain and strips retry marker`() {
        val interceptor = CommonParamsInterceptor(
            sessionResolver = {
                Session(
                    loginCode = "tok123",
                    userId = "1",
                    accountId = "2",
                    projectId = "9",
                    telephone = "13800000000",
                )
            },
        )

        var capturedOfficial: Request? = null
        interceptor.intercept(
            FakeChain(
                Request.Builder()
                    .url("https://v3-api.china-qzxy.cn/user/info")
                    .header(CommonParamsInterceptor.HEADER_RETRIED, "1")
                    .build(),
            ) { req ->
                capturedOfficial = req
                okJson(req, """{"errorCode":0}""")
            },
        )
        assertNull(capturedOfficial!!.header(CommonParamsInterceptor.HEADER_RETRIED))
        assertEquals("tok123", capturedOfficial!!.url.queryParameter("loginCode"))
        assertEquals("9", capturedOfficial!!.header("Config-Project"))

        var capturedLookalike: Request? = null
        interceptor.intercept(
            FakeChain(Request.Builder().url("https://evil-china-qzxy.cn/user/info").build()) { req ->
                capturedLookalike = req
                okJson(req, """{"errorCode":0}""")
            },
        )
        assertNull(capturedLookalike!!.url.queryParameter("loginCode"))
        assertNull(capturedLookalike!!.header("Config-Project"))
    }

    @Test
    fun `SessionExpiredInterceptor skips duplicate silent re-login when token already rotated`() {
        var currentToken = "old_tok"
        val reLoginCalls = AtomicInteger(0)
        val interceptor = SessionExpiredInterceptor(
            currentSessionToken = { currentToken },
            canSilentReLogin = { true },
            performSilentReLogin = {
                reLoginCalls.incrementAndGet()
                currentToken = "new_tok"
                true
            },
        )

        // 请求 A 先行触发重登并将 token 换为 new_tok
        val respA = interceptor.intercept(
            FakeChain(Request.Builder().url("https://v3-api.china-qzxy.cn/account/wallet").build()) { req ->
                if (req.header(CommonParamsInterceptor.HEADER_RETRIED) == null) {
                    okJson(req, """{"errorCode":8,"message":"expired"}""")
                } else {
                    okJson(req, """{"errorCode":0,"data":{}}""")
                }
            },
        )
        assertEquals(200, respA.code)
        assertEquals(1, reLoginCalls.get())

        // 模拟先前已在飞行的请求 B（构造时 currentToken 为 old_tok）在锁外等到期间 token 已被更新
        currentToken = "old_tok"
        val callsWithStaleBefore = AtomicInteger(0)
        val concurrentInterceptor = SessionExpiredInterceptor(
            currentSessionToken = {
                if (callsWithStaleBefore.incrementAndGet() == 1) "old_tok" else "new_tok"
            },
            canSilentReLogin = { true },
            performSilentReLogin = {
                reLoginCalls.incrementAndGet()
                true
            },
        )
        concurrentInterceptor.intercept(
            FakeChain(Request.Builder().url("https://v3-api.china-qzxy.cn/user/info").build()) { req ->
                if (req.header(CommonParamsInterceptor.HEADER_RETRIED) == null) {
                    okJson(req, """{"errorCode":8}""")
                } else {
                    okJson(req, """{"errorCode":0}""")
                }
            },
        )
        // 未触发二次重登
        assertEquals(1, reLoginCalls.get())
    }

    @Test
    fun `SimpleCookieJar merges cookies by name without wiping prior WAF cookies`() {
        val jar = SimpleCookieJar()
        val url = "https://v3-api.china-qzxy.cn/account/wallet".toHttpUrl()
        val waf = Cookie.Builder().name("aliyungf_tc").value("waf1").domain("v3-api.china-qzxy.cn").build()
        val acw = Cookie.Builder().name("acw_tc").value("waf2").domain("v3-api.china-qzxy.cn").build()

        jar.saveFromResponse(url, listOf(waf))
        jar.saveFromResponse(url, listOf(acw))

        val loaded = jar.loadForRequest(url).associate { it.name to it.value }
        assertEquals(mapOf("aliyungf_tc" to "waf1", "acw_tc" to "waf2"), loaded)
    }

    @Test
    fun `BathRepository callWithOfflineFallback enforces business error check on offline response`() = runBlocking {
        val fakeApi = object : OrderApi {
            override suspend fun usingRateOrder(xfModel: String, snCode: String, deviceSncode: String) =
                ApiResponse<JsonObject>()
            override suspend fun tcpRateOrder(xfModel: String, snCode: String, deviceSncode: String) =
                ApiResponse<OrderResult>()
            override suspend fun tcpDownRateResult(xfModel: String, snCode: String, deviceSncode: String) =
                ApiResponse<OrderResult>()
            override suspend fun tcpCloseOrder(snCode: String, orderNo: String) =
                ApiResponse<OrderResult>()
            override suspend fun closeOrderResultQuery(snCode: String, orderNo: String) =
                ApiResponse<OrderResult>()
            override suspend fun consumeResult(orderNo: String) = ApiResponse<JsonObject>()
            override suspend fun bluetoothRateOrder(
                deviceId: String, macAddress: String, macType: String, bigTypeId: String,
                smallTypeId: String, protocolType: String, randomNumber: String, signature: String,
                xfModel: String, couponId: String?,
            ) = ApiResponse<BluetoothDownRateData>()
            override suspend fun offlineBluetoothRateOrder(
                deviceId: String, macAddress: String, macType: String, bigTypeId: String,
                smallTypeId: String, protocolType: String, randomNumber: String, signature: String,
                xfModel: String, couponId: String?,
            ) = ApiResponse<BluetoothDownRateData>()
            override suspend fun bluetoothUploadData(
                protocolType: String, randomNumber: String, xfData: String, signature: String,
            ) = ApiResponse<BluetoothUploadData>()
            override suspend fun offlineBluetoothUploadData(
                protocolType: String, randomNumber: String, xfData: String, signature: String,
            ) = ApiResponse<BluetoothUploadData>()
            override suspend fun bluetoothUploadFail(orderNo: String, reason: String?) = ApiResponse<JsonObject>()
            override suspend fun bathroomOrderClosed(orderNo: String) = ApiResponse<JsonObject>()
        }

        val repo = BathRepository(fakeApi)
        var thrownCode: Int? = null
        try {
            repo.callWithOfflineFallback(
                actionName = "test",
                primary = { throw IOException("primary unreachable") },
                offline = { ApiResponse<String>(errorCode = 204, message = "余额不足", data = null) },
            )
        } catch (e: ApiException) {
            thrownCode = e.code
        }
        assertEquals(204, thrownCode)
    }

    private fun okJson(req: Request, json: String): Response = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(json.toResponseBody("application/json; charset=utf-8".toMediaType()))
        .build()

    private class FakeChain(
        private val req: Request,
        private val handler: (Request) -> Response,
    ) : Interceptor.Chain {
        override fun request(): Request = req
        override fun proceed(request: Request): Response = handler(request)
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 10_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis(): Int = 30_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis(): Int = 30_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
    }
}
