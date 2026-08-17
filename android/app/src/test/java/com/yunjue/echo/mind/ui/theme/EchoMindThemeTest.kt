package com.yunjue.echo.mind.ui.theme

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * V3 §Z 主题回归（dark-first）：
 * - 背景近黑：WCAG 相对亮度 < 0.2；
 * - 单一强调色：primary ≠ error（error 专属真实错误/安全场景）；
 * - 正文对比度：onSurface vs background ≥ 4.5:1（WCAG AA，测试内计算）；
 * - smoke：EchoMindTheme { Text } 渲染不崩溃。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoMindThemeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun linear(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun backgroundIsNearBlack() {
        assertTrue(luminance(EchoMindDarkColors.background) < 0.2)
    }

    @Test
    fun primaryIsTheSingleAccentDistinctFromError() {
        assertTrue(EchoMindDarkColors.primary != EchoMindDarkColors.error)
    }

    @Test
    fun onSurfaceMeetsAAContrastAgainstBackground() {
        assertTrue(contrastRatio(EchoMindDarkColors.onSurface, EchoMindDarkColors.background) >= 4.5)
    }

    @Test
    fun themeSmokeRenderDoesNotCrash() {
        compose.setContent { EchoMindTheme { Text("theme smoke") } }
        compose.onNodeWithText("theme smoke").assertExists()
    }
}
