package com.qzxy.lite.tmall

import java.io.IOException

/** 串口化解锁校验流程（迁移自 TMallKey WaterQuickUse）。 */
internal class WaterQuickUse {

    companion object {
        fun unlockUnconfirmed(error: Throwable): Boolean =
            error is IOException || (error is TmallMtopError && error.code == "NETWORK_ERROR")
    }

    private var running = false

    suspend fun start(
        resNo: String,
        query: suspend (String) -> TmallWaterDevice,
        unlock: suspend (String) -> WaterStartResult,
    ): WaterStartResult? {
        if (running) return null
        running = true
        try {
            val device = query(resNo)
            check(device.resNo == resNo) { "设备编号不匹配，请重新扫码添加这台饮水机" }
            check(device.unlockable) {
                when {
                    !device.online -> "设备当前离线"
                    !device.inBusinessTime -> "不在营业时段"
                    !device.canUse -> "设备暂不可用"
                    else -> device.faultText.ifBlank { "设备状态异常" }
                }
            }
            return unlock(resNo)
        } finally {
            running = false
        }
    }
}

/**
 * 天猫校园饮水仓库：封装会话 Cookie 装配、设备识别、一键解锁与结束接水。
 */
class TmallWaterRepository(
    val sessionStore: TmallWaterSessionStore,
    val deviceStore: TmallWaterDeviceStore,
) {
    private fun apiClient(): TmallMtopClient {
        val jar = sessionStore.current()
        if (jar.isEmpty()) throw TmallMtopError(listOf("FAIL_SYS_SESSION_EXPIRED::请先登录天猫校园账号"))
        return TmallMtopClient(jar.toMutableMap())
    }

    private fun api(): TmallWaterApi = TmallWaterApi(apiClient())

    fun probeCampusUser(): CampusProbeResult = api().probeCampusUser()

    fun fetchAndSaveDevice(queryCode: String): Pair<TmallWaterDevice, Boolean> {
        val client = apiClient()
        val device = TmallWaterApi(client).deviceInfo(queryCode)
        syncClientCookiesIfChanged(client)
        val saved = deviceStore.remember(device)
        return device to saved
    }

    fun checkDeviceInfo(resNo: String): TmallWaterDevice {
        val client = apiClient()
        val device = TmallWaterApi(client).deviceInfo(resNo)
        syncClientCookiesIfChanged(client)
        return device
    }

    fun startWater(resNo: String, source: String = "FAST_START"): WaterStartResult {
        val client = apiClient()
        val res = TmallWaterApi(client).start(resNo, source)
        syncClientCookiesIfChanged(client)
        deviceStore.touch(resNo)
        return res
    }

    fun stopWater(resNo: String, isvOrderId: String = ""): String {
        val client = apiClient()
        val raw = TmallWaterApi(client).stop(resNo, isvOrderId)
        syncClientCookiesIfChanged(client)
        return raw
    }

    fun adoptSession(jar: Map<String, String>) {
        sessionStore.adopt(jar)
    }

    fun logout() {
        sessionStore.clear()
    }

    private fun syncClientCookiesIfChanged(client: TmallMtopClient) {
        val updated = client.export()
        if (updated.isNotEmpty() && updated != sessionStore.current()) {
            sessionStore.adopt(updated)
        }
    }
}
