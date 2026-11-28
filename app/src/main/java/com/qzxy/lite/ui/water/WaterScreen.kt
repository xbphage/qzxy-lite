package com.qzxy.lite.ui.water

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.appContainer
import com.qzxy.lite.tmall.SavedWaterDevice
import com.qzxy.lite.tmall.WaterDeviceSortMode
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.QzEmptyState
import com.qzxy.lite.ui.common.SoftNotice
import com.qzxy.lite.ui.common.StatusDot
import com.qzxy.lite.ui.common.lazyVerticalScrollbar
import com.qzxy.lite.ui.theme.LocalQzColors
import com.qzxy.lite.ui.theme.TabularNumbers

private enum class WaterDialogPanel { ADD, MANUAL, SETTINGS, MANAGE }

/**
 * 天猫校园饮水主页：
 * - 支持手机验证码+滑块、淘宝账号网页两种登录方式；
 * - 支持扫码或手输添加饮水机、收藏列表排序删除、点击解锁与停止接水。
 */
@Composable
fun WaterScreen(
    pendingScannedQr: String?,
    onConsumeScannedQr: () -> Unit,
    pendingOpenManual: Boolean = false,
    onConsumeOpenManual: () -> Unit = {},
    onOpenScanner: () -> Unit,
    onOpenPhoneLogin: () -> Unit,
    onOpenTaobaoLogin: () -> Unit,
) {
    val context = LocalContext.current
    val vm: WaterViewModel = viewModel { WaterViewModel(context.appContainer.tmallWaterRepository) }
    val state by vm.state.collectAsState()
    var panel by rememberSaveable { mutableStateOf<WaterDialogPanel?>(null) }
    var manualInput by rememberSaveable { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(pendingScannedQr, state.loggedIn) {
        if (!pendingScannedQr.isNullOrBlank() && state.loggedIn) {
            vm.addDevice(pendingScannedQr)
            onConsumeScannedQr()
        }
    }
    androidx.compose.runtime.LaunchedEffect(pendingOpenManual) {
        if (pendingOpenManual) {
            panel = WaterDialogPanel.MANUAL
            onConsumeOpenManual()
        }
    }

    val canEdit = !state.busy
    val canUse = canEdit && state.loggedIn

    when (panel) {
        WaterDialogPanel.ADD -> WaterAddDialog(
            onScan = {
                panel = null
                onOpenScanner()
            },
            onManualInput = { panel = WaterDialogPanel.MANUAL },
            onClose = { panel = null },
        )
        WaterDialogPanel.MANUAL -> WaterManualDialog(
            value = manualInput,
            onValueChange = { manualInput = it },
            enabled = canUse,
            onAdd = {
                vm.addDevice(manualInput)
                manualInput = ""
                panel = null
            },
            onClose = { panel = null },
        )
        WaterDialogPanel.SETTINGS -> WaterSettingsDialog(
            mode = state.sortMode,
            account = state.maskedAccount,
            loggedIn = state.loggedIn,
            onSort = vm::setSortMode,
            onLogout = {
                vm.logout()
                panel = null
            },
            onClose = { panel = null },
        )
        WaterDialogPanel.MANAGE -> WaterManageDialog(
            devices = state.devices,
            custom = state.sortMode == WaterDeviceSortMode.CUSTOM,
            onSave = { kept ->
                val keptIds = kept.map { it.resNo }
                state.devices.filterNot { it.resNo in keptIds }.forEach { vm.removeDevice(it.resNo) }
                if (state.sortMode == WaterDeviceSortMode.CUSTOM) vm.saveCustomOrder(keptIds)
                panel = null
            },
            onClose = { panel = null },
        )
        null -> Unit
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("饮水", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (state.loggedIn) "天猫校园 · ${state.maskedAccount.ifBlank { "已登录" }}"
                    else "天猫校园直饮水 · 请先登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loggedIn) {
                IconButton(onClick = onOpenScanner, enabled = canUse) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = "扫饮水二维码", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { panel = WaterDialogPanel.ADD }, enabled = canUse) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加饮水机")
                }
            }
            IconButton(onClick = { panel = WaterDialogPanel.SETTINGS }, enabled = canEdit) {
                Icon(Icons.Rounded.Settings, contentDescription = "饮水设置")
            }
        }

        val listState = rememberLazyListState()
        val displayDevices = remember(state.devices, state.highlightedResNo) {
            com.qzxy.lite.tmall.WaterDeviceOrder.pinToTop(state.devices, state.highlightedResNo)
        }
        LaunchedEffect(state.highlightedResNo, displayDevices.firstOrNull()?.resNo) {
            val hi = state.highlightedResNo
            if (!hi.isNullOrBlank() && displayDevices.firstOrNull()?.resNo == hi) {
                listState.animateScrollToItem(0)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .lazyVerticalScrollbar(listState),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.loggedIn) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("登录天猫校园后即可扫码接水", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "支持手机号短信验证码（含阿里滑块验证）与淘宝账号 H5 网页登录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = onOpenPhoneLogin,
                                enabled = canEdit,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("手机号验证码登录（含滑块）")
                            }
                            OutlinedButton(
                                onClick = onOpenTaobaoLogin,
                                enabled = canEdit,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("淘宝账号网页登录")
                            }
                        }
                    }
                }
            }

            if (state.processingResNo == null && state.status.isNotBlank()) {
                item { SoftNotice(state.status) }
            }
            state.error?.let { err ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ErrorNotice(err)
                        TextButton(onClick = vm::dismissError) { Text("知道了") }
                    }
                }
            }

            if (state.loggedIn) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onOpenScanner,
                            enabled = canUse,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("扫饮水码")
                        }
                        OutlinedButton(
                            onClick = { panel = WaterDialogPanel.MANUAL },
                            enabled = canUse,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("手输设备号")
                        }
                    }
                }

                if (displayDevices.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "我的饮水机",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { panel = WaterDialogPanel.MANAGE }, enabled = canEdit) {
                                Text("管理设备")
                            }
                        }
                    }
                    items(displayDevices, key = { it.resNo }) { device ->
                        val activeOrder = state.activeOrder?.takeIf { it.resNo == device.resNo }
                        WaterDeviceCard(
                            device = device,
                            highlighted = state.highlightedResNo == device.resNo,
                            processingText = state.status.takeIf { state.processingResNo == device.resNo },
                            error = state.deviceError?.takeIf { it.resNo == device.resNo }?.message,
                            unlocked = state.lastUnlockedResNo == device.resNo,
                            activeIsvOrderId = activeOrder?.isvOrderId,
                            canUse = canUse,
                            onUse = { vm.quickUse(device.resNo) },
                            onStop = { orderId -> vm.stopWater(device.resNo, orderId) },
                        )
                    }
                } else {
                    item {
                        QzEmptyState("还没有饮水机，点击「扫饮水码」扫码添加即可一键接水。")
                    }
                }
            }
        }
    }
}

@Composable
private fun WaterDeviceCard(
    device: SavedWaterDevice,
    highlighted: Boolean,
    processingText: String?,
    error: String?,
    unlocked: Boolean,
    activeIsvOrderId: String?,
    canUse: Boolean,
    onUse: () -> Unit,
    onStop: (String) -> Unit,
) {
    val qz = LocalQzColors.current
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = if (highlighted) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(if (unlocked) qz.hot else MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        if (highlighted) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    "刚添加",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    val meta = listOfNotNull(
                        "编号 ${device.resNo}",
                        device.location.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onUse,
                    enabled = canUse,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    if (processingText != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(processingText)
                    } else {
                        Text("解锁接水")
                    }
                }
            }

            if (error != null) {
                ErrorNotice(error)
            } else if (unlocked) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "解锁成功，请按机器上的「出水」键。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (!activeIsvOrderId.isNullOrBlank()) {
                        TextButton(onClick = { onStop(activeIsvOrderId) }, enabled = canUse) {
                            Text("结束接水")
                        }
                    }
                }
            }
        }
    }
}
