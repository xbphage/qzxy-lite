package com.qzxy.lite.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.BuildConfig
import com.qzxy.lite.appContainer
import com.qzxy.lite.ui.common.DisclaimerDialog
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.ExpandableRawCard
import com.qzxy.lite.ui.common.verticalScrollbar
import com.qzxy.lite.update.UpdateManager

/**
 * 我的：双平台账号中心 + 应用更新 + 趣智账单/退款（按登录态展示）+ 免责声明。
 */
@Composable
fun MineScreen(
    onOpenBillDetail: () -> Unit,
    onCheckUpdate: () -> Unit = {},
    onOpenQzxyLogin: () -> Unit = {},
    onOpenTmallPhoneLogin: () -> Unit = {},
    onOpenTmallTaobaoLogin: () -> Unit = {},
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val vm: MineViewModel = viewModel {
        MineViewModel(
            context.appContainer.campusRepository,
            context.appContainer.billRepository,
            context.appContainer.sessionStore,
        )
    }
    val state by vm.state.collectAsState()
    val session by context.appContainer.sessionStore.session.collectAsState()
    val tmallCookies by context.appContainer.tmallSessionStore.cookies.collectAsState()
    val qzxyLoggedIn = session?.isLoggedIn == true
    val tmallLoggedIn = tmallCookies.isNotEmpty()
    val tmallAccount = if (tmallLoggedIn) {
        com.qzxy.lite.tmall.TmallWaterSessionStore.maskAccount(
            com.qzxy.lite.tmall.TmallWaterSessionStore.extractAccountId(tmallCookies),
        )
    } else ""

    var diagOpen by remember { mutableStateOf(false) }
    var disclaimerOpen by remember { mutableStateOf(false) }
    val autoUpdateEnabled by UpdateManager.autoUpdateEnabledFlow(context).collectAsState()

    val pageScroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScrollbar(pageScroll)
            .verticalScroll(pageScroll),
    ) {
        // —— 个人信息头：双平台综合状态 ——
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column {
                val titleText = when {
                    qzxyLoggedIn && tmallLoggedIn -> state.maskedPhone.ifBlank { "双平台已登录" }
                    qzxyLoggedIn -> state.maskedPhone.ifBlank { "趣智校园已登录" }
                    tmallLoggedIn -> "天猫 ${tmallAccount.ifBlank { "已登录" }}"
                    else -> "未登录账号"
                }
                val subText = when {
                    qzxyLoggedIn && tmallLoggedIn -> "${state.school} · 已同时绑定天猫饮水"
                    qzxyLoggedIn -> state.school
                    tmallLoggedIn -> "仅使用天猫校园直饮水"
                    else -> "可按需分别登录趣智水房或天猫饮水"
                }
                Text(titleText, style = MaterialTheme.typography.titleLarge)
                Text(
                    subText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (qzxyLoggedIn && state.projectId.isNotBlank()) {
                    Text(
                        "projectId ${state.projectId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (qzxyLoggedIn && state.loading) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            // —— 双平台账号中心 ——
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.WaterDrop,
                            contentDescription = null,
                            tint = if (qzxyLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("趣智校园", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (qzxyLoggedIn) {
                                    "已登录 ${state.maskedPhone} · ${state.school}"
                                } else {
                                    "未登录 · 用于浴室水房与钱包充值"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (qzxyLoggedIn) {
                            TextButton(onClick = onLogout) {
                                Text("退出", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(onClick = onOpenQzxyLogin) {
                                Text("登录")
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.LocalDrink,
                            contentDescription = null,
                            tint = if (tmallLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("天猫校园", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (tmallLoggedIn) {
                                    "已登录 ${tmallAccount.ifBlank { "饮水会话有效" }}"
                                } else {
                                    "未登录 · 用于扫码直饮水"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (tmallLoggedIn) {
                            TextButton(onClick = { context.appContainer.tmallWaterRepository.logout() }) {
                                Text("退出", color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            Row {
                                TextButton(onClick = onOpenTmallPhoneLogin) { Text("短信") }
                                TextButton(onClick = onOpenTmallTaobaoLogin) { Text("淘宝") }
                            }
                        }
                    }
                }
            }

            // —— 应用更新：手动检查 + 自动检查/静默下载开关 ——
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onCheckUpdate)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.SystemUpdateAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("检查更新", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "当前版本 v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { UpdateManager.setAutoUpdateEnabled(context, !autoUpdateEnabled) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("自动检查并下载更新", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (autoUpdateEnabled) "启动时自动检查新版并静默下载" else "已关闭自动更新（仍可点击上方手动检查）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoUpdateEnabled,
                            onCheckedChange = { UpdateManager.setAutoUpdateEnabled(context, it) },
                        )
                    }
                }
            }

            // —— 诊断日志（仅 diag 构建显示） ——
            if (com.qzxy.lite.BuildConfig.DIAG_LOG) {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { diagOpen = true },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("诊断日志", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "复现问题后导出/分享日志，便于远程排查",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // —— 账单明细入口（仅趣智已登录展示） ——
            if (qzxyLoggedIn) {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenBillDetail),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text("账单明细", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "消费 / 充值流水，按月查询",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "›",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // —— 退款进度（本项目退款入口当前关闭） ——
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("退款进度", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "服务端配置该项目退款功能现未开启（refundSwitch=0），开放前仅支持查看进度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ExpandableRawCard("退款记录 · 原始响应", state.refundRaw, "暂无退款记录")
                    }
                }
            }

            // —— 免责声明与免费申明 ——
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { disclaimerOpen = true },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("免责声明与免费申明", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "本软件完全免费开源 · 如遇收费请退款举报",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (qzxyLoggedIn) {
                state.error?.let { ErrorNotice(it) }
            }

            Spacer(
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
            )
        }
    }

    if (disclaimerOpen) {
        DisclaimerDialog(
            requireConfirmTyping = false,
            onAccept = { disclaimerOpen = false },
            onDecline = { disclaimerOpen = false },
        )
    }

    if (diagOpen) {
        com.qzxy.lite.ui.diag.DiagLogDialog(onDismiss = { diagOpen = false })
    }
}
