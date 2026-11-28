package com.qzxy.lite.tmall

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import kotlin.math.abs

/** 一张饮水机二维码解析结果。 */
data class ScannedWaterQr(
    val raw: String,
    /** 二维码编号（如 `tmxy869451084947543` 或纯数字资源号 `630102040`）。 */
    val code: String,
) {
    val valid: Boolean get() = code.isNotBlank()
}

/** 饮水机详情（来自 `mtop.tmall.campus.share.applet.general.device.info`）。 */
data class TmallWaterDevice(
    val query: String,
    /** 水机资源号 `deviceCode`，`water.start` 下单传此字段作为 `deviceId`。 */
    val resNo: String,
    val deviceId: String,
    val name: String,
    val address: String,
    val building: String,
    val campus: String,
    val online: Boolean,
    val canUse: Boolean,
    val inBusinessTime: Boolean,
    val runningStatus: String,
    val faultText: String,
    val campusAreaId: String = "",
    val merchantId: String = "",
    val relationshipId: String = "",
    val businessType: String = "",
) {
    val unlockable: Boolean get() = canUse && online && inBusinessTime && faultText.isBlank()
}

/** 天猫校园水卡 / 钱包账户（金额单位：分）。 */
data class TmallWalletAccount(
    val fundAccreditId: String,
    val fundAccountId: String = "",
    val fundName: String,
    val usableFen: Long,
    val freezeFen: Long = 0L,
    val statusName: String = "",
    val relationShipId: String = "",
    val fundPlatform: String = "",
    val campusAreaCode: String = "",
    val subjectCode: String = "",
    val canRecharge: Boolean = true,
    val activityName: String = "",
) {
    val usableDisplay: String get() = formatFenToYuan(usableFen)
    val freezeDisplay: String get() = formatFenToYuan(freezeFen)
    val isWithdrawingFrozen: Boolean get() = statusName.equals("FROZEN", ignoreCase = true)
}

/** 天猫校园水卡单条消费/账单流水。 */
data class TmallWalletRecord(
    val amountDisplay: String,
    val sourceInfo: String,
    val outOrderId: String,
    val createTimeStr: String,
    val remark: String = "",
)

/** 天猫校园钱包聚合信息（账户列表 + 选中账户消费流水 + 免费/配额活动名）。 */
data class TmallWalletOverview(
    val accounts: List<TmallWalletAccount>,
    val records: List<TmallWalletRecord> = emptyList(),
    val freeActivityName: String = "",
)

internal fun formatFenToYuan(fen: Long): String {
    val value = abs(fen)
    val yuan = value / 100
    val remainder = (value % 100).toString().padStart(2, '0')
    return if (fen < 0) "-$yuan.$remainder" else "$yuan.$remainder"
}

/** 解锁结果（来自 `mtop.tmall.campus.share.applet.device.water.start`）。 */
data class WaterStartResult(
    val isvOrderId: String,
    val serviceInfoId: String,
    val raw: String,
)

/** 校园身份检查结果。 */
data class CampusProbeResult(
    val ok: Boolean,
    val code: String,
    val message: String,
)

/**
 * 天猫校园饮水业务 API 封装（仅保留饮水相关接口，不含门锁）。
 */
class TmallWaterApi(private val client: TmallMtopClient) {

