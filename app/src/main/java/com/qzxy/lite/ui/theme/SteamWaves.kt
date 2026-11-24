package com.qzxy.lite.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 蒸汽波纹：三条不同周期/振幅的正弦线，缓慢漂移。
 * 唯一的装饰母题：登录英雄区、空状态、余额面板共用；其余界面保持安静。
 */
@Composable
fun SteamWaves(
    modifier: Modifier = Modifier,
    color: Color = LocalQzColors.current.onDeep,
    baseAlpha: Float = 0.24f,
) {
    val phase: Float = if (rememberMotionAllowed()) {
        val transition = rememberInfiniteTransition(label = "steam")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
            label = "phase",
        )
        p
    } else {
        0f
    }

    Canvas(modifier) {
        // baseline 占高度比例 / 振幅占高度比例 / 全宽周期数
        val specs = listOf(
            Triple(0.30f, 0.014f, 2.4),
            Triple(0.54f, 0.022f, 1.6),
            Triple(0.76f, 0.017f, 3.1),
        )
        specs.forEachIndexed { i, (baseline, amp, cycles) ->
            val path = Path()
            var x = 0f
            while (x <= size.width) {
                val y = size.height * baseline + size.height * amp *
                    sin(2.0 * PI * cycles * x / size.width + phase + i * 2.1).toFloat()
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += size.width / 48f
            }
            drawPath(
                path,
                color.copy(alpha = baseAlpha * (1f - i * 0.22f)),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** 系统关闭动画（时长缩放=0）时所有循环动效静止。 */
@Composable
fun rememberMotionAllowed(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
}
