package com.qzxy.lite.data.api

import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.data.model.BathDevice
import retrofit2.http.GET
import retrofit2.http.Query

/** 设备详情（v3-api）。 */
interface DeviceApi {

    /**
     * 扫码/BLE 识别后取设备信息（原版实测：macAddress 带冒号 URL 编码，
     * 另带 isNew=1、isHaier=0、projectId）。
     */
    @GET("device/info/mac")
    suspend fun deviceByMac(
        @Query("macAddress") macAddress: String? = null,
        @Query("identifyCode") identifyCode: String? = null,
        @Query("isNew") isNew: String = "1",
        @Query("isHaier") isHaier: String = "0",
    ): ApiResponse<BathDevice>
}
