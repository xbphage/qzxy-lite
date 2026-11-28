package com.qzxy.lite.ui.bath

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qzxy.lite.appContainer
import com.qzxy.lite.data.bath.BathPhase
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.theme.LocalQzColors
import com.qzxy.lite.ui.theme.TabularNumbers
import com.qzxy.lite.ui.theme.rememberMotionAllowed

/**
 * 洗澡控制页（2026-11-13 视觉改版）：一台设备，一个阀。
 * 大圆阀是全 App 唯一的响亮元素：空闲=泉青开阀，供水=汤珊瑚关阀，完成=描边返回。
 * 轮询/防重复（307）/关阀确认逻辑与改版前一致（BathViewModel 未动）。
 */
@Composable
fun BathScreen(
    sn: String,
    deviceSn: String,
    deviceName: String,
    deviceType: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: BathViewModel = viewModel {
        BathViewModel(
            context.appContainer.bathRepository,
            context.appContainer.bathSessionStore,
            context.appContainer.walletRepository,
        )
    }
    val state by vm.state.collectAsState()
    // 路由约定：sn = snCode（下单主标识），deviceSn = deviceSncode（第二标识，可空）。
    LaunchedEffect(sn, deviceSn, deviceName) {
        vm.bindDevice(deviceName, sn.ifBlank { deviceSn }, deviceSn)
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
                Column(Modifier.weight(1f)) {
                    Text(
                        deviceName.ifBlank { "未命名设备" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "SN ${deviceSn.ifBlank { sn }} · $deviceType",
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    onClick = vm::toggleBleMode,
                    enabled = !state.busy && state.phase == BathPhase.IDLE,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = if (state.isBleMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(
                        if (state.isBleMode) "⚡ 蓝牙脱机模式" else "☁️ 远程网络模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.isBleMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ValveControl(
                    phase = state.phase,
                    busy = state.busy,
                    onOpen = vm::open,
                    onClose = vm::close,
                    onBack = onBack,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor(state),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                state.orderNo?.let { orderNo ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "订单 $orderNo",
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.error?.let { ErrorNotice(it) }
            }
        }
    }
}

/** 大圆阀直径与涟漪画布直径（涟漪溢出绘制，不占布局）。 */
private val ValveSize = 190.dp
private val RippleSize = 300.dp

/** 大圆阀：按阶段换色/换动作；供水时向外扩涟漪，动画尊重系统时长缩放。 */
@Composable
private fun ValveControl(
    phase: BathPhase,
    busy: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val qz = LocalQzColors.current
    val active = phase == BathPhase.SHOWERING
    val churning = phase == BathPhase.OPENING || phase == BathPhase.CLOSING
    val rippleColor = if (active) qz.hot else MaterialTheme.colorScheme.primary
    val motionAllowed = rememberMotionAllowed()
    // 动画必须在每次组合时都创建：写在 if 分支里会随 phase 变化增删组合槽，
    // 属于 Compose 反模式（可能引发组合/动画状态错乱）。这里只在绘制阶段读取值。
    val ripple = rememberInfiniteTransition(label = "valve").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "ripple",
    )
    val rippleEnabled = (active || churning) && motionAllowed

    data class Face(
        val label: String,
        val container: Color,
        val onContainer: Color,
        val icon: @Composable (Modifier, Color) -> Unit,
    )

    val face = when (phase) {
        BathPhase.IDLE -> Face("开热水器", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) { m, c ->
            Icon(Icons.Rounded.WaterDrop, null, tint = c, modifier = m)
        }
        BathPhase.OPENING -> Face("正在开阀", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) { m, _ ->
            CircularProgressIndicator(modifier = m.size(34.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
        }
        BathPhase.SHOWERING -> Face("关阀结束", qz.hot, Color.White) { m, c ->
            Icon(Icons.Rounded.WaterDrop, null, tint = c, modifier = m)
        }
        BathPhase.CLOSING -> Face("正在关阀", qz.hot, Color.White) { m, _ ->
            CircularProgressIndicator(modifier = m.size(34.dp), strokeWidth = 3.dp, color = Color.White)
        }
        BathPhase.CLOSED -> Face("完成 · 返回", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) { m, c ->
            Icon(Icons.Rounded.CheckCircle, null, tint = c, modifier = m)
        }
    }
    val action: (() -> Unit)? = when (phase) {
        BathPhase.IDLE -> onOpen
        BathPhase.SHOWERING -> onClose
        BathPhase.CLOSED -> onBack
        else -> null
    }

    // 布局盒 = 圆的实际尺寸；涟漪画布更大但溢出绘制，不产生隐形空隙
    Box(Modifier.size(ValveSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(RippleSize)) {
            // 只在绘制阶段读动画值：涟漪靠重绘推进，不触发重组。
            val rippleFraction = if (rippleEnabled) ripple.value else 0f
            if (rippleFraction > 0f) {
                val circleRadius = ValveSize.toPx() / 2f
                // 两道错相涟漪，从圆边缘向外扩散
                listOf(rippleFraction, (rippleFraction + 0.5f) % 1f).forEach { frac ->
                    drawCircle(
                        color = rippleColor.copy(alpha = (1f - frac) * 0.30f),
                        radius = circleRadius * (1f + frac * 1.1f),
                    )
                }
            }
        }
        Surface(
            onClick = { action?.invoke() },
            enabled = !busy && action != null,
            shape = androidx.compose.foundation.shape.CircleShape,
            color = face.container,
            modifier = Modifier.size(ValveSize),
            shadowElevation = if (active) 10.dp else 4.dp,
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                face.icon(Modifier.size(44.dp), face.onContainer)
                Spacer(Modifier.height(10.dp))
                Text(face.label, style = MaterialTheme.typography.labelLarge, color = face.onContainer)
            }
        }
    }
}

/** 阀下方的一行状态说明。 */
private fun statusText(state: BathUiState): String = when (state.phase) {
    BathPhase.IDLE -> "待机 · 点按开阀"
    BathPhase.OPENING -> "正在开阀…"
    BathPhase.SHOWERING -> "供水进行中 · 点按关阀"
    BathPhase.CLOSING -> "正在关阀…"
    BathPhase.CLOSED -> "本次已结束，账单以服务端结算为准"
}

@Composable
private fun statusColor(state: BathUiState): Color = when (state.phase) {
    BathPhase.SHOWERING -> LocalQzColors.current.hot
    BathPhase.CLOSED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}
