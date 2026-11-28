package com.qzxy.lite.data.api

import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.data.model.WalletInfo
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * 余额 / 充值（2026-11-13 ~ 2026-11-28 实测定稿）：
 * - 端点在 v3-api（默认网关）；
 * - account/wallet、user/account/money/all 均为 **GET + 公共参数**（原版同款调用）；
 * - 充值下单：settlement/weChat/recharge/app/balance 与 settlement/alipay/recharge/balance，
 *   signature 由 `WalletRepository.rechargeSignature` 按参数字典序拼接 + `JniUtils.getSk()` 后 MD5 生成。
 */
interface WalletApi {

    /** 钱包余额（GET + 公共参数；原版同款调用，见 logcat_device.txt 22:18:19）。 */
    @GET("account/wallet")
    suspend fun wallet(): ApiResponse<WalletInfo>

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
}
