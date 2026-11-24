package com.qzxy.lite.ui.campus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.appContainer
import com.qzxy.lite.data.bath.BathPhase
import com.qzxy.lite.data.model.BathDevice
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.QzEmptyState
import com.qzxy.lite.ui.common.SoftNotice
import com.qzxy.lite.ui.common.StatusDot
import com.qzxy.lite.ui.theme.LocalQzColors
import com.qzxy.lite.ui.theme.TabularNumbers
import java.time.LocalTime
import kotlinx.coroutines.delay

/**
 * 首页（2026-11-14 优化）：问候 + 水房标题固定在顶部（自适应状态栏，不随列表滚动），
 * 设备列表在下方独立滚动 + 下拉刷新。换绑校园在「我的」页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(
    onOpenBath: (sn: String, deviceSn: String, name: String, type: String) -> Unit,
    onOpenScan: () -> Unit,
) {
    val context = LocalContext.current
    val vm: CampusViewModel = viewModel {
        CampusViewModel(
            context.appContainer.campusRepository,
            context.appContainer.authRepository,
            context.appContainer.savedDeviceStore,
            context.appContainer.bathRepository,
        )
    }
    val state by vm.state.collectAsState()
    val session by context.appContainer.sessionStore.session.collectAsState()
    val savedDevices by context.appContainer.savedDeviceStore.devices.collectAsState()
    // 洗澡会话快照：刚开阀返回首页时立即显示"使用中"，不必等服务端列表刷新。
    val bathSnapshots by context.appContainer.bathSessionStore.snapshots.collectAsState()

    // 首页可见期间轻量对账（20s 一次，只查在用状态）：别处关阀/设备被占用也能反映出来。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(HOME_RECONCILE_INTERVAL_MS)
                vm.refreshUsageStatus()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // —— 固定头部：单次 statusBarsPadding，始终贴顶 ——
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                greeting(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("水房", style = MaterialTheme.typography.headlineSmall)
            Text(
                "校园 ${session?.projectId?.ifBlank { "--" } ?: "--"} · 下拉刷新设备",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.load() },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        MenuRow(
                            icon = { Icon(Icons.Rounded.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary) },
                            label = "蓝牙找设备",
                            onClick = onOpenScan,
                        )
                    }
                }

                state.error?.let { error ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ErrorNotice(error)
                            TextButton(onClick = { vm.load(initial = true) }) { Text("重试") }
                        }
                    }
                }
                state.message?.let { message ->
                    item { SoftNotice(message) }
                }
                if (state.loading) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    }
                }

                item {
                    Text(
                        "浴室 / 热水器",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 本地保存的设备优先显示（扫描识别后落盘）；无保存设备时才拉服务端列表
                val displayDevices = if (savedDevices.isNotEmpty()) {
                    savedDevices.map { d ->
                        // 本地快照（刚开阀）与 ViewModel 对账结果都是"在用"的来源，任一命中即算在用。
                        val snapshotBusy = bathSnapshots[d.snCode]?.phase == BathPhase.SHOWERING
                        val reconciled = state.devices.firstOrNull { it.snCode == d.snCode }
                        BathDevice(
                            snCode = d.snCode,
                            deviceSncode = d.deviceSncode.takeIf { it.isNotBlank() },
                            devName = d.name,
                            macAddress = d.mac,
                            deviceType = d.type,
                            signal = d.rssi,
                            usingStatus = if (snapshotBusy || reconciled?.isBusy == true) "1" else null,
                        )
                    }
                } else {
                    state.devices
                }

                items(displayDevices, key = { it.sn ?: it.macAddress ?: it.label }) { device ->
                    DeviceRow(device, signalText = device.signal?.takeIf { it != 0 }?.let { "信号 $it dBm" }) {
                        // snCode 用于下单；deviceSncode 是原版同发的第二标识，缺失时为空（仓库层回退）。
                        val sn = device.snCode?.takeIf { it.isNotBlank() }
                            ?: device.deviceSncode?.takeIf { it.isNotBlank() }
                            ?: device.macAddress?.takeIf { it.isNotBlank() }
                            ?: ""
                        val deviceSncode = device.deviceSncode?.takeIf { it.isNotBlank() } ?: ""
                        if (sn.isBlank()) return@DeviceRow
                        onOpenBath(sn, deviceSncode, device.label, device.deviceType ?: "NET")
                    }
                }

                if (displayDevices.isEmpty() && !state.loading && state.error == null) {
                    item {
                        QzEmptyState("还没有设备。下拉刷新，或用蓝牙扫描找设备。")
                    }
                }
            }
        }
    }
}

/** 设备行：状态点 + 设备名 + 水表数字 SN（可附信号值）；整卡可点，「进入」按钮同义。 */
@Composable
private fun DeviceRow(device: BathDevice, signalText: String? = null, onSelect: () -> Unit) {
    val qz = LocalQzColors.current
    val snText = device.sn ?: device.macAddress ?: "--"
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusDot(if (device.isBusy) qz.hot else MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(device.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(2.dp))
                Text(
                    buildString {
                        append("SN ")
                        append(snText)
                        device.areaName?.let { append(" · ").append(it) }
                        signalText?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (device.isBusy) "使用中" else "空闲",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (device.isBusy) qz.hot else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "进入",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 菜单行：图标 + 标题 + 右箭头，整行可点。 */
@Composable
private fun MenuRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** 按时段问候（凌晨/早上/中午/下午/晚上）。 */
private fun greeting(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 0..4 -> "夜深了"
        in 5..10 -> "早上好"
        in 11..12 -> "中午好"
        in 13..17 -> "下午好"
        else -> "晚上好"
    }
}

/** 首页可见期间的在用状态对账间隔（只查 using，很轻）。 */
private const val HOME_RECONCILE_INTERVAL_MS = 20_000L
