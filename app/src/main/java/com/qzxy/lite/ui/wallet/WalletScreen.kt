package com.qzxy.lite.ui.wallet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.appContainer
import com.qzxy.lite.data.repository.PayChannel
import com.qzxy.lite.tmall.TmallWalletAccount
import com.qzxy.lite.tmall.TmallWalletRecord
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.SoftNotice
import com.qzxy.lite.ui.common.verticalScrollbar
import com.qzxy.lite.ui.theme.LocalQzColors
import com.qzxy.lite.ui.theme.SteamWaves
import com.qzxy.lite.ui.theme.TabularNumbers

/**
 * 钱包页：同页展示趣智校园钱包（余额 + 官方支付宝直充）与天猫校园水卡钱包（余额/冻结/近期流水只读）。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WalletScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit = {},
    onOpenTmallPhoneLogin: () -> Unit = {},
    onOpenTmallTaobaoLogin: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: WalletViewModel = viewModel {
        WalletViewModel(
            context.appContainer.walletRepository,
            context.appContainer.sessionStore,
            context.appContainer.tmallWaterRepository,
        )
    }
    val state by vm.state.collectAsState()
    val session by context.appContainer.sessionStore.session.collectAsState()
    val isQzxyLoggedIn = session?.isLoggedIn == true
    val isTmallLoggedIn = state.tmallLoggedIn
    val anyLoggedIn = isQzxyLoggedIn || isTmallLoggedIn
    val qz = LocalQzColors.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 每次回到钱包页（含关阀或接水后切回 Tab）自动静默刷新已登录平台余额
    DisposableEffect(lifecycleOwner, isQzxyLoggedIn, isTmallLoggedIn) {
        val observer = LifecycleEventObserver { _, event ->
            if ((isQzxyLoggedIn || isTmallLoggedIn) && event == Lifecycle.Event.ON_RESUME) {
                vm.refresh(initial = false, force = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxSize()) {
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
                Text("钱包", style = MaterialTheme.typography.titleLarge)
            }

            val walletScroll = rememberScrollState()
            PullToRefreshBox(
                isRefreshing = anyLoggedIn && (state.refreshing || state.tmallRefreshing),
                onRefresh = { if (anyLoggedIn) vm.refresh() },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScrollbar(walletScroll)
                        .verticalScroll(walletScroll)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // —— 趣智校园钱包区块 ——
                    if (isQzxyLoggedIn) {
                        if (state.loading && state.balance == "--") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) { CircularProgressIndicator() }
                        }

                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(qz.deep)
                                .padding(22.dp),
                        ) {
                            SteamWaves(
                                Modifier.matchParentSize(),
                                color = qz.onDeep,
                                baseAlpha = 0.12f,
                            )
                            Column {
                                Text(
                                    "趣智钱包余额",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = qz.onDeep.copy(alpha = 0.72f),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    "¥ ${state.balance}",
                                    style = MaterialTheme.typography.displayMedium,
                                    color = qz.onDeep,
                                )
                                state.summaryHint?.let { hint ->
                                    Spacer(Modifier.size(10.dp))
                                    Text(
                                        hint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = qz.onDeep.copy(alpha = 0.55f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        Text(
                            "趣智充值",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val tiers = state.tiers
                            val amounts = if (tiers.isEmpty()) listOf("5", "10", "20", "30", "50") else tiers.map { it.amount }
                            amounts.forEach { yuan ->
                                val selected = state.amount == yuan
                                FilledTonalButton(
                                    onClick = { vm.onAmountChange(yuan) },
                                    colors = if (selected) {
                                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        )
                                    } else {
                                        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
                                    },
                                ) {
                                    Text("¥${yuan.removeSuffix(".0")}")
                                }
                            }
                        }

                        Button(
                            onClick = { vm.recharge(PayChannel.ALIPAY, context) },
                            enabled = !state.recharging,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                        ) { Text("支付宝充值") }

                        if (state.recharging) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("正在下单并拉起官方支付…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        state.message?.let { SoftNotice(it) }
                        state.error?.let { ErrorNotice(it) }
                    } else {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("趣智校园钱包", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "登录趣智校园后可查看水房余额、支付宝官方充值与账单流水。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    onClick = onOpenLogin,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("登录趣智校园")
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // —— 天猫校园水卡钱包区块（只读） ——
                    Text(
                        "天猫校园水卡钱包",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (isTmallLoggedIn) {
                        if (state.tmallLoading && state.tmallAccounts.isEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                        }

                        state.tmallError?.let { ErrorNotice(it) }

                        if (state.tmallAccounts.isNotEmpty()) {
                            state.tmallAccounts.forEachIndexed { idx, acct ->
                                TmallWalletAccountCard(
                                    account = acct,
                                    selected = idx == state.tmallSelectedIndex,
                                    selectable = state.tmallAccounts.size > 1,
                                    onClick = { vm.selectTmallAccount(idx) },
                                )
                            }

                            TmallWalletRecordsCard(
                                records = state.tmallRecords,
                                refreshing = state.tmallRefreshing,
                            )
                        } else if (!state.tmallLoading && state.tmallError == null) {
                            Card(
                                Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                            ) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("暂未发现需预付的天猫水卡", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        state.tmallActivityName.ifBlank {
                                            "若本校直饮水免费或按次免密结算，则无独立水卡余额；也可先在「饮水」页添加一台饮水机后再下拉刷新。"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("尚未登录天猫校园", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "登录后可在此查看天猫校园水卡可用余额、冻结金额与近期消费流水（只读）。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = onOpenTmallPhoneLogin,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("短信登录") }
                                    OutlinedButton(
                                        onClick = onOpenTmallTaobaoLogin,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("淘宝登录") }
                                }
                            }
                        }
                    }

                    Text(
                        "趣智充值仅做「拉起官方支付」这一层；天猫校园水卡当前仅展示余额与流水，如需充值请在官方天猫校园 App 操作。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TmallWalletAccountCard(
    account: TmallWalletAccount,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (selectable) it.clickable(onClick = onClick) else it },
        shape = MaterialTheme.shapes.medium,
        border = if (selectable && selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selectable && selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.background
            },
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.fundName, style = MaterialTheme.typography.titleMedium)
                val notes = buildList {
                    if (account.freezeFen > 0L) add("冻结 ¥${account.freezeDisplay}")
                    if (account.isWithdrawingFrozen) add("提现审核中")
                    if (account.activityName.isNotBlank()) add(account.activityName)
                }
                if (notes.isNotEmpty()) {
                    Text(
                        notes.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (selectable) {
                    Text(
                        if (selected) "当前查看流水" else "点击切换流水",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "¥ ${account.usableDisplay}",
                style = MaterialTheme.typography.titleLarge.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TmallWalletRecordsCard(
    records: List<TmallWalletRecord>,
    refreshing: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("近期消费流水", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (refreshing && records.isEmpty()) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }
            if (records.isEmpty()) {
                Text(
                    "暂无近期消费记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                records.forEachIndexed { idx, rec ->
                    if (idx > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val title = listOfNotNull(
                                rec.sourceInfo.takeIf { it.isNotBlank() },
                                rec.outOrderId.takeIf { it.isNotBlank() },
                            ).joinToString(" ")
                            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(
                                rec.createTimeStr.takeIf { it.isNotBlank() },
                                rec.remark.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            if (sub.isNotBlank()) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            rec.amountDisplay,
                            style = MaterialTheme.typography.titleSmall.merge(TabularNumbers),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
