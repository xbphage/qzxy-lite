package com.qzxy.lite.ui.water

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qzxy.lite.tmall.SavedWaterDevice
import com.qzxy.lite.tmall.WaterDeviceSortMode
import com.qzxy.lite.ui.common.lazyVerticalScrollbar

@Composable
internal fun WaterAddDialog(
    onScan: () -> Unit,
    onManualInput: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("添加饮水机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text("扫描饮水机二维码")
                }
                OutlinedButton(onClick = onManualInput, modifier = Modifier.fillMaxWidth()) {
                    Text("手动输入设备号 / 链接")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("取消") }
        },
    )
}

@Composable
internal fun WaterManualDialog(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onAdd: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("手动输入设备号") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("设备号 / 二维码链接") },
                placeholder = { Text("如 630102040 或 tmxy...") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onAdd, enabled = enabled && value.isNotBlank()) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        },
    )
}

@Composable
internal fun WaterSettingsDialog(
    mode: WaterDeviceSortMode,
    account: String,
    loggedIn: Boolean,
    onSort: (WaterDeviceSortMode) -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("饮水设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备排序方式", style = MaterialTheme.typography.labelLarge)
                WaterSortOption("按最近使用排序", mode == WaterDeviceSortMode.RECENT) {
                    onSort(WaterDeviceSortMode.RECENT)
                }
                WaterSortOption("自定义顺序", mode == WaterDeviceSortMode.CUSTOM) {
                    onSort(WaterDeviceSortMode.CUSTOM)
                }
                if (mode == WaterDeviceSortMode.CUSTOM) {
                    Text(
                        "可在「管理设备」中上下调整顺序。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loggedIn) {
                    if (account.isNotBlank()) {
                        Text(
                            "当前账号：$account",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onLogout) {
                        Text("退出天猫校园登录", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("完成") }
        },
    )
}

@Composable
private fun WaterSortOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun WaterManageDialog(
    devices: List<SavedWaterDevice>,
    custom: Boolean,
    onSave: (List<SavedWaterDevice>) -> Unit,
    onClose: () -> Unit,
) {
    var draftIds by rememberSaveable { mutableStateOf(ArrayList(devices.map { it.resNo })) }
    val byId = devices.associateBy { it.resNo }
    val draft = draftIds.mapNotNull(byId::get)
    val listState = rememberLazyListState()

    fun move(from: Int, to: Int) {
        draftIds = ArrayList(draft.map { it.resNo }).apply { add(to, removeAt(from)) }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("管理饮水机") },
        text = {
            if (draft.isEmpty()) {
                Text("暂无已保存设备", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .lazyVerticalScrollbar(listState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(draft, key = { _, d -> d.resNo }) { index, device ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                if (device.location.isNotBlank()) {
                                    Text(
                                        device.location,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (custom) {
                                IconButton(
                                    onClick = { move(index, index - 1) },
                                    enabled = index > 0,
                                ) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "上移")
                                }
                                IconButton(
                                    onClick = { move(index, index + 1) },
                                    enabled = index < draft.lastIndex,
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "下移")
                                }
                            }
                            IconButton(
                                onClick = { draftIds = ArrayList(draftIds.filterNot { it == device.resNo }) },
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "移除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        },
    )
}
