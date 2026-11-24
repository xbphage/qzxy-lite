package com.qzxy.lite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「水房」主题（2026-11-13 视觉改版）：
 * - 冷调水雾底 + 青墨深潭 + 泉青主操作；汤珊瑚只表达“热水/使用中”（温度语义，不做装饰色）；
 * - 金额/订单号/SN 一律走表数字（fontFeatureSettings = "tnum"），呈现水表质感；
 * - shapes.small = 胶囊：按钮全圆角；文本框显式传 shapes.medium 避免被胶囊吞掉。
 */

/** 品牌扩展色：Material 三色槽装不下的语义色。 */
@Immutable
data class QzColors(
    val deep: Color,      // 深潭底（登录英雄区 / 余额面板）
    val onDeep: Color,    // 深潭上的文字
    val hot: Color,       // 汤珊瑚：供水/使用中
    val wave: Color,      // 蒸汽波纹线的基色（登录英雄区用 onDeep，组件内覆盖）
)

val LocalQzColors = staticCompositionLocalOf {
    QzColors(
        deep = Color(0xFF122E36),
        onDeep = Color(0xFFEAF5F6),
        hot = Color(0xFFE96F4C),
        wave = Color(0x40FFFFFF),
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F7E8C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD2ECEF),
    onPrimaryContainer = Color(0xFF04333B),
    secondary = Color(0xFF33565E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E9EC),
    onSecondaryContainer = Color(0xFF0B2F36),
    tertiary = Color(0xFFAA4026),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDACE),
    onTertiaryContainer = Color(0xFF3A0B00),
    background = Color(0xFFF0F5F5),
    onBackground = Color(0xFF14272C),
    surface = Color(0xFFFAFEFE),
    onSurface = Color(0xFF14272C),
    surfaceVariant = Color(0xFFDCE8E9),
    onSurfaceVariant = Color(0xFF415B62),
    outline = Color(0xFF718C93),
    outlineVariant = Color(0xFFC3D6D8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF55C6CF),
    onPrimary = Color(0xFF00333A),
    primaryContainer = Color(0xFF004C55),
    onPrimaryContainer = Color(0xFFB2ECF2),
    secondary = Color(0xFFA5CDD3),
    onSecondary = Color(0xFF06343B),
    secondaryContainer = Color(0xFF234B53),
    onSecondaryContainer = Color(0xFFD6E9EC),
    tertiary = Color(0xFFF2926B),
    onTertiary = Color(0xFF551F08),
    tertiaryContainer = Color(0xFF7A3118),
    onTertiaryContainer = Color(0xFFFFDACE),
    background = Color(0xFF0B1E25),
    onBackground = Color(0xFFDEEAEB),
    surface = Color(0xFF0F262E),
    onSurface = Color(0xFFDEEAEB),
    surfaceVariant = Color(0xFF14323B),
    onSurfaceVariant = Color(0xFFB2C7CB),
    outline = Color(0xFF6F9199),
    outlineVariant = Color(0xFF2A4952),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C2B24),
    onErrorContainer = Color(0xFFFFDAD5),
)

private val QzLight = QzColors(
    deep = Color(0xFF122E36),
    onDeep = Color(0xFFEAF5F6),
    hot = Color(0xFFE96F4C),
    wave = Color(0x40FFFFFF),
)

private val QzDark = QzColors(
    deep = Color(0xFF07161C),
    onDeep = Color(0xFFDEEAEB),
    hot = Color(0xFFF2926B),
    wave = Color(0x33B8E4E8),
)

/** 表数字：余额/订单号/SN 等仪表数字专用（正文中文不受影响）。 */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

private val QzTypography = Typography(
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 34.sp,
        fontFeatureSettings = "tnum",
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 26.sp,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.4.sp),
)

private val QzShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(100.dp),   // 按钮胶囊
    medium = RoundedCornerShape(16.dp),   // 卡片 / 文本框
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun QzxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalQzColors provides if (darkTheme) QzDark else QzLight) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = QzTypography,
            shapes = QzShapes,
            content = content,
        )
    }
}
