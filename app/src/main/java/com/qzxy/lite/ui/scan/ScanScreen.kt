package com.qzxy.lite.ui.scan

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.appContainer
import com.qzxy.lite.ble.BleSighting
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.QzEmptyState
import com.qzxy.lite.ui.common.lazyVerticalScrollbar
import com.qzxy.lite.ui.theme.TabularNumbers
import com.qzxy.lite.ui.theme.rememberMotionAllowed

/**
 * 蓝牙找设备（2026-11-13 视觉改版）：开关收成一个按钮，扫描状态用脉冲点表达，
 * 设备行带 RSSI 信号格；识别规则与「识别后才能使用」的逻辑与改版前一致。
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onUseDevice: (snCode: String, deviceSncode: String, displayName: String) -> Unit,
) {
    val context = LocalContext.current
    val vm: ScanViewModel = viewModel {
        ScanViewModel(context.appContainer.bleScanner, context.appContainer.deviceApi)
    }
    val state by vm.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) vm.start() else vm.stop()
    }

    fun ensurePermissionAndStart() {
        val needed = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) vm.start() else permissionLauncher.launch(needed.toTypedArray())
    }

    LaunchedEffect(Unit) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        if (manager?.adapter == null) {
            vm.stop()
        } else {
            ensurePermissionAndStart()
        }
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
                IconButton(onClick = {
                    vm.stop()
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text("蓝牙找设备", style = MaterialTheme.typography.titleLarge)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (state.scanning) vm.stop() else ensurePermissionAndStart() }) {
                        Icon(
                            if (state.scanning) Icons.Rounded.Stop else Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.scanning) "停止扫描" else "开始扫描")
                    }
                    Spacer(Modifier.weight(1f))
                    ScanStatusBadge(scanning = state.scanning)
                }

                state.error?.let { ErrorNotice(it) }

                Text(
                    "识别规则与原版一致：广播名前缀 KLCXKJ；MAC 前两位 C0 归一化为 00。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val scanListState = rememberLazyListState()
            LazyColumn(
                state = scanListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp)
                    .lazyVerticalScrollbar(scanListState),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(state.devices, key = { it.normalizedMac }) { device ->
                    DeviceRow(device, state.nameByMac[device.normalizedMac]) { displayName ->
                        // 服务端返回的 snCode/deviceSncode 才是下单标识；只有查不到时才回退到
                        // 归一化 MAC（那种情况服务端大概率会回 306 设备不在线，界面上会明确提示）。
                        val info = state.deviceByMac[device.normalizedMac]
                        val serverSn = info?.snCode?.takeIf { it.isNotBlank() }
                        val serverDeviceSn = info?.deviceSncode?.takeIf { it.isNotBlank() }
                        val snCode = serverSn ?: serverDeviceSn ?: device.normalizedMac.replace(":", "")
                        val deviceSncode = serverDeviceSn ?: serverSn ?: ""
                        val savedType = if (info?.isOfflineBleOnly == true) "BLE" else (info?.deviceType ?: "NET")
                        // 保存到本地设备库：首页直接显示（含信号值），无需重复扫描（与原版"添加设备"一致）
                        context.appContainer.savedDeviceStore.add(
                            com.qzxy.lite.data.device.SavedDevice(
                                snCode = snCode,
                                deviceSncode = deviceSncode,
                                name = displayName,
                                type = savedType,
                                mac = device.normalizedMac,
                                rssi = device.rssi,
                            )
                        )
                        onUseDevice(snCode, deviceSncode, displayName)
                    }
                }
                if (state.devices.isEmpty() && state.error == null) {
                    item {
                        QzEmptyState(
                            if (state.scanning) "正在监听广播，靠近设备试试。"
                            else "未发现设备；请确认设备已上电、蓝牙已开启并靠近设备。",
                        )
                    }
                }
            }

            Spacer(
                Modifier
                    .navigationBarsPadding()
                    .height(8.dp),
            )
        }
    }
}

/** 扫描状态徽标：扫描中=泉青脉冲点，停止=灰点。 */
@Composable
private fun ScanStatusBadge(scanning: Boolean) {
    val motionAllowed = rememberMotionAllowed()
    val pulseAlpha: Float = if (scanning && motionAllowed) {
        val transition = rememberInfiniteTransition(label = "scan")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700), repeatMode = RepeatMode.Reverse),
            label = "pulse",
        )
        a
    } else {
        1f
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .alpha(pulseAlpha)
                .clip(RoundedCornerShape(50))
                .background(
                    if (scanning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (scanning) "扫描中" else "已停止",
            style = MaterialTheme.typography.labelMedium,
            color = if (scanning) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 设备行：RSSI 信号格 + 展示名（未解析名置灰）+ 归一化 MAC；解析成功才可点选。 */
@Composable
private fun DeviceRow(
    device: BleSighting,
    displayName: String?,
    onUse: (String) -> Unit,
) {
    Card(
        onClick = { displayName?.let(onUse) },
        enabled = displayName != null,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SignalBars(device.rssi)
            Column(Modifier.weight(1f)) {
                Text(
                    displayName ?: "未识别名称（${device.advName}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (displayName != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${device.normalizedMac}（广播 ${device.broadcastMac}）",
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (displayName != null) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = "选择该设备",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** RSSI → 4 格信号（≥-55 / ≥-70 / ≥-85 / 其余）。 */
@Composable
private fun SignalBars(rssi: Int) {
    val count = when {
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        (1..4).forEach { i ->
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i <= count) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}
