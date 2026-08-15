package com.yunjue.echo.mind.presence

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ERA 31 R13（§16 Battery Reality）——Wallpaper 自适应帧间隔策略锚点。
 *
 * 对应真实产品问题：可见期此前恒 60fps 连续渲染，静态/低变化阶段没有自动降帧，
 * 违反 §16「静态/低变化阶段自动降低 frame rate」目标（Battery impact 指标）。
 */
class WallpaperFrameIntervalPolicyTest {

    @Test
    fun transitionWindowUsesSmoothInterval() {
        assertEquals(TRANSITION_FRAME_INTERVAL_MS, wallpaperFrameIntervalMs(msSinceVisualChange = 0L, rippleActive = false))
        assertEquals(TRANSITION_FRAME_INTERVAL_MS, wallpaperFrameIntervalMs(msSinceVisualChange = 1_999L, rippleActive = false))
    }

    @Test
    fun idlePhaseDropsToQuarterFps() {
        assertEquals(IDLE_FRAME_INTERVAL_MS, wallpaperFrameIntervalMs(msSinceVisualChange = 2_000L, rippleActive = false))
        assertEquals(IDLE_FRAME_INTERVAL_MS, wallpaperFrameIntervalMs(msSinceVisualChange = 60_000L, rippleActive = false))
        // 静置期帧率必须显著低于过渡期（§16 降帧语义，至少 4 倍）
        assert(IDLE_FRAME_INTERVAL_MS >= TRANSITION_FRAME_INTERVAL_MS * 4)
    }

    @Test
    fun activeRippleStaysSmoothRegardlessOfIdleTime() {
        assertEquals(TRANSITION_FRAME_INTERVAL_MS, wallpaperFrameIntervalMs(msSinceVisualChange = 60_000L, rippleActive = true))
    }
}
