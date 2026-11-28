package com.qzxy.lite.data.api

import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.data.model.BathDevice
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 校园/浴室列表（端点在 v3-api，2026-11-13 探测确认）。
 * - device/bath/list/area：GET，areaId 可为空串（新预约模式）。
 */
interface CampusApi {

    /** 学校三级信息。 */
    @GET("project/info/triple")
    suspend fun projectInfo(): ApiResponse<JsonObject>

    /** 按区域拉浴室/淋浴位列表（新预约模式；areaId 空串=全部）。 */
    @GET("device/bath/list/area")
    suspend fun bathListByArea(
        @Query("areaId") areaId: String? = null,
        @Query("buildingId") buildingId: String? = null,
        @Query("floorId") floorId: String? = null,
    ): ApiResponse<List<BathDevice>>
}
