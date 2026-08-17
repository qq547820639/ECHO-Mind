package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.BehaviorState

import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 74 §64/§65 + V3 §L/§M — Presence 渲染策略锚点：
 * 用户偏好（减少动画/动态程度/夜间模式）→ [resolveRenderPolicy]（PresenceRenderPolicy）；
 * reduceMotion → MotionPolicy.REDUCED 且 surface 保持基底；mapper flowSpeed 归零；
 * 快照重读节流边界不变。
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
    fun reduceMotionYieldsReducedPolicyAndZeroFlow() {
        val policy = resolveRenderPolicy(
            baseSurface = EchoSurface.WALLPAPER_VISUAL_ONLY,
            reduceMotion = true,
            motionLevelName = "DEFAULT",
            nightMode = false,
        )
        assertTrue("减少动画 → MotionPolicy.REDUCED", policy.reduceMotion)
        assertEquals(MotionPolicy.REDUCED, policy.motion)
        assertEquals("surface 保持基底（V3：REDUCED 不再是 surface）",
            EchoSurface.WALLPAPER_VISUAL_ONLY, policy.surface)
        // §64 冻结语义：减少动画 → flowSpeed 归零（无障碍硬契约）
        assertEquals(
            0f,
            EchoVisualMapper.map(state(), 13f, reduceMotion = policy.reduceMotion).flowSpeed,
            1e-6f,
        )
    }

    @Test
    fun baseSurfacePreservedWithoutReduceMotion() {
        val wallpaper = resolveRenderPolicy(EchoSurface.WALLPAPER_VISUAL_ONLY, false, "DEFAULT", false)
        assertEquals(EchoSurface.WALLPAPER_VISUAL_ONLY, wallpaper.surface)
        assertEquals(MotionPolicy.NORMAL, wallpaper.motion)
        val dream = resolveRenderPolicy(EchoSurface.DREAM_AMBIENT, false, "DEFAULT", false)
        assertEquals(EchoSurface.DREAM_AMBIENT, dream.surface)
    }

    @Test
    fun motionLevelAndNightModeMapThrough() {
        assertEquals(
            PresenceMotionLevel.QUIET,
            resolveRenderPolicy(EchoSurface.DREAM_AMBIENT, false, "QUIET", false).motionLevel,
        )
        assertEquals(
            PresenceMotionLevel.LIVELY,
            resolveRenderPolicy(EchoSurface.DREAM_AMBIENT, false, "LIVELY", false).motionLevel,
        )
        // 未知动态程度名 fail-closed 到 DEFAULT
        assertEquals(
            PresenceMotionLevel.DEFAULT,
            resolveRenderPolicy(EchoSurface.DREAM_AMBIENT, false, "UNKNOWN", false).motionLevel,
        )
        assertEquals(
            PresenceMotionLevel.DEFAULT,
            resolveRenderPolicy(EchoSurface.DREAM_AMBIENT, false, "", false).motionLevel,
        )
        assertTrue(resolveRenderPolicy(EchoSurface.DREAM_AMBIENT, false, "DEFAULT", true).nightMode)
    }

    @Test
    fun quietMapsToQuietMotionAndSlowerFlow() {
        val policy = resolveRenderPolicy(EchoSurface.WALLPAPER_VISUAL_ONLY, false, "QUIET", false)
        assertEquals(MotionPolicy.QUIET, policy.motion)
        assertFalse(policy.reduceMotion)
        val base = EchoVisualMapper.map(state(), 13f)
        val quiet = EchoVisualMapper.map(state(), 13f, motionLevel = policy.motionLevel)
        assertTrue("QUIET 应比 DEFAULT 更慢", quiet.flowSpeed < base.flowSpeed)
    }

    @Test
    fun livelyMapsToNormalMotion() {
        val policy = resolveRenderPolicy(EchoSurface.DREAM_AMBIENT, false, "LIVELY", false)
        assertEquals(MotionPolicy.NORMAL, policy.motion)
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
