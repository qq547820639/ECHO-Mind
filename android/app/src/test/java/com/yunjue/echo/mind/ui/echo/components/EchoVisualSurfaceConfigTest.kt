package com.yunjue.echo.mind.ui.echo.components

import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.visual.surface.EchoSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 38 — echoVisualSurfaceConfig 纯函数矩阵：
 * 动效等级映射（QUIET/LIVELY/未知回退 DEFAULT）+ 减少动画 + 夜间模式透传。
 * V3 §L/§M：SurfaceMode 已删除——config 携带 reduceMotion/motionLevel/nightMode，
 * 由 resolveRenderPolicy 统一解析（APP 基底 = APP_PRIVATE）。
 */
class EchoVisualSurfaceConfigTest {

    @Test
    fun quietPrefMapsToQuiet() {
        val config = echoVisualSurfaceConfig(motionLevelPref = "QUIET", reduceMotion = false, nightMode = false)
        assertEquals(PresenceMotionLevel.QUIET, config.motionLevel)
        assertFalse(config.reduceMotion)
    }

    @Test
    fun livelyPrefMapsToLively() {
        val config = echoVisualSurfaceConfig(motionLevelPref = "LIVELY", reduceMotion = false, nightMode = false)
        assertEquals(PresenceMotionLevel.LIVELY, config.motionLevel)
    }

    @Test
    fun unknownPrefFallsBackToDefault() {
        val config = echoVisualSurfaceConfig(motionLevelPref = "EXTREME", reduceMotion = false, nightMode = false)
        assertEquals(PresenceMotionLevel.DEFAULT, config.motionLevel)
    }

    @Test
    fun reduceMotionPassedThrough() {
        val config = echoVisualSurfaceConfig(motionLevelPref = "DEFAULT", reduceMotion = true, nightMode = false)
        assertTrue("减少动画 → config.reduceMotion（渲染侧 MotionPolicy.REDUCED）", config.reduceMotion)
    }

    @Test
    fun nightModePassedThrough() {
        val config = echoVisualSurfaceConfig(motionLevelPref = "DEFAULT", reduceMotion = false, nightMode = true)
        assertEquals(true, config.nightMode)
    }

    @Test
    fun appConfigDelegatesToSharedRenderPolicyResolver() {
        // ERA 75：APP 与 Wallpaper/Dream 共用唯一映射真值（resolveRenderPolicy）
        for (pref in listOf("QUIET", "LIVELY", "DEFAULT", "UNKNOWN")) {
            for (reduce in listOf(false, true)) {
                val app = echoVisualSurfaceConfig(pref, reduce, nightMode = true)
                val shared = com.yunjue.echo.mind.presence.resolveRenderPolicy(
                    EchoSurface.APP_PRIVATE, reduce, pref, nightMode = true
                )
                assertEquals(shared.motionLevel, app.motionLevel)
                assertEquals(shared.reduceMotion, app.reduceMotion)
                assertEquals(shared.nightMode, app.nightMode)
                assertEquals(EchoSurface.APP_PRIVATE, shared.surface)
            }
        }
    }
}
