package com.qzxy.lite.data.repository

import com.qzxy.lite.core.dataOrThrow
import com.qzxy.lite.data.api.BillApi
import kotlinx.serialization.json.JsonElement

/** 账单 / 退款进度仓库（本项目退款入口当前关闭，仅查看）。 */
class BillRepository(private val billApi: BillApi) {

    /** 账户账单流水（month=YYYY-MM；billRequestType：0=消费、1=充值；data 为数组）。 */
    suspend fun accountBillList(month: String, billRequestType: String): JsonElement =
        billApi.accountBillList(month = month, billRequestType = billRequestType).dataOrThrow()

    /** 退款记录列表。 */
    suspend fun refundList(): JsonElement = billApi.refundList().dataOrThrow()
}

/** 账单条目（详细字段，来自实测响应的嵌套 DTO）。 */
data class BillItem(
    val kind: String = "",
    val orderId: String = "",
    val dealDate: String = "",
    val consumeDate: String = "",
    val uploadDate: String = "",
    val upState: String = "",
    val preDeductMoney: String = "",
    val consumeMoney: String = "",
    val consumeMoneyReal: String = "",
    val consumeMoneyGiven: String = "",
    val dealMoney: String = "",
    val typeName: String = "",
)
