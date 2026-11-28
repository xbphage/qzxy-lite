package com.qzxy.lite

import android.content.Context
import com.qzxy.lite.core.ApiConstants
import com.qzxy.lite.data.api.AuthApi
import com.qzxy.lite.data.api.BillApi
import com.qzxy.lite.data.api.CampusApi
import com.qzxy.lite.data.api.DeviceApi
import com.qzxy.lite.data.api.OrderApi
import com.qzxy.lite.data.api.WalletApi
import com.qzxy.lite.data.network.NetworkModule
import com.qzxy.lite.ble.BleScanner
import com.qzxy.lite.ble.BleWaterMeterClient
import com.qzxy.lite.data.bath.BathSessionStore
import com.qzxy.lite.data.device.SavedDeviceStore
import com.qzxy.lite.data.legal.DisclaimerStore
import com.qzxy.lite.data.repository.AuthRepository
import com.qzxy.lite.data.repository.BathRepository
import com.qzxy.lite.data.repository.BillRepository
import com.qzxy.lite.data.repository.CampusRepository
import com.qzxy.lite.data.repository.WalletRepository
import com.qzxy.lite.data.session.Session
import com.qzxy.lite.data.session.SessionStore
import com.qzxy.lite.tmall.TmallWaterDeviceStore
import com.qzxy.lite.tmall.TmallWaterRepository
import com.qzxy.lite.tmall.TmallWaterSessionStore

/** 轻量依赖容器：网络层按 SessionStore 当前值动态注入公共参数。 */
class AppContainer(context: Context) {

    val sessionStore: SessionStore = SessionStore(context)

    /** 按域名取会话：wfwapi 用密码登录签发的会话码，其余（v3-api）用验证码登录的会话。 */
    private val sessionResolver: (host: String) -> Session? = { host ->
        val base = sessionStore.current()
        if (base == null) {
            null
        } else if (host == ApiConstants.WFWAPI_HOST) {
            val wlc = sessionStore.wfwapiLoginCode.value
            if (wlc.isNullOrBlank()) base else base.copy(loginCode = wlc)
        } else {
            base
        }
    }

    private val okHttpClient = NetworkModule.okHttp(
        sessionResolver = sessionResolver,
        currentSessionToken = { sessionStore.current()?.loginCode },
        canSilentReLogin = { sessionStore.keepLoggedIn && sessionStore.savedPasswordMd5 != null },
        performSilentReLogin = { authRepository.performSilentReLoginSync() },
        debug = BuildConfig.DEBUG,
        diag = BuildConfig.DIAG_LOG,
        onSessionExpired = {
            sessionStore.clear("会话已失效（errorCode=8）：loginCode 被轮换或过期")
        },
    )

    /** 业务端点实测在 v3-api；wfwapi 不认 v3 会话（同 loginCode 返回 errorCode=8，见 logcat_login.txt）。 */
    private val v3Retrofit = NetworkModule.retrofit(okHttpClient, ApiConstants.V3_API)

    private val authApi: AuthApi = v3Retrofit.create(AuthApi::class.java)
    private val campusApi: CampusApi = v3Retrofit.create(CampusApi::class.java)
    val deviceApi: DeviceApi = v3Retrofit.create(DeviceApi::class.java)
    private val orderApi: OrderApi = v3Retrofit.create(OrderApi::class.java)
    private val walletApi: WalletApi = v3Retrofit.create(WalletApi::class.java)
    private val billApi: BillApi = v3Retrofit.create(BillApi::class.java)

    /** BLE 扫描（识别规则同原版）。 */
    val bleScanner = BleScanner(context)

    /** BLE 水表通信客户端（脱机蓝牙水表）。 */
    val bleWaterMeterClient = BleWaterMeterClient(context)

    /** 免责声明与免费须知确认状态。 */
    val disclaimerStore = DisclaimerStore(context)

    /** 已保存设备（扫描识别后落盘，首页直接显示）。 */
    val savedDeviceStore = SavedDeviceStore(context)

    /** 洗澡会话（进程级）：页面出栈/重建后仍能恢复"正在供水"，并据服务端对账纠正。 */
    val bathSessionStore = BathSessionStore()

    val authRepository = AuthRepository(authApi, sessionStore)
    val campusRepository = CampusRepository(campusApi, deviceApi)
    val bathRepository = BathRepository(orderApi, bleWaterMeterClient, sessionStore, deviceApi)
    val walletRepository = WalletRepository(walletApi, sessionStore)
    val billRepository = BillRepository(billApi)

    val tmallSessionStore = TmallWaterSessionStore(context)
    val tmallDeviceStore = TmallWaterDeviceStore(context)
    val tmallWaterRepository = TmallWaterRepository(tmallSessionStore, tmallDeviceStore)
}
