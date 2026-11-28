package com.qzxy.lite.data.repository

import com.qzxy.lite.core.dataOrThrow
import com.qzxy.lite.data.api.CampusApi
import com.qzxy.lite.data.api.DeviceApi
import com.qzxy.lite.data.model.BathDevice
import kotlinx.serialization.json.JsonObject

/**
 * 校园/设备选择：镜像原版 BathChoseActivity——列表走 v3-api/device/bath/list/area（areaId 空串=全部）。
 * 注意：**wfwapi 不认 v3-api 的 loginCode**（实测同码在 wfwapi 返回 errorCode=8），
 * 所以不要把需要会话的请求发往 wfwapi。
 */
class CampusRepository(
    private val campusApi: CampusApi,
    private val deviceApi: DeviceApi,
) {

    suspend fun projectInfo(): JsonObject = campusApi.projectInfo().dataOrThrow()

    /** 浴室/热水器列表（v3-api，areaId 空串=全部区域）。 */
    suspend fun bathList(): List<BathDevice> = campusApi.bathListByArea(areaId = "").dataOrThrow()

    /** 扫码取设备（macAddress 或 identifyCode 二选一，由二维码内容决定）。 */
    suspend fun deviceByMac(macAddress: String? = null, identifyCode: String? = null): BathDevice =
        deviceApi.deviceByMac(macAddress, identifyCode).dataOrThrow()
}
