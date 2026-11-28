package com.qzxy.lite.ui.wallet

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.appContainer
import com.qzxy.lite.data.repository.PayChannel
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.SoftNotice
import com.qzxy.lite.ui.common.verticalScrollbar
import com.qzxy.lite.ui.theme.LocalQzColors
import com.qzxy.lite.ui.theme.SteamWaves

/**
 * 钱包页（2026-11-13 视觉改版）：深潭余额面板（水表数字）+ 充值 + 折叠的原始响应证据。
 * 充值金额按服务端档位（settlement/recharge/package/list 实测 5/10/20/30/50 元）点选。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WalletScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: WalletViewModel = viewModel { WalletViewModel(context.appContainer.walletRepository) }
    val state by vm.state.collectAsState()
    val qz = LocalQzColors.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 每次回到钱包页（含关阀后切回 Tab）自动静默刷新一次余额，避免 restoreState 展示旧余额
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh(initial = false)
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
                isRefreshing = state.refreshing,
                onRefresh = { vm.refresh() },
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
                    if (state.loading && state.balance == "--") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }

                    // 余额面板：深潭底 + 蒸汽波 + 水表数字
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
                                "钱包余额",
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
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Text(
                        "充值",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 充值档位来自 settlement/recharge/package/list（实测 5/10/20/30/50 元）；FlowRow 自动换行
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

                    Text(
                        "支付仅做「拉起官方支付」这一层；本项目仅用于本人账号操作与学习。",
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
