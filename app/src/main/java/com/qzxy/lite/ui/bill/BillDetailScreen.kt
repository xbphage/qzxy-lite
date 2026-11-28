package com.qzxy.lite.ui.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.appContainer
import com.qzxy.lite.data.repository.BillItem
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.QzEmptyState
import com.qzxy.lite.ui.common.ScrollMoreHint
import com.qzxy.lite.ui.common.StatusDot
import com.qzxy.lite.ui.common.lazyVerticalScrollbar
import com.qzxy.lite.ui.common.verticalScrollbar
import com.qzxy.lite.ui.theme.LocalQzColors
import com.qzxy.lite.ui.theme.TabularNumbers
import java.time.LocalDate

/**
 * 账单明细页（2026-11-14 改版）：
 * 顶部 ‹ 年月 › 翻月 + 下拉选择最近 12 个月（替代手填），消费/充值 FilterChip；
 * 流水行 = 状态点 + 时间/订单 + 右对齐表数字金额；点行看全部字段详情弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillDetailScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: BillDetailViewModel = viewModel { BillDetailViewModel(context.appContainer.billRepository) }
    val state by vm.state.collectAsState()
    val qz = LocalQzColors.current
    var monthMenuOpen by remember { mutableStateOf(false) }

    // 下拉里给最近 12 个月（含当月），最新在前
    val recentMonths = remember {
        (11 downTo 0).map { LocalDate.now().minusMonths(it.toLong()) }
            .map { it.toString().substring(0, 7) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize()) {
            // —— 顶栏：返回 + 标题（单次 statusBarsPadding） ——
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text("账单明细", style = MaterialTheme.typography.titleLarge)
            }

            // —— 年月选择：‹ [2026-11 ▾] ›（下拉最近 12 个月） ——
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { vm.shiftMonth(-1) }) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "上一个月")
                }
                Box {
                    TextButton(onClick = { monthMenuOpen = true }) {
                        Text(
                            state.month,
                            style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                        )
                    }
                    DropdownMenu(
                        expanded = monthMenuOpen,
                        onDismissRequest = { monthMenuOpen = false },
                    ) {
                        recentMonths.forEach { month ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        month,
                                        style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                                        color = if (month == state.month) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    monthMenuOpen = false
                                    vm.setMonth(month)
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { vm.shiftMonth(+1) }) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "下一个月")
                }
            }

            // —— 类型筛选 ——
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.type == "0",
                    onClick = { vm.onTypeChange("0") },
                    label = { Text("消费") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
                FilterChip(
                    selected = state.type == "1",
                    onClick = { vm.onTypeChange("1") },
                    label = { Text("充值") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))

            // —— 流水列表 ——
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                if (state.loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .lazyVerticalScrollbar(listState),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.error?.let { error ->
                        item { ErrorNotice(error) }
                    }
                    if (state.bills.isEmpty() && !state.loading && state.error == null) {
                        item {
                            QzEmptyState("本月暂无${if (state.type == "0") "消费" else "充值"}记录")
                        }
                    }
                    itemsIndexed(state.bills) { _, bill ->
                        BillRow(bill) { vm.select(bill) }
                    }
                }
            }
        }
    }

    // —— 账单详情弹窗：金额打头，字段成组 ——
    state.selected?.let { bill ->
        AlertDialog(
            onDismissRequest = { vm.select(null) },
            shape = MaterialTheme.shapes.large,
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        bill.kind,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (bill.kind == "充值") MaterialTheme.colorScheme.primary else qz.hot,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        detailHeadline(bill),
                        style = MaterialTheme.typography.displaySmall.merge(TabularNumbers),
                        color = if (bill.kind == "充值") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            text = {
                val detailScroll = rememberScrollState()
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScrollbar(detailScroll)
                            .verticalScroll(detailScroll)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        DetailRow("订单号", bill.orderId)
                        DetailRow("下单时间", bill.dealDate)
                        DetailRow("用水时间", bill.consumeDate)
                        DetailRow("上传时间", bill.uploadDate)
                        DetailRow("上传状态", bill.upState)
                        DetailRow("预扣金额", money(bill.preDeductMoney))
                        DetailRow("实际消费", money(bill.consumeMoney))
                        DetailRow("实扣金额", money(bill.consumeMoneyReal))
                        DetailRow("赠送抵扣", money(bill.consumeMoneyGiven))
                        DetailRow("支付方式", bill.typeName)
                    }
                    ScrollMoreHint(visible = detailScroll.canScrollForward)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.select(null) }) { Text("关闭") }
            },
        )
    }
}

/** 流水行：状态点（充值=泉青 / 消费=汤珊瑚）+ 时间/订单 + 右对齐表数字金额。 */
@Composable
private fun BillRow(bill: BillItem, onClick: () -> Unit) {
    val qz = LocalQzColors.current
    val isRecharge = bill.kind == "充值"
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(if (isRecharge) MaterialTheme.colorScheme.primary else qz.hot)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bill.kind,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isRecharge) MaterialTheme.colorScheme.primary else qz.hot,
                    )
                    if (bill.typeName.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            bill.typeName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                if (bill.dealDate.isNotBlank()) {
                    Text(
                        bill.dealDate,
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (bill.orderId.isNotBlank()) {
                    Text(
                        "订单 ${bill.orderId.take(14)}${if (bill.orderId.length > 14) "…" else ""}",
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                amountText(bill),
                style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                color = if (isRecharge) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** 行金额：充值 = +¥x；消费优先实际消费，其次预扣，再无则待结算。 */
private fun amountText(bill: BillItem): String = when (bill.kind) {
    "充值" -> "+¥${bill.dealMoney}"
    "消费" -> when {
        bill.consumeMoney.isNotBlank() -> "-¥${bill.consumeMoney}"
        bill.preDeductMoney.isNotBlank() -> "-¥${bill.preDeductMoney}"
        else -> "待结算"
    }
    else -> ""
}

/** 详情弹窗打头金额（与行金额同规则）。 */
private fun detailHeadline(bill: BillItem): String = amountText(bill)

private fun money(v: String): String = if (v.isNotBlank()) "¥$v" else ""

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            textAlign = TextAlign.End,
        )
    }
}
