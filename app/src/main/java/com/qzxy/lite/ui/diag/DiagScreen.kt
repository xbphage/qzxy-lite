package com.qzxy.lite.ui.diag

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qzxy.lite.core.DiagLog

/**
 * 诊断日志查看/导出弹窗（仅 diag 构建显示入口）。
 * 三个出口：复制到剪贴板 / 导出到下载目录 / 直接分享（微信发回开发者）。
 */
@Composable
fun DiagLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines by DiagLog.lines.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志（${lines.size} 条）") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text(
                    "包含每次请求参数与服务端响应（手机号/loginCode 已脱敏）。" +
                        "复现问题后导出或分享给开发者即可定位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(lines) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    if (lines.isEmpty()) {
                        item { Text("暂无日志", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val ok = DiagLog.exportToDownloads(context) != null
                    Toast.makeText(
                        context,
                        if (ok) "已导出到 Downloads 目录" else "导出失败",
                        Toast.LENGTH_LONG,
                    ).show()
                }) { Text("导出") }
                TextButton(onClick = { DiagLog.share(context) }) { Text("分享") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    DiagLog.clear(context)
                    Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                }) { Text("清空") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}