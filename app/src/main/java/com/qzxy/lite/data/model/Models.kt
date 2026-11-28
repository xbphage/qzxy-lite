package com.qzxy.lite.data.model

import com.qzxy.lite.core.FlexibleStringSerializer
import com.qzxy.lite.core.YuanStringSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 领域模型（字段名/形态以 2026-11-13 真实响应为准，见 notes/samples/registerAndLogin_*.json、user_info_*.json）：
 * - id/金额是**数字**，统一用 FlexibleStringSerializer 收成 String；
 * - projectId/accountId 嵌套在 `userAccount` 子对象里（不在 data 顶层）。
 */

/** user/account 子对象（registerAndLogin 与 user/info 共用）。 */
@Serializable
data class UserAccount(
    @Serializable(with = FlexibleStringSerializer::class) val projectId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val accountId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val userId: String? = null,
    val projectName: String? = null,
    val name: String? = null,
    val gender: Int? = null,
    /** 余额：整数=0.001 元（683 → "0.683"），字符串已是元；统一归一成元字符串。 */
    @Serializable(with = YuanStringSerializer::class) val accountRealMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val accountGivenMoney: String? = null,
)

/** user/info 返回（含会话上下文字段，与公共参数同名）。 */
@Serializable
data class UserInfo(
    @Serializable(with = FlexibleStringSerializer::class) val userId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val accountId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val projectId: String? = null,
    val telephone: String? = null,
    val telPhone: String? = null,
    val loginCode: String? = null,
    val name: String? = null,
    val nickname: String? = null,
    val face: String? = null,
    val userAccount: UserAccount? = null,
) {
    val displayName: String get() = name ?: nickname ?: telephone ?: telPhone ?: "用户"
}

/** registerAndLogin 返回：id 在 userAccount 子对象中（数字形态）。 */
@Serializable
data class LoginResult(
    val loginCode: String? = null,
    val telephone: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val userId: String? = null,
    val userAccount: UserAccount? = null,
    val user: UserInfo? = null,
    val userInfo: UserInfo? = null,
) {
    fun merged(): UserInfo {
        val sub = user ?: userInfo
        return UserInfo(
            userId = userId ?: userAccount?.userId ?: sub?.userId,
            accountId = userAccount?.accountId ?: sub?.accountId,
            projectId = userAccount?.projectId ?: sub?.projectId,
            telephone = telephone ?: sub?.telephone,
            telPhone = sub?.telPhone,
            loginCode = loginCode ?: sub?.loginCode,
            name = userAccount?.name ?: sub?.name,
            nickname = sub?.nickname,
            face = sub?.face,
            userAccount = userAccount,
        )
    }
}

/** 区域/楼栋层级节点（device/area/list/new）。 */
@Serializable
data class AreaItem(
    val areaId: String? = null,
    val areaName: String? = null,
    val buildingId: String? = null,
    val buildingName: String? = null,
    val floorId: String? = null,
    val floorName: String? = null,
) {
    val label: String get() = areaName ?: buildingName ?: floorName ?: "未命名区域"
    val id: String? get() = areaId ?: buildingId ?: floorId
}

/** 浴室/热水器设备（device/bath/list/area、device/info/mac）。字段形态未全核实，一律弹性解析。 */
@Serializable
data class BathDevice(
    /** 后端下发的正式显示名（如"热水表-2栋-2单元-6层-2-2-625"），见 notes/蓝牙设备名识别.md §6。 */
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("DevName") val devName: String? = null,
    /** 云端登记的设备 ID（与水表蓝牙 0x23 响应中的内部物理 ID 不同，下单签名必须传此 DevID）。 */
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("DevID") val devId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val deviceId: String? = null,
    /** 设备大类（浴室水表通常为 4，饮水机为 5，洗衣机为 6，吹风机为 7）。 */
    @Serializable(with = FlexibleStringSerializer::class) val bigTypeId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("Dsbtypeid") val dsbTypeIdCap: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val dsbtypeid: String? = null,
    /** 设备小类（通常为 1）。 */
    @Serializable(with = FlexibleStringSerializer::class) val smallTypeId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("DevTypeID") val devTypeIdCap: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val devTypeId: String? = null,
    /** 项目/学校 ID。 */
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("PrjID") val prjId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val projectId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val snCode: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val deviceSncode: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val macAddress: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val devMac: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val realMac: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val deviceName: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val bathroomName: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val areaName: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val deviceType: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val deviceModel: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val devTypeName: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val status: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val usingStatus: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val isBle: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val isOnline: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val communicationTypeId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val onlineStatusId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val bindDeviceSnCode: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val keyNo: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val keyType: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val withholdMoney: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val deductionMethod: String? = null,
    /** BLE 扫描时的信号强度（dBm），仅本地保存的设备携带。 */
    val signal: Int? = null,
) {
    val label: String
        get() = devName ?: deviceName ?: bathroomName ?: macAddress ?: deviceSncode ?: snCode ?: "未命名设备"
    /** 设备 SN：接口参数同时出现 snCode 与 deviceSncode，联调时以实际响应补齐映射（TODO(probe)）。 */
    val sn: String? get() = deviceSncode ?: snCode
    val isBusy: Boolean get() = usingStatus == "1" || status == "1"
    val cloudDeviceId: String? get() = devId?.takeIf { it.isNotBlank() } ?: deviceId?.takeIf { it.isNotBlank() }
    val resolvedBigTypeId: String?
        get() = listOf(bigTypeId, dsbTypeIdCap, dsbtypeid).firstOrNull { !it.isNullOrBlank() }
    val resolvedSmallTypeId: String?
        get() = listOf(smallTypeId, devTypeIdCap, devTypeId).firstOrNull { !it.isNullOrBlank() }
    /** 纯蓝牙脱机设备（无在线 TCP 通道，communicationTypeId=="0" 且 onlineStatusId!="1"）。 */
    val isOfflineBleOnly: Boolean
        get() = (communicationTypeId == "0" || isBle == "1") && onlineStatusId != "1" && isOnline != "1"
}

