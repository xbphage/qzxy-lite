package com.qzxy.lite.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.qzxy.lite.data.legal.DisclaimerStore

/**
 * 免责声明与免费开源声明弹窗。
 * [requireConfirmTyping] 为 true 时阻塞主流程，必须完整输入确认句方可继续。
 */
@Composable
fun DisclaimerDialog(
    requireConfirmTyping: Boolean = true,
    onAccept: () -> Unit,
    onDecline: () -> Unit = {},
) {
    var confirmInput by rememberSaveable { mutableStateOf("") }
    val trimmedInput = confirmInput.trim()
    val matches = !requireConfirmTyping || trimmedInput == DisclaimerStore.REQUIRED_CONFIRM_TEXT
    val showMismatch = requireConfirmTyping && confirmInput.isNotEmpty() && !matches

    AlertDialog(
        onDismissRequest = {
            if (!requireConfirmTyping) onAccept()
        },
        properties = DialogProperties(
            dismissOnBackPress = !requireConfirmTyping,
            dismissOnClickOutside = !requireConfirmTyping,
        ),
        title = {
            Text(
                "免责声明与使用须知",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "本软件为完全免费软件，谨防受骗！",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "本软件完全免费开源，无任何收费、付费解锁或代充服务。如您在任何渠道遇到收费售卖，请立即申请退款并举报！",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                DisclaimerClause(
                    "1. 免费开源声明",
                    "本软件（qzxy-lite）完全免费（github.com/xbphage/qzxy-lite），禁止任何商业倒卖；如遇收费请退款举报。",
                )
                DisclaimerClause(
                    "2. 非官方声明",
                    "本软件为第三方个人学习项目，不代表趣智校园官方立场。",
                )
                DisclaimerClause(
                    "3. 隐私说明",
                    "仅直连官方服务器，不额外获取或上传任何个人数据。",
                )
                DisclaimerClause(
                    "4. 功能限制",
                    "不保证稳定性，目前仅“洗澡”功能经过测试，不建议使用其他功能。实际扣费以官方为准。",
                )
                DisclaimerClause(
                    "5. 禁止传播",
                    "仅供个人学习，严禁在任何国内平台传播或分发。",
                )

                Text(
                    "继续使用即代表您同意以上内容并自愿承担相关风险；如有异议，请立即停止使用并卸载本软件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (requireConfirmTyping) {
                    Spacer(Modifier.size(2.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "请输入以下文字以确认：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                DisclaimerStore.REQUIRED_CONFIRM_TEXT,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = confirmInput,
                        onValueChange = { confirmInput = it },
                        label = { Text("确认声明") },
                        placeholder = { Text(DisclaimerStore.REQUIRED_CONFIRM_TEXT) },
                        isError = showMismatch,
                        supportingText = {
                            Text(
                                if (showMismatch) "请完整准确输入上方确认文字"
                                else "输完后“同意并继续”按钮将自动激活",
                            )
                        },
                        maxLines = 2,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        dismissButton = if (requireConfirmTyping) {
            {
                TextButton(onClick = onDecline) {
                    Text("不同意并退出")
                }
            }
        } else {
            null
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                enabled = matches,
            ) {
                Text(if (requireConfirmTyping) "同意并继续" else "我已知晓")
            }
        },
    )
}

@Composable
private fun DisclaimerClause(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
