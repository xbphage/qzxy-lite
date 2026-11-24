package com.qzxy.lite.data.api

import com.qzxy.lite.core.ApiConstants
import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.data.model.LoginResult
import com.qzxy.lite.data.model.UserInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 登录链（2026-11-13 实测定稿，见 notes/account-context.md 与 tools/probe_path.py 结果）：
 * - **登录链端点在 v3-api.china-qzxy.cn**（wfwapi 上同路径 404/参数错误）；
 * - ① user/verification/code/get：GET，参数 secret=MD5(手机号前3+后4+"klcx")、typeId=3、telephone、platform=1；
 * - ② user/registerAndLogin：**POST**（GET 返回 errorCode=5 请求方式错误）；
 * - ③ user/login：**POST** 密码登录（password=MD5(明文)、type=0，绕开验证码频控；凭证错误返回 errorCode=12）；
 * - ④ user/info：GET，公共参数 + Config-Project 头；
 * - v3-api 响应封装多一个 `success` 字段，message 可能叫 `errorMessage`。
 */
interface AuthApi {

    @GET("user/verification/code/get")
    suspend fun sendVerificationCode(
        @Query("secret") secret: String,
        @Query("telephone") telephone: String,
        @Query("typeId") typeId: Int = 3,
        @Query("platform") platform: Int = 1,
        @Query("phoneSystem") phoneSystem: String = "android",
        @Query("version") version: String = ApiConstants.APP_VERSION,
    ): ApiResponse<JsonObject>

    @POST("user/verification/code/get")
    suspend fun sendVerificationCodePost(@Body body: SendCodeRequest): ApiResponse<JsonObject>

    @FormUrlEncoded
    @POST("user/verification/code/get")
    suspend fun sendVerificationCodeForm(
        @Field("secret") secret: String,
        @Field("telephone") telephone: String,
        @Field("typeId") typeId: Int = 3,
        @Field("platform") platform: Int = 1,
    ): ApiResponse<JsonObject>

    @POST("user/registerAndLogin")
    suspend fun registerAndLogin(@Body body: LoginRequest): ApiResponse<LoginResult>

    @FormUrlEncoded
    @POST("user/registerAndLogin")
    suspend fun registerAndLoginForm(
        @Field("telephone") telephone: String,
        @Field("smsCode") smsCode: String,
        @Field("type") type: Int = 5,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("version") version: String = ApiConstants.APP_VERSION,
    ): ApiResponse<LoginResult>

    /** 拉取用户信息（含 accountId/userId/telPhone/projectId/loginCode）。 */
    @GET("user/info")
    suspend fun userInfo(): ApiResponse<UserInfo>

    /** 密码登录（LoginPassActivity 还原；v3-api 实测可达，凭证错误返回 errorCode=12）。 */
    @FormUrlEncoded
    @POST("user/login")
    suspend fun passwordLogin(
        @Field("telephone") telephone: String,
        @Field("password") passwordMd5: String,
        @Field("type") type: Int = 0,
        @Field("identifier") identifier: String = "",
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("version") version: String = ApiConstants.APP_VERSION,
    ): ApiResponse<LoginResult>

    /** 绑定/更换校园（换 projectId 后需重拉配置）。 */
    @POST("user/project/bind")
    suspend fun bindProject(@Body body: BindProjectRequest): ApiResponse<JsonObject>

    /**
     * 会话缓存主动续期（对标原版 v0.j().K(context) 调用的 user/login/cache/renewal）。
     * 周期性调用此接口可直接重置并延长服务端 Redis 中当前 session 的 TTL。
     */
    @FormUrlEncoded
    @POST("user/login/cache/renewal")
    suspend fun renewLoginCache(
        @Field("accountId") accountId: String,
        @Field("projectId") projectId: String,
    ): ApiResponse<JsonObject>

    /** 退出登录。 */
    @POST("user/logout")
    suspend fun logout(): ApiResponse<JsonObject>
}

@Serializable
data class SendCodeRequest(
    val secret: String,
    val telephone: String,
    val typeId: Int = 3,
    val platform: Int = 1,
)

@Serializable
data class LoginRequest(
    val telephone: String,
    val smsCode: String,
    val type: Int = 5,
    val phoneSystem: String = "android",
    val version: String = ApiConstants.APP_VERSION,
)

@Serializable
data class BindProjectRequest(val projectId: String)