    fun deviceInfo(query: String): TmallWaterDevice {
        val inner = TmallJson.stringify(
            mapOf(
                "isv" to ISV,
                "businessType" to BUSINESS_TYPE,
                "resNo" to query,
                "paymentChannel" to PAYMENT_CHANNEL,
            ),
        )
        val body = client.call(
            API_DEVICE_INFO,
            mapOf("requestType" to "DEVICE_INFO_GET", "requestJson" to inner),
        )
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.asObjOrNull()?.get("data")?.asObjOrNull()
            ?: throw TmallMtopError(listOf("EMPTY::设备信息为空"))
        val d = data["deviceResponse"]?.asObjOrNull()
            ?: throw TmallMtopError(listOf("EMPTY::设备信息结构异常（缺少 deviceResponse）"))
        val biz = data["bizTimeConfig"]?.asObjOrNull()

        val faultStatus = d.str("faultStatus")
        val faultText = d.str("faultInfo").ifBlank {
            if (faultStatus.isBlank() || faultStatus == "WELL_FUNCTIONING") "" else d.str("errorMsg")
        }

        val attr = data["deviceBusinessAttributeDTO"]?.asObjOrNull()
        return TmallWaterDevice(
            query = query,
            resNo = d.str("deviceCode").ifBlank { query },
            deviceId = d.str("deviceId"),
            name = d.str("deviceName").ifBlank { "饮水机" },
            address = d.str("address"),
            building = d.str("buildingName"),
            campus = d.str("campusAreaName"),
            online = d.str("onlineStatus").uppercase() != "OFFLINE",
            canUse = d.bool("deviceCanUse", true),
            inBusinessTime = biz?.bool("isInBusinessTime", true) ?: true,
            runningStatus = d.str("runningStatus"),
            faultText = faultText,
            campusAreaId = d.str("campusAreaId"),
            merchantId = d.str("merchantId"),
            relationshipId = d.str("relationshipId"),
            businessType = attr?.str("businessType").orEmpty(),
        )
    }

    /** 换取/刷新校园用户会话（`mtop.tmall.campus.member.user.login`）；返回 `null` 表示调用成功。 */
    fun confirmCampusUser(): String? = try {
        client.call(API_CAMPUS_LOGIN, emptyMap())
        null
    } catch (e: TmallMtopError) {
        "${e.code}：${e.human()}"
    }

    /**
     * 通用校园会员档案探测（不绑定任何特定学校/楼栋常量）：
     * `mtop.tmall.campus.member.app.user.get` 成功即说明携带有效校园身份；
     * 若 `GET_CAMPUS_USER_ERROR` 重试一次区分瞬时抽风与未绑定校园档案。
     */
    fun probeCampusUser(): CampusProbeResult {
        var last: CampusProbeResult? = null
        repeat(2) { attempt ->
            try {
                client.call(API_CAMPUS_USER_GET, emptyMap())
                return CampusProbeResult(true, "SUCCESS", "校园身份正常")
            } catch (e: TmallMtopError) {
                last = when (e.code) {
                    "GET_CAMPUS_USER_ERROR" -> CampusProbeResult(
                        false,
                        e.code,
                        "当前账号暂未激活天猫校园饮水档案（${e.detail.ifBlank { e.code }}）。\n" +
                            "请确认登录的是开通了天猫校园的账号，或用淘宝网页版访问一次校园服务后重试。",
                    )
                    "FAIL_SYS_SESSION_EXPIRED" -> CampusProbeResult(false, e.code, "登录态已过期，请重新登录")
                    else -> CampusProbeResult(false, e.code, e.human())
                }
                if (e.code != "GET_CAMPUS_USER_ERROR" || attempt == 1) return last!!
            }
        }
        return last ?: CampusProbeResult(false, "UNKNOWN", "校园身份校验失败")
    }

