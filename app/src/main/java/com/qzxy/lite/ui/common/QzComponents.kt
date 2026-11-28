package com.qzxy.lite.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qzxy.lite.ui.theme.SteamWaves

/**
 * 「水房」公共组件：状态点 / 提示条 / 可折叠原始响应卡 / 空状态。
 * 均为安静件——唯一的装饰性元素是空状态里的蒸汽波。
 */

/** 状态点：泉青=空闲，汤珊瑚=使用中。 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 错误提示条（errorContainer，不弹窗）。 */
@Composable
fun ErrorNotice(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 一般提示条（primaryContainer）。 */
@Composable
fun SoftNotice(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * 可折叠的原始响应卡：联调证据默认收起（不吵），需要核对字段时展开。
 * 用于钱包页的档位/规则/支付方式 JSON 等调试数据。
 */
@Composable
fun ExpandableRawCard(
    title: String,
    body: String?,
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (open) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (open) 180f else 0f),
            )
        }
        AnimatedVisibility(open) {
            val rawScroll = rememberScrollState()
            Column(Modifier.padding(horizontal = 14.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    Modifier
                        .padding(top = 10.dp, bottom = 8.dp)
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScrollbar(rawScroll)
                        .verticalScroll(rawScroll)
                        .padding(end = 8.dp),
                ) {
                    Text(
                        body ?: emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ScrollMoreHint(visible = rawScroll.canScrollForward)
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

/** 空状态：迷你蒸汽波 + 一句邀请，不摆架子。 */
@Composable
fun QzEmptyState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 130.dp, height = 56.dp)) {
            SteamWaves(
                Modifier.size(width = 130.dp, height = 56.dp),
                color = MaterialTheme.colorScheme.primary,
                baseAlpha = 0.45f,
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * 为 [verticalScroll] 容器绘制常驻滚动条（置于 `.verticalScroll(state)` 之前以对齐视口坐标）。
 * 只要内容超出视口（`state.maxValue > 0`），立即显示清晰滑块，避免用户误以为“已经到底”。
 */
fun Modifier.verticalScrollbar(
    state: ScrollState,
    thumbColor: Color = Color.Unspecified,
    width: Dp = 3.dp,
): Modifier = composed {
    val resolvedColor = if (thumbColor == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f)
    } else {
        thumbColor
    }
    drawWithContent {
        drawContent()
        val maxScroll = state.maxValue.toFloat()
        if (maxScroll > 0f && size.height > 0f) {
            val viewHeight = size.height
            val totalHeight = viewHeight + maxScroll
            val thumbHeight = (viewHeight * (viewHeight / totalHeight))
                .coerceIn(24.dp.toPx(), viewHeight * 0.85f)
            val thumbOffsetY = (state.value.toFloat() / maxScroll) * (viewHeight - thumbHeight).coerceAtLeast(0f)
            drawScrollbarThumb(resolvedColor, width.toPx(), thumbOffsetY, thumbHeight)
        }
    }
}

/** 为 [androidx.compose.foundation.lazy.LazyColumn] 绘制滚动条，提示下方仍有列表项。 */
fun Modifier.lazyVerticalScrollbar(
    state: LazyListState,
    thumbColor: Color = Color.Unspecified,
    width: Dp = 3.dp,
): Modifier = composed {
    val resolvedColor = if (thumbColor == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f)
    } else {
        thumbColor
    }
    drawWithContent {
        drawContent()
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo
        if (total > 0 && visible.isNotEmpty() && (state.canScrollForward || state.canScrollBackward)) {
            val viewHeight = size.height
            val avgSize = visible.sumOf { it.size }.toFloat() / visible.size
            val estimatedTotal = (avgSize * total).coerceAtLeast(viewHeight + 1f)
            val thumbHeight = (viewHeight * (viewHeight / estimatedTotal))
                .coerceIn(24.dp.toPx(), viewHeight * 0.85f)
            val first = visible.first()
            val scrolled = first.index * avgSize - first.offset
            val maxScroll = (estimatedTotal - viewHeight).coerceAtLeast(1f)
            val fraction = (scrolled / maxScroll).coerceIn(0f, 1f)
            val thumbOffsetY = fraction * (viewHeight - thumbHeight).coerceAtLeast(0f)
            drawScrollbarThumb(resolvedColor, width.toPx(), thumbOffsetY, thumbHeight)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrollbarThumb(
    color: Color,
    strokePx: Float,
    offsetY: Float,
    thumbHeight: Float,
) {
    val padEnd = 2.dp.toPx()
    val radius = CornerRadius(strokePx / 2f, strokePx / 2f)
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - strokePx - padEnd, offsetY),
        size = Size(strokePx, thumbHeight),
        cornerRadius = radius,
    )
}

/** 可滚动区域底部还有未展示内容时的紧凑提示条，滑到底后自动隐藏。 */
@Composable
fun ScrollMoreHint(
    visible: Boolean,
    text: String = "向下滑动查看更多",
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