/** 余额（account/wallet）。字段名候选并存；金额统一归一成元字符串（整数=0.001 元，字符串=元）。 */
@Serializable
data class WalletInfo(
    @Serializable(with = YuanStringSerializer::class) val money: String? = null,
    @Serializable(with = YuanStringSerializer::class) val balance: String? = null,
    @Serializable(with = YuanStringSerializer::class) val allMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val walletMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val accountMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val accountRealMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val accountGivenMoney: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val accountRealMoneyStr: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val accountGivenMoneyStr: String? = null,
) {
    val display: String
        get() {
            val direct = money ?: balance ?: walletMoney ?: allMoney
            if (!direct.isNullOrBlank()) return direct
            val real = (accountMoney ?: accountRealMoney ?: accountRealMoneyStr)?.toDoubleOrNull()
            val given = (accountGivenMoney ?: accountGivenMoneyStr)?.toDoubleOrNull()
            if (real != null || given != null) {
                return com.qzxy.lite.core.formatYuan((real ?: 0.0) + (given ?: 0.0))
            }
            return "--"
        }
}

/** 预估消费（order/perMoney）。 */
@Serializable
data class MoneyEstimate(
    @Serializable(with = FlexibleStringSerializer::class) val money: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val perMoney: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val price: String? = null,
    val unit: String? = null,
) {
    val display: String get() = perMoney ?: money ?: price ?: "--"
}

/** 下单/关阀指令结果（字段形态实测：orderNo 数字、state 数字，均弹性解析）。 */
@Serializable
data class OrderResult(
    @Serializable(with = FlexibleStringSerializer::class) val orderNo: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val orderId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val status: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val state: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val result: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val msg: String? = null,
)

/** 支付方式（settlement/recharge/pay/mode）。 */
@Serializable
data class PayMode(
    val payModeId: String? = null,
    val payModeName: String? = null,
    val channelType: String? = null,
    val iconUrl: String? = null,
) {
    val label: String get() = payModeName ?: channelType ?: payModeId ?: "支付方式"
}

/** 蓝牙下发费率开阀响应（order/downRate/bluetooth/rateOrder）。 */
@Serializable
data class BluetoothDownRateData(
    @Serializable(with = FlexibleStringSerializer::class) val orderNo: String? = null,
    val downData: String? = null,
    @Serializable(with = YuanStringSerializer::class) val perMoney: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val accountId: String? = null,
    @Serializable(with = FlexibleStringSerializer::class) val consumeDate: String? = null,
    @Serializable(with = YuanStringSerializer::class) val minMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val realMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val givenMoney: String? = null,
    val autoDisConTime: Long? = null,
)

/**
 * 蓝牙上传消费记录响应（order/upload/bluetooth/data）。
 * 官方接口实测与消费账单同源：数字字段单位为 0.001 元，字符串字段单位为元，
 * 统一通过 [YuanStringSerializer] 归一，避免关阀结算消费/余额放大 1000 倍。
 */
@Serializable
data class BluetoothUploadData(
    @Serializable(with = FlexibleStringSerializer::class) val orderNo: String? = null,
    @Serializable(with = YuanStringSerializer::class) val consumeMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val upMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val consumeMoneyReal: String? = null,
    @Serializable(with = YuanStringSerializer::class) val consumeMoneyGiven: String? = null,
    @Serializable(with = YuanStringSerializer::class) val upLeadMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val perMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val balance: String? = null,
    @Serializable(with = YuanStringSerializer::class) val money: String? = null,
    @Serializable(with = YuanStringSerializer::class) val realMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val accountRealMoney: String? = null,
    @Serializable(with = YuanStringSerializer::class) val accountGivenMoney: String? = null,
) {
    /** 本次实际消费金额（元）：兼容 consumeMoney / upMoney / consumeMoneyReal。 */
    val resolvedConsumeMoney: String?
        get() = listOf(consumeMoney, upMoney, consumeMoneyReal)
            .firstOrNull { !it.isNullOrBlank() }

    /** 接口直接附带的剩余余额（元）；无该字段时由上层调 `account/wallet` 刷新。 */
    val resolvedBalance: String?
        get() {
            val direct = listOf(balance, money, realMoney).firstOrNull { !it.isNullOrBlank() }
            if (direct != null) return direct
            val real = accountRealMoney?.toDoubleOrNull()
            val given = accountGivenMoney?.toDoubleOrNull()
            return if (real != null || given != null) {
                com.qzxy.lite.core.formatYuan((real ?: 0.0) + (given ?: 0.0))
            } else {
                null
            }
        }
}