    /**
     * 查询当前天猫校园账号下的水卡/洗护钱包账户列表（单位：分，只读）。
     */
    fun fundAccountList(
        businessType: String = BUSINESS_TYPE,
        campusAreaCode: String = "",
    ): List<TmallWalletAccount> {
        val inner = TmallJson.stringify(
            mapOf(
                "businessType" to businessType,
                "campusAreaCode" to campusAreaCode,
            ),
        )
        val body = client.call(
            API_FUND_ACCOUNT_LIST,
            mapOf("requestType" to "FUND_ACCREDIT_ACCOUNT_LIST", "requestJson" to inner),
        )
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["data"]?.asObjOrNull()?.get("response") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            (el as? JsonObject)?.let { parseFundAccountItem(it) }
        }
    }

    /**
     * 按设备上下文查询单台饮水机关联的校园水卡余额及免费/配额活动状态（只读）。
     */
    fun fundDeviceInfo(device: TmallWaterDevice): Pair<TmallWalletAccount?, String> {
        if (device.campusAreaId.isBlank() ||
            device.merchantId.isBlank() ||
            device.relationshipId.isBlank() ||
            device.deviceId.isBlank()
        ) {
            return null to ""
        }
        val body = client.call(
            API_FUND_DEVICE_INFO,
            mapOf(
                "campusAreaCode" to (device.campusAreaId.toLongOrNull() ?: device.campusAreaId),
                "businessTypeEnum" to device.businessType.ifBlank { BUSINESS_TYPE },
                "payeeId" to (device.merchantId.toLongOrNull() ?: device.merchantId),
                "relationShipId" to (device.relationshipId.toLongOrNull() ?: device.relationshipId),
                "deviceId" to device.deviceId,
                "paymentChannel" to PAYMENT_CHANNEL,
            ),
        )
        val data = json.parseToJsonElement(body).jsonObject["data"]?.asObjOrNull()
            ?: return null to ""
        val activity = data["waterActivity"]?.asObjOrNull()
        val activityName = if (activity?.bool("hasActivity", false) == true) {
            activity.str("name")
        } else {
            ""
        }
        val resp = data["fundAccreditAndAccountGetResponse"]?.asObjOrNull()
            ?: return null to activityName
        val account = parseFundAccreditBlock(
            block = resp,
            fallbackRelationShipId = device.relationshipId,
            fallbackCampusArea = device.campusAreaId,
            fallbackFreezeFen = data.longVal("freezeAmount"),
            activityName = activityName,
        )
        return account to activityName
    }

    /**
     * 查询指定钱包账户详情与近期消费流水（`FUND_ACCREDIT_ACCOUNT_ORDER_LIST`，只读）。
     */
    fun fundAccountHomePage(
        account: TmallWalletAccount,
        fundOrderType: String = "CONSUME",
        pageNum: Int = 1,
        pageSize: Int = 10,
    ): Pair<TmallWalletAccount?, List<TmallWalletRecord>> {
        val inner = TmallJson.stringify(
            mapOf(
                "businessType" to BUSINESS_TYPE,
                "campusAreaCode" to "",
                "fundOrderType" to fundOrderType,
                "pageNum" to pageNum.toString(),
                "pageSize" to pageSize.toString(),
                "relationShipId" to account.relationShipId,
                "fundAccreditId" to account.fundAccreditId,
                "fundPlatform" to account.fundPlatform,
            ),
        )
        val body = client.call(
            API_FUND_ACCOUNT_HOMEPAGE,
            mapOf("requestType" to "FUND_ACCREDIT_ACCOUNT_ORDER_LIST", "requestJson" to inner),
        )
        val resp = json.parseToJsonElement(body).jsonObject["data"]
            ?.asObjOrNull()
            ?.get("response")
            ?.asObjOrNull()
            ?: return null to emptyList()

        val refreshedAccount = resp["accountResponse"]?.asObjOrNull()?.let { acctObj ->
            parseFundAccountItem(acctObj, defaultAccount = account)
        }
        val recordsArr = resp["orderResponse"]?.asObjOrNull()?.get("data") as? JsonArray
        val records = recordsArr?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val amount = obj.str("totalAmountStr")
            val source = obj.str("sourceInfo")
            val outOrderId = obj.str("outOrderId")
            val time = obj.str("createTimeStr")
            val remark = obj["attr"]?.asObjOrNull()?.str("remark").orEmpty()
            if (amount.isBlank() && source.isBlank() && time.isBlank()) return@mapNotNull null
            TmallWalletRecord(
                amountDisplay = amount.ifBlank { "--" },
                sourceInfo = source.ifBlank { "饮水/洗护消费" },
                outOrderId = outOrderId,
                createTimeStr = time,
                remark = remark,
            )
        }.orEmpty()
        return refreshedAccount to records
    }

    /** 解锁饮水机（`mtop.tmall.campus.share.applet.device.water.start`）。 */
    fun start(resNo: String, source: String = "FAST_START"): WaterStartResult {
        val body = client.call(
            API_WATER_START,
            mapOf(
                "deviceId" to resNo,
                "paymentChannel" to PAYMENT_CHANNEL,
                "deviceConsumeSourceStr" to source,
            ),
        )
        val data = runCatching {
            json.parseToJsonElement(body).jsonObject["data"]?.asObjOrNull()
        }.getOrNull()
        return WaterStartResult(
            isvOrderId = data?.str("isvOrderId").orEmpty(),
            serviceInfoId = data?.str("shareServiceInfoId").orEmpty(),
            raw = body,
        )
    }

    /** 手动结束接水并结单（`mtop.tmall.campus.share.applet.device.water.stop`）。 */
    fun stop(resNo: String, isvOrderId: String = ""): String {
        val data = mutableMapOf<String, Any?>(
            "deviceId" to resNo,
            "paymentChannel" to PAYMENT_CHANNEL,
        )
        if (isvOrderId.isNotBlank()) data["isvOrderId"] = isvOrderId
        return client.call(API_WATER_STOP, data)
    }

    companion object {
        const val ISV = "CAMPUS"
        const val BUSINESS_TYPE = "WASH_AND_CARE"
        const val PAYMENT_CHANNEL = "TMXY_APP"

        const val API_DEVICE_INFO = "mtop.tmall.campus.share.applet.general.device.info"
        const val API_WATER_START = "mtop.tmall.campus.share.applet.device.water.start"
        const val API_WATER_STOP = "mtop.tmall.campus.share.applet.device.water.stop"
        const val API_CAMPUS_LOGIN = "mtop.tmall.campus.member.user.login"
        const val API_CAMPUS_USER_GET = "mtop.tmall.campus.member.app.user.get"
        const val API_FUND_ACCOUNT_LIST = "mtop.tmall.campus.share.fund.accredit.account.list"
        const val API_FUND_ACCOUNT_HOMEPAGE = "mtop.tmall.campus.share.fund.accredit.account.homePage"
        const val API_FUND_DEVICE_INFO = "mtop.tmall.campus.share.app.fund.use.device.info.get"

        private val json = Json { ignoreUnknownKeys = true }

        private fun parseFundAccountItem(
            obj: JsonObject,
            defaultAccount: TmallWalletAccount? = null,
        ): TmallWalletAccount {
            val fa = obj["fundAccount"]?.asObjOrNull()
            val accreditId = obj.str("id")
                .ifBlank { obj.str("fundAccreditId") }
                .ifBlank { fa?.str("fundAccreditId").orEmpty() }
                .ifBlank { defaultAccount?.fundAccreditId.orEmpty() }
            val freeze = fa?.longVal("totalFreezeAmount")
                ?: obj.longVal("freezeAmount").takeIf { it > 0L }
                ?: defaultAccount?.freezeFen
                ?: 0L
            return TmallWalletAccount(
                fundAccreditId = accreditId,
                fundAccountId = fa?.str("fundAccountId").orEmpty().ifBlank { defaultAccount?.fundAccountId.orEmpty() },
                fundName = obj.str("fundName").ifBlank { defaultAccount?.fundName ?: "天猫校园水卡" },
                usableFen = fa?.longVal("totalUsableAmount") ?: defaultAccount?.usableFen ?: 0L,
                freezeFen = freeze,
                statusName = fa?.str("statusName").orEmpty().ifBlank { defaultAccount?.statusName.orEmpty() },
                relationShipId = obj.str("relationShipId").ifBlank { defaultAccount?.relationShipId.orEmpty() },
                fundPlatform = obj.str("fundPlatform").ifBlank { defaultAccount?.fundPlatform.orEmpty() },
                campusAreaCode = obj.str("campusAreaCode").ifBlank { defaultAccount?.campusAreaCode.orEmpty() },
                subjectCode = obj.str("subjectCode").ifBlank { defaultAccount?.subjectCode.orEmpty() },
                canRecharge = obj.bool("canRecharge", defaultAccount?.canRecharge ?: true),
                activityName = defaultAccount?.activityName.orEmpty(),
            )
        }

        private fun parseFundAccreditBlock(
            block: JsonObject,
            fallbackRelationShipId: String,
            fallbackCampusArea: String,
            fallbackFreezeFen: Long,
            activityName: String,
        ): TmallWalletAccount? {
            val fa = block["fundAccount"]?.asObjOrNull() ?: return null
            return TmallWalletAccount(
                fundAccreditId = fa.str("fundAccreditId").ifBlank { block.str("id") },
                fundAccountId = fa.str("fundAccountId"),
                fundName = block.str("fundName").ifBlank { "天猫校园水卡" },
                usableFen = fa.longVal("totalUsableAmount"),
                freezeFen = fa.longVal("totalFreezeAmount").takeIf { it > 0L } ?: fallbackFreezeFen,
                statusName = fa.str("statusName"),
                relationShipId = block.str("relationShipId").ifBlank { fallbackRelationShipId },
                fundPlatform = block.str("fundPlatform"),
                campusAreaCode = block.str("campusAreaCode").ifBlank { fallbackCampusArea },
                subjectCode = fa.str("subjectCode").ifBlank { block.str("subjectCode") },
                canRecharge = block.bool("canRecharge", true),
                activityName = activityName,
            )
        }

        private fun kotlinx.serialization.json.JsonElement.asObjOrNull(): JsonObject? =
            this as? JsonObject

        private fun JsonObject.str(key: String): String {
            val el = get(key) ?: return ""
            if (el is JsonNull) return ""
            return (el as? JsonPrimitive)?.content.orEmpty()
        }

        private fun JsonObject.bool(key: String, default: Boolean): Boolean {
            val el = get(key) as? JsonPrimitive ?: return default
            return el.booleanOrNull ?: default
        }

        private fun JsonObject.longVal(key: String): Long {
            val el = get(key) as? JsonPrimitive ?: return 0L
            return el.longOrNull ?: el.content.trim().toLongOrNull() ?: 0L
        }
    }
}

