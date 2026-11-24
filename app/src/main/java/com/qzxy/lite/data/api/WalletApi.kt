package com.qzxy.lite.data.api

import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.data.model.PayMode
import com.qzxy.lite.data.model.WalletInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 余额 / 充值（2026-11-13 充值流程文档实测定稿，见 notes/充值支付流程.md）：
 * - 端点在 v3-api（生产等价路径为 wfwapi wallet 系列，由 MyApp.e 双网关开关切换）；
 * - account/wallet、user/account/money/all 实测为 **POST 表单**（GET 传参被判"参数为空"），
 *   Retrofit 要求 @FormUrlEncoded 至少一个 @Field，这里放 projectId（拦截器不重复注入）；
 * - 充值下单：settlement/weChat|alipay/recharge/app/balance，表单 payWay=5(微信)/rechargeMoney/signature/telPhone；
 *   signature 生成逻辑未还原（native/工具类），先用验证码同款 secret 试探，以服务端返回为准。
 */
interface WalletApi {

    /** 钱包余额（GET + 公共参数；原版同款调用，见 logcat_device.txt 22:18:19）。 */
    @GET("account/wallet")
    suspend fun wallet(): ApiResponse<WalletInfo>

    /** 各类账户资金汇总。 */
    @GET("user/account/money/all")
    suspend fun allMoney(): ApiResponse<JsonObject>

    /** 充值金额档位/套餐列表（v3 等价：settlement/recharge/package/list；data 为数组）。 */
    @GET("settlement/recharge/package/list")
    suspend fun rechargePackages(): ApiResponse<JsonElement>

    /** 可用支付方式开关（v3 等价：settlement/recharge/pay/mode）。 */
    @GET("settlement/recharge/pay/mode")
    suspend fun payModes(): ApiResponse<JsonElement>

    /** 充值满赠规则（金额档位的"送 X 元"）。 */
    @GET("settlement/recharge/given/rule/count")
    suspend fun givenRuleCount(): ApiResponse<JsonElement>

    /** 微信充值下单（j3() 实测参数：payWay=5、rechargeMoney、signature、telPhone）。data = SDK 拉起串（字符串）。 */
    @FormUrlEncoded
    @POST("settlement/weChat/recharge/app/balance")
    suspend fun rechargeWeChat(
        @Field("payWay") payWay: String,
        @Field("rechargeMoney") rechargeMoney: String,
        @Field("signature") signature: String,
        @Field("telPhone") telPhone: String,
    ): ApiResponse<String>

    /** 支付宝充值下单（原版 U2() 实测：payWay=2；v3 路径 settlement/alipay/recharge/balance）。data = SDK 拉起串。 */
    @FormUrlEncoded
    @POST("settlement/alipay/recharge/balance")
    suspend fun rechargeAliPay(
        @Field("payWay") payWay: String = "2",
        @Field("rechargeMoney") rechargeMoney: String,
        @Field("signature") signature: String,
        @Field("telPhone") telPhone: String,
    ): ApiResponse<String>

    /** 月账单。 */
    @GET("wallet/billList/month")
    suspend fun billMonth(@Query("month") month: String? = null): ApiResponse<JsonObject>
}
