package com.qzxy.lite.data.repository

import com.klcxkj.jni.JniUtils
import com.qzxy.lite.core.dataOrThrow
import com.qzxy.lite.core.md5Hex as coreMd5Hex
import com.qzxy.lite.data.api.WalletApi
import com.qzxy.lite.data.model.WalletInfo
import com.qzxy.lite.data.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 钱包仓库：余额（含全局实时状态流，关阀结算/充值后同步更新）、充值档位/支付方式/满赠规则、充值下单。
 */
class WalletRepository(
    private val walletApi: WalletApi,
    private val sessionStore: SessionStore,
    val payLauncher: PayLauncher = PayLauncherNotIntegrated(),
) {

    private val _latestBalance = MutableStateFlow<String?>(null)
    /** 最近一次向服务端拉取到的账户余额展示值（如 `"19.81"`），供钱包页与关阀结算共用。 */
    val latestBalance: StateFlow<String?> = _latestBalance.asStateFlow()

    private fun telPhone(): String = sessionStore.current()?.telephone.orEmpty()

    /** 与验证码接口同款的 secret 算法（充值 signature 的首次试探值）。 */
    fun signature(): String {
        val phone = telPhone()
        return coreMd5Hex(phone.take(3) + phone.takeLast(4) + "klcx")
    }

    /**
     * 充值签名（sj/e0.e(Map) 还原 + 实测通过的 SK）：
     * map 按 key 字母序排列，拼接 "key"+"value"，末尾追加 SK，整体 MD5 小写 hex 后 trim。
     */
    fun rechargeSignature(loginCode: String, payWay: String, rechargeMoney: String, telPhone: String): String {
        val map = sortedMapOf(
            "loginCode" to loginCode,
            "payWay" to payWay,
            "rechargeMoney" to rechargeMoney,
            "telPhone" to telPhone,
        )
        val joined = buildString {
            for ((k, v) in map) append(k).append(v)
            append(JniUtils.getSk())
        }
        return coreMd5Hex(joined.trim())
    }

    suspend fun wallet(): WalletInfo {
        val info = walletApi.wallet().dataOrThrow()
        val display = info.display
        if (display != "--") _latestBalance.value = display
        return info
    }

    /** 静默刷新并返回最新账户余额（失败或空值返回 null，不影响主结算流程）。 */
    suspend fun refreshBalanceQuietly(): String? = runCatching {
        wallet().display.takeIf { it != "--" }
    }.getOrNull()

    suspend fun allMoney(): JsonObject = walletApi.allMoney().dataOrThrow()

    /** 充值金额档位/套餐（原始响应；data 为数组）。 */
    suspend fun rechargePackages(): JsonElement = walletApi.rechargePackages().dataOrThrow()

    /** 可用支付方式（原始响应）。 */
    suspend fun payModes(): JsonElement = walletApi.payModes().dataOrThrow()

    /** 充值满赠规则。 */
    suspend fun givenRuleCount(): JsonElement = walletApi.givenRuleCount().dataOrThrow()

    /** 充值档位解析（amount=面额元，givenMoney=赠送元；typeId 下单时传）。 */
    fun parseTiers(json: JsonElement): List<RechargeTier> {
        android.util.Log.d("QzxyWallet", "parseTiers input: $json")
        val data = (json as? kotlinx.serialization.json.JsonObject)?.get("data")
            ?: (json as? kotlinx.serialization.json.JsonArray)
            ?: return emptyList()
        val items = mutableListOf<RechargeTier>()
        fun collect(e: JsonElement) {
            when (e) {
                is kotlinx.serialization.json.JsonArray -> e.forEach { collect(it) }
                is kotlinx.serialization.json.JsonObject -> {
                    fun field(name: String): String? =
                        (e[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val amount = field("amount") ?: return
                    items.add(
                        RechargeTier(
                            typeId = field("typeId").orEmpty(),
                            amount = amount,
                            givenMoney = field("givenMoney") ?: "0",
                        )
                    )
                }
                else -> {}
            }
        }
        collect(data)
        android.util.Log.d("QzxyWallet", "parseTiers parsed: ${items.size} tiers ${items.map { it.amount }}")
        return items
    }

    /** 充值档位解析（amount=面额元，givenMoney=赠送元；typeId 下单时传）。 */
    suspend fun rechargeTiers(): List<RechargeTier> {
        val json = walletApi.rechargePackages().dataOrThrow()
        android.util.Log.d("QzxyWallet", "rechargeTiers fetch: $json")
        val tiers = parseTiers(json)
        android.util.Log.d("QzxyWallet", "rechargeTiers parsed: ${tiers.size} tiers")
        return tiers
    }

    /** 微信充值下单：返回 SDK 拉起串（官方支付 SDK 未接入前不发起实际支付）。 */
    suspend fun createWeChatRecharge(money: String): String =
        walletApi.rechargeWeChat(
            payWay = "5",
            rechargeMoney = money,
            signature = rechargeSignature(
                loginCode = sessionStore.current()?.loginCode.orEmpty(),
                payWay = "5",
                rechargeMoney = money,
                telPhone = telPhone(),
            ),
            telPhone = telPhone(),
        ).dataOrThrow()

    /** 支付宝充值下单（原版 U2() 实测 payWay=2）。 */
    suspend fun createAliPayRecharge(money: String): String =
        walletApi.rechargeAliPay(
            payWay = "2",
            rechargeMoney = money,
            signature = rechargeSignature(
                loginCode = sessionStore.current()?.loginCode.orEmpty(),
                payWay = "2",
                rechargeMoney = money,
                telPhone = telPhone(),
            ),
            telPhone = telPhone(),
        ).dataOrThrow()

    /**
     * 拉起支付宝 SDK（PayTask.payV2，**必须在子线程调用**——官方 demo 即独立线程；
     * 在主线程阻塞调用会导致 SDK 的收银台页面无法显示而卡死）。
     * resultStatus：9000=支付成功；8000=处理中；6001=用户取消；其余=失败。
     */
    suspend fun payWithAlipaySdk(activity: android.app.Activity, orderString: String): Map<String, String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.alipay.sdk.app.PayTask(activity).payV2(orderString, true)
        }
}

/** 充值渠道（仅封装官方支付渠道；不做任何绕过/代收）。 */
enum class PayChannel { WECHAT, ALIPAY }

/**
 * 官方支付拉起器：接入微信/支付宝 SDK 后由实现类完成"拉起官方支付"。
 * 协议要求：仅拉起官方支付，不实现、不模拟、不绕过任何支付流程。
 */
interface PayLauncher {
    fun launch(ticket: RechargeTicket)
}

/** SDK 未接入前的占位实现：显式失败，不伪造支付成功。 */
class PayLauncherNotIntegrated : PayLauncher {
    override fun launch(ticket: RechargeTicket) {
        throw NotImplementedError(
            "官方支付 SDK 未接入（${ticket.channel}）：仅允许拉起官方支付，接入点见 WalletRepository.payLauncher"
        )
    }
}

/** 充值下单结果：只保存拉起官方支付所需的原始参数，实际支付交给官方 SDK。 */
class RechargeTicket(val channel: PayChannel, val payload: JsonObject)

/** 充值档位（wallet/recharge/package 实测：typeId/amount/givenMoney）。 */
data class RechargeTier(
    val typeId: String,
    val amount: String,
    val givenMoney: String,
)
