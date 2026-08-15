package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.BehaviorState

import com.yunjue.echo.mind.model.SensingRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 74 §64/§65 — Presence 表面功耗策略锚点：
 * 用户偏好（减少动画/动态程度/夜间模式）→ 表面配置；
 * REDUCED_MOTION/LOW_POWER 的功耗方向；快照重读节流边界。
 */
class PresenceSurfacePolicyTest {

    private fun state() = EchoPresenceState(
        sensingStatus = SensingRuntimeStatus.ACTIVE,
        maturity = EchoMaturity.KNOWN,
        rhythmState = RhythmState(activityLevel = 0.8f, rhythmDelta = 0f, coverage = 0.7f),
        behaviorState = BehaviorState(density = 0.7f, deviation = 0.2f),
        confidence = 0.7f,
        identityGenome = EchoIdentityGenome(seed = 42L),
    )

    @Test
    fun reduceMotionOverridesSurfaceToZeroFlow() {
        val config = resolveSurfaceConfig(
            baseSurface = SurfaceMode.HOME_WALLPAPER,
            reduceMotion = true,
            motionLevelName = "DEFAULT",
            nightMode = false,
        )
        assertEquals(SurfaceMode.REDUCED_MOTION, config.surface)
        // §64 冻结语义：减少动画 → flowSpeed 归零（无障碍硬契约）
        assertEquals(0f, computeVisualParameters(state(), 13f, config.surface).flowSpeed, 1e-6f)
    }

    @Test
    fun baseSurfacePreservedWithoutReduceMotion() {
        val wallpaper = resolveSurfaceConfig(SurfaceMode.HOME_WALLPAPER, false, "DEFAULT", false)
        assertEquals(SurfaceMode.HOME_WALLPAPER, wallpaper.surface)
        val dream = resolveSurfaceConfig(SurfaceMode.DREAM, false, "DEFAULT", false)
        assertEquals(SurfaceMode.DREAM, dream.surface)
    }

    @Test
    fun motionLevelAndNightModeMapThrough() {
        assertEquals(
            PresenceMotionLevel.QUIET,
            resolveSurfaceConfig(SurfaceMode.DREAM, false, "QUIET", false).motionLevel
        )
        assertEquals(
            PresenceMotionLevel.LIVELY,
            resolveSurfaceConfig(SurfaceMode.DREAM, false, "LIVELY", false).motionLevel
        )
        assertEquals(
            PresenceMotionLevel.DEFAULT,
            resolveSurfaceConfig(SurfaceMode.DREAM, false, "UNKNOWN", false).motionLevel
        )
        assertTrue(resolveSurfaceConfig(SurfaceMode.DREAM, false, "DEFAULT", true).nightMode)
    }

    @Test
    fun quietAndLowPowerReduceFlowVersusDefault() {
        val base = computeVisualParameters(state(), 13f, SurfaceMode.HOME_WALLPAPER)
        val quiet = computeVisualParameters(state(), 13f, SurfaceMode.HOME_WALLPAPER, PresenceMotionLevel.QUIET)
        val lowPower = computeVisualParameters(state(), 13f, SurfaceMode.LOW_POWER)
        assertTrue("QUIET 应比 DEFAULT 更静", quiet.flowSpeed < base.flowSpeed)
        assertTrue("LOW_POWER 应比 HOME_WALLPAPER 更静", lowPower.flowSpeed < base.flowSpeed)
    }

    @Test
    fun snapshotRefreshThrottleBoundaries() {
        // 首帧（lastRead=0）：真实时钟远大于间隔 → 立即读
        assertTrue(shouldRefreshSnapshot(0L, 5_000L))
        // 间隔内：不读
        assertFalse(shouldRefreshSnapshot(1_000L, 1_999L))
        // 恰好到间隔：读
        assertTrue(shouldRefreshSnapshot(1_000L, 2_000L))
        // 自定义间隔
        assertTrue(shouldRefreshSnapshot(0L, 15_000L, intervalMs = 15_000L))
    }
}
