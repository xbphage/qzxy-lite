package com.qzxy.lite.data.api

import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.data.model.BluetoothDownRateData
import com.qzxy.lite.data.model.BluetoothUploadData
import com.qzxy.lite.data.model.MoneyEstimate
import com.qzxy.lite.data.model.OrderResult
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 开热水器/关阀订单（2026-11-13 用原版 App 实测抓包定稿，见 notes/samples/logcat_device.txt）：
 * 全部在 v3-api，全部 **POST 表单**；开阀流程＝
 *   ① order/tcpDevice/query/rateOrder/using（防重复，可选）
 *   ② order/tcpDevice/downRate/rateOrder {xfModel=0, snCode}（下单开阀）
 *   ③ order/tcpDevice/query/downRateResult {xfModel=0, snCode}（轮询开机结果）
 *   ④ order/tcpDevice/closeOrder {snCode, orderNo}（关阀，orderNo 来自②的响应）
 *   ⑤ order/tcpDevice/closeOrder/result/query {snCode, orderNo}（关阀结果）
 *   ⑥ order/consumeOrder/result/query {orderNo}（消费/账单）
 * snCode = 设备 MAC 去冒号大写（如 C47F0ED9E84A）。
 *
 * 脱机蓝牙水表流程：
 *   ① order/downRate/bluetooth/rateOrder 获得 downData 与 orderNo
 *   ② 手机 BLE 写入水表开阀
 *   ③ 手机 BLE 发送关阀指令与采集账单指令
 *   ④ order/upload/bluetooth/data 上传水表消费数据并完成扣费
 */
interface OrderApi {

    /** 当前使用中订单（开阀前防重复）。 */
    @FormUrlEncoded
    @POST("order/tcpDevice/query/rateOrder/using")
    suspend fun usingRateOrder(
        @Field("xfModel") xfModel: String,
        @Field("snCode") snCode: String,
        /** 设备标识第二字段（原版同发，见 notes/设备流程.md:78）。 */
        @Field("deviceSncode") deviceSncode: String = "",
    ): ApiResponse<JsonObject>

    /** 开阀下单（按量/时计费，xfModel 实测 "0"）。原版同发 snCode + deviceSncode。 */
    @FormUrlEncoded
    @POST("order/tcpDevice/downRate/rateOrder")
    suspend fun tcpRateOrder(
        @Field("xfModel") xfModel: String,
        @Field("snCode") snCode: String,
        @Field("deviceSncode") deviceSncode: String = "",
    ): ApiResponse<OrderResult>

    /** 开阀结果轮询。 */
    @FormUrlEncoded
    @POST("order/tcpDevice/query/downRateResult")
    suspend fun tcpDownRateResult(
        @Field("xfModel") xfModel: String,
        @Field("snCode") snCode: String,
        @Field("deviceSncode") deviceSncode: String = "",
    ): ApiResponse<OrderResult>

    /** 关阀（orderNo 来自开阀响应）。 */
    @FormUrlEncoded
    @POST("order/tcpDevice/closeOrder")
    suspend fun tcpCloseOrder(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
    ): ApiResponse<OrderResult>

    /** 关阀结果轮询。 */
    @FormUrlEncoded
    @POST("order/tcpDevice/closeOrder/result/query")
    suspend fun closeOrderResultQuery(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
    ): ApiResponse<OrderResult>

    /** 消费结果/账单查询。 */
    @FormUrlEncoded
    @POST("order/consumeOrder/result/query")
    suspend fun consumeResult(
        @Field("orderNo") orderNo: String,
    ): ApiResponse<JsonObject>

    /** 预估消费（原版开阀流程未调用，保留备用；方法未实测）。 */
    @GET("order/perMoney")
    suspend fun perMoney(
        @Query("snCode") snCode: String? = null,
        @Query("deviceSncode") deviceSncode: String? = null,
        @Query("xfModel") xfModel: String = "0",
    ): ApiResponse<MoneyEstimate>

    /** 蓝牙脱机水表开阀下单（获取下发给水表的 downData 指令）。 */
    @FormUrlEncoded
    @POST("order/downRate/bluetooth/rateOrder")
    suspend fun bluetoothRateOrder(
        @Field("deviceId") deviceId: String,
        @Field("macAddress") macAddress: String,
        @Field("macType") macType: String,
        @Field("bigTypeId") bigTypeId: String,
        @Field("smallTypeId") smallTypeId: String,
        @Field("protocolType") protocolType: String,
        @Field("randomNumber") randomNumber: String,
        @Field("signature") signature: String,
        @Field("xfModel") xfModel: String = "0",
        @Field("couponId") couponId: String? = null,
    ): ApiResponse<BluetoothDownRateData>

    /** 离线网关备用端点：蓝牙脱机水表开阀下单。 */
    @FormUrlEncoded
    @POST("https://wfwoff.china-qzxy.cn/offlineOrder/downRate/bluetooth/rateOrder")
    suspend fun offlineBluetoothRateOrder(
        @Field("deviceId") deviceId: String,
        @Field("macAddress") macAddress: String,
        @Field("macType") macType: String,
        @Field("bigTypeId") bigTypeId: String,
        @Field("smallTypeId") smallTypeId: String,
        @Field("protocolType") protocolType: String,
        @Field("randomNumber") randomNumber: String,
        @Field("signature") signature: String,
        @Field("xfModel") xfModel: String = "0",
        @Field("couponId") couponId: String? = null,
    ): ApiResponse<BluetoothDownRateData>

    /** 蓝牙脱机水表消费数据上传结算。 */
    @FormUrlEncoded
    @POST("order/upload/bluetooth/data")
    suspend fun bluetoothUploadData(
        @Field("protocolType") protocolType: String,
        @Field("randomNumber") randomNumber: String,
        @Field("xfData") xfData: String,
        @Field("signature") signature: String,
    ): ApiResponse<BluetoothUploadData>

    /** 离线网关备用端点：蓝牙脱机水表消费数据上传结算。 */
    @FormUrlEncoded
    @POST("https://wfwoff.china-qzxy.cn/offlineOrder/upload/bluetooth/data")
    suspend fun offlineBluetoothUploadData(
        @Field("protocolType") protocolType: String,
        @Field("randomNumber") randomNumber: String,
        @Field("xfData") xfData: String,
        @Field("signature") signature: String,
    ): ApiResponse<BluetoothUploadData>

    /** 蓝牙水表开阀失败上报。 */
    @FormUrlEncoded
    @POST("order/upload/bluetooth/fail")
    suspend fun bluetoothUploadFail(
        @Field("orderNo") orderNo: String,
        @Field("reason") reason: String? = null,
    ): ApiResponse<JsonObject>

    /** 浴室订单关闭状态同步。 */
    @FormUrlEncoded
    @POST("order/bathroom/order/closed")
    suspend fun bathroomOrderClosed(
        @Field("orderNo") orderNo: String,
    ): ApiResponse<JsonObject>
}

