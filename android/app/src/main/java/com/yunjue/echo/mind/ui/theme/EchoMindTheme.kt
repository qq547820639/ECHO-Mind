package com.yunjue.echo.mind.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * V3 §Z：ECHO Mind 暗色主题 —— deep navy 近黑背景 + 单一蓝紫强调色（8F7CF0）。
 *
 * 原则：ONLY ECHO GLOWS —— 有机体（ECHO 视觉）是唯一发光元素；主题本身克制：
 * 无发光按钮/chip/导航/卡片，形状沿用 M3 默认。error 仅保留给真实错误/安全场景
 * （M3 默认值），outline 为暗灰。
 *
 * Organism Visual Breakthrough §43：背景从旧中性灰黑 #0E0F12 逐步靠近视觉宪法的
 * deep navy / OLED black（#050A18 → #02040C 族）——与 organism ambient 场同族，
 * 大黑暗负空间里只有 ECHO 在发光。对比度：onBackground/onSurface 保持高对比，
 * 可访问性不受影响（文字色未动，只有底色更暗）。
 */
internal val EchoMindDarkColors = darkColorScheme(
    background = Color(0xFF050A18),
    onBackground = Color(0xFFE6E7EB),
    surface = Color(0xFF0A1020),
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF141A2C),
    onSurfaceVariant = Color(0xFF9AA0AB),
    primary = Color(0xFF8F7CF0),
    onPrimary = Color(0xFF14121F),
    primaryContainer = Color(0xFF2A2740),
    onPrimaryContainer = Color(0xFFCFC8FF),
    outline = Color(0xFF6F7580),
    outlineVariant = Color(0xFF242A3C),
)

/** App 级唯一主题入口：dark-first；排版沿用 M3 默认。 */
@Composable
fun EchoMindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EchoMindDarkColors,
        content = content,
    )
}
