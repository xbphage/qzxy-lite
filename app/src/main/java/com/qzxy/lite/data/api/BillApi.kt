package com.qzxy.lite.data.api

import com.qzxy.lite.core.ApiResponse
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 账单 / 退款（2026-11-14 文档实测定稿，见 notes/账单与退款.md）：
 * - 全部在 v3-api，GET + 公共参数（拦截器注入）；
 * - 账单流水的 data 实测为**数组**（[{billType:1,...}]），用 JsonElement 兼容数组/对象两种形态；
 * - 退款：本项目入口当前关闭（refundSwitch=0），lite 仅做"退款进度查看"。
 */
interface BillApi {

    /** 账户账单流水列表（MyBillInfoV3Activity 同款；data 为数组）。 */
    @GET("order/query/account/bill/list")
    suspend fun accountBillList(
        @Query("month") month: String,
        @Query("billRequestType") billRequestType: String = "1",
    ): ApiResponse<JsonElement>

    /** 未结算账单数量。 */
    @GET("order/account/runningBill/num/query")
    suspend fun runningBillNum(): ApiResponse<JsonElement>

    /** 月账单。 */
    @GET("wallet/billList/month")
    suspend fun walletBillMonth(@Query("month") month: String): ApiResponse<JsonElement>

    /** 未支付账单。 */
    @GET("wallet/billList/unPay")
    suspend fun walletBillUnPay(): ApiResponse<JsonElement>

    /** 退款记录列表（本项目入口关闭，仅查看进度）。 */
    @GET("wallet/application/list")
    suspend fun refundList(): ApiResponse<JsonElement>

    /** 退款详情。 */
    @GET("wallet/application/detail")
    suspend fun refundDetail(@Query("id") id: String): ApiResponse<JsonElement>
}
