package com.qzxy.lite.ui.bill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qzxy.lite.core.asYuanOrNull
import com.qzxy.lite.core.formatYuan
import com.qzxy.lite.data.repository.BillItem
import com.qzxy.lite.data.repository.BillRepository
import com.qzxy.lite.ui.common.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

data class BillDetailUiState(
    val month: String = LocalDate.now().toString().substring(0, 7),
    /** billRequestType：0=消费、1=充值。 */
    val type: String = "0",
    val bills: List<BillItem> = emptyList(),
    val selected: BillItem? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** 账单明细页：顶部月份 + 类型（消费/充值）筛选，下方账单详细列表，点卡片看全部字段。 */
class BillDetailViewModel(private val bill: BillRepository) : ViewModel() {

    private val _state = MutableStateFlow(BillDetailUiState())
    val state: StateFlow<BillDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val month = _state.value.month
        val type = _state.value.type
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { bill.accountBillList(month, type) }
                .onSuccess { el -> _state.update { it.copy(bills = extractBills(el)) } }
                .onFailure { t ->
                    _state.update { it.copy(bills = emptyList(), error = t.toUserMessage()) }
                }
            _state.update { it.copy(loading = false) }
        }
    }

    /** 选定年月（YYYY-MM）并重查（下拉选择 / 翻月按钮共用）。 */
    fun setMonth(v: String) {
        _state.update { it.copy(month = v) }
        load()
    }

    /** 相邻月切换：-1 = 上一个月，+1 = 下一个月。 */
    fun shiftMonth(delta: Int) {
        val next = runCatching {
            LocalDate.parse("${_state.value.month}-01").plusMonths(delta.toLong())
        }.getOrElse { return }
        setMonth(next.toString().substring(0, 7))
    }

    /** 切换类型（0=消费 / 1=充值）并重查。 */
    fun onTypeChange(type: String) {
        _state.update { it.copy(type = type) }
        load()
    }

    fun select(item: BillItem?) = _state.update { it.copy(selected = item) }

    /** 从账单响应提取详细账单条目（嵌套 consumeBillDTO / rechargeBillDTO）。 */
    private fun extractBills(el: JsonElement): List<BillItem> {
        val items = mutableListOf<JsonObject>()
        fun collect(e: JsonElement) {
            when (e) {
                is kotlinx.serialization.json.JsonArray -> e.forEach { collect(it) }
                is JsonObject -> {
                    val listKey = listOf("list", "records", "rows", "billList", "data", "items")
                        .firstOrNull { e[it] is kotlinx.serialization.json.JsonArray }
                    if (listKey != null) collect(e[listKey]!!) else items.add(e)
                }
                else -> {}
            }
        }
        collect((el as? JsonObject)?.get("data") ?: el)
        return items.mapNotNull { o ->
            val consume = o["consumeBillDTO"] as? JsonObject
            val recharge = o["rechargeBillDTO"] as? JsonObject
            /** 非金额字段原样取标量文本（日期/订单号/upState/类型名，不做单位换算）。 */
            fun f(obj: JsonObject, name: String): String =
                (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            /** 金额字段统一归一成元字符串：JSON 整数=0.001 元，JSON 字符串=元。 */
            fun money(obj: JsonObject, name: String): String =
                obj[name].asYuanOrNull()?.let { formatYuan(it) }.orEmpty()
            when {
                consume != null -> BillItem(
                    kind = "消费",
                    orderId = f(consume, "orderId"),
                    dealDate = f(consume, "dealDate"),
                    consumeDate = f(consume, "consumeDate"),
                    uploadDate = f(consume, "uploadDate"),
                    upState = f(consume, "upState"),
                    preDeductMoney = money(consume, "preDeductMoney"),
                    consumeMoney = money(consume, "consumeMoney"),
                    consumeMoneyReal = money(consume, "consumeMoneyReal"),
                    consumeMoneyGiven = money(consume, "consumeMoneyGiven"),
                    dealMoney = money(consume, "dealMoney"),
                )
                recharge != null -> BillItem(
                    kind = "充值",
                    dealDate = f(recharge, "dealDate"),
                    dealMoney = money(recharge, "dealMoney"),
                    typeName = f(recharge, "typeName"),
                )
                else -> null
            }
        }.sortedByDescending { it.dealDate }
    }
}