/**
 * 饮水机二维码解析器。
 * 兼容：
 * - `https://share.confong.cn/xh?id=tmxy869451084947543`
 * - 含 `?id=...` 或 `?deviceCode=...` 的任意 URL
 * - 裸 `tmxy<10..20位数字>`
 * - 裸 `<9..20位数字>` 资源号
 */
object TmallQrCodec {

    private val RE_TMXY = Regex("""tmxy(\d{10,20})""")
    private val RE_BARE = Regex("""^\s*(\d{9,20})\s*$""")

    fun parse(text: String): ScannedWaterQr? {
        val raw = text.trim()
        if (raw.isEmpty()) return null

        val fromUrl = runCatching {
            val q = raw.substringAfter('?', "")
            if (q.isEmpty()) {
                ""
            } else {
                q.split('&').firstNotNullOfOrNull { pair ->
                    val k = pair.substringBefore('=')
                    if (k == "id" || k == "deviceCode") {
                        URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                    } else {
                        null
                    }
                }.orEmpty()
            }
        }.getOrDefault("")

        val code = when {
            fromUrl.isNotBlank() && (RE_TMXY.matches(fromUrl) || RE_BARE.matches(fromUrl)) -> fromUrl
            RE_TMXY.containsMatchIn(raw) -> "tmxy" + RE_TMXY.find(raw)!!.groupValues[1]
            fromUrl.isNotBlank() -> fromUrl
            RE_BARE.matches(raw) -> RE_BARE.find(raw)!!.groupValues[1]
            else -> return null
        }
        return ScannedWaterQr(raw = raw, code = code)
    }
}
