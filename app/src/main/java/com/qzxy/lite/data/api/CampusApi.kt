package com.qzxy.lite.data.api

import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.data.model.AreaItem
import com.qzxy.lite.data.model.BathDevice
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 校园/区域/浴室列表（端点在 v3-api，2026-11-13 探测确认）。
 * - device/bath/list/area：GET，areaId 可为空串（新预约模式，MyApp.e=true 路径）；
 * - device/area/list/new：POST 表单，仅工单报修（OrderRepairActivity）使用；
 * - device/bathroom/list 在 v3-api 不存在（走 [WfwapiApi]，BathChoseActivity 生产路径）。
 */
interface CampusApi {

    /** 学校三级信息。 */
    @GET("project/info/triple")
    suspend fun projectInfo(): ApiResponse<JsonObject>

    /** 楼栋/区域层级列表（POST 表单，工单选位置用）。 */
    @FormUrlEncoded
    @POST("device/area/list/new")
    suspend fun areaListNew(
        @Field("markId") markId: String? = null,
        @Field("areaId") areaId: String? = null,
        @Field("buildingId") buildingId: String? = null,
        @Field("floorId") floorId: String? = null,
        @Field("roomId") roomId: String? = null,
    ): ApiResponse<List<AreaItem>>

    /** 按区域拉浴室/淋浴位列表（新预约模式；areaId 空串=全部）。 */
    @GET("device/bath/list/area")
    suspend fun bathListByArea(
        @Query("areaId") areaId: String? = null,
        @Query("buildingId") buildingId: String? = null,
        @Query("floorId") floorId: String? = null,
    ): ApiResponse<List<BathDevice>>

    /** 浴室列表 + 排名。 */
    @GET("device/bath/listAndRank")
    suspend fun bathListAndRank(
        @Query("bathroomId") bathroomId: String? = null,
        @Query("myRankFlag") myRankFlag: String? = null,
    ): ApiResponse<JsonObject>
}
