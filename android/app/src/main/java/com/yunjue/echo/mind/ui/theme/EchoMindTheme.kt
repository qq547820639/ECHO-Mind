package com.yunjue.echo.mind.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * V3 §Z：ECHO Mind 暗色主题 —— 近黑背景 + 单一蓝紫强调色（8F7CF0）。
 *
 * 原则：ONLY ECHO GLOWS —— 有机体（ECHO 视觉）是唯一发光元素；主题本身克制：
 * 无发光按钮/chip/导航/卡片，形状沿用 M3 默认。error 仅保留给真实错误/安全场景
 * （M3 默认值），outline 为暗灰。
 */
internal val EchoMindDarkColors = darkColorScheme(
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFE6E7EB),
    surface = Color(0xFF14161B),
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF1C1F26),
    onSurfaceVariant = Color(0xFF9AA0AB),
    primary = Color(0xFF8F7CF0),
    onPrimary = Color(0xFF14121F),
    primaryContainer = Color(0xFF2A2740),
    onPrimaryContainer = Color(0xFFCFC8FF),
    outline = Color(0xFF6F7580),
    outlineVariant = Color(0xFF2A2E37),
)

/** App 级唯一主题入口：dark-first；排版沿用 M3 默认。 */
@Composable
fun EchoMindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EchoMindDarkColors,
        content = content,
    )
}
