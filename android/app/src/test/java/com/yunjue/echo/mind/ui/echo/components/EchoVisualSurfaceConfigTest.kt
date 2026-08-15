package com.yunjue.echo.mind.ui.echo.components

import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ERA 38 — echoVisualSurfaceConfig 纯函数矩阵：
 * 动效等级映射（QUIET/LIVELY/未知回退 DEFAULT）+ 减少动画表面 + 夜间模式透传。
 */
class EchoVisualSurfaceConfigTest {

    @Test
    fun quietPrefMapsToQuiet() {
        val config = echoVisualSurfaceConfig(motionLevelPref = "QUIET", reduceMotion = false, nightMode = false)
        assertEquals(PresenceMotionLevel.QUIET, config.motionLevel)
        assertEquals(SurfaceMode.APP, config.surface)
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
    fun reduceMotionSelectsReducedMotionSurface() {
        val config = echoVisualSurfaceConfig(motionLevelPref = "DEFAULT", reduceMotion = true, nightMode = false)
        assertEquals(SurfaceMode.REDUCED_MOTION, config.surface)
    }

    @Test
    fun nightModePassedThrough() {
        val config = echoVisualSurfaceConfig(motionLevelPref = "DEFAULT", reduceMotion = false, nightMode = true)
        assertEquals(true, config.nightMode)
    }

    @Test
    fun appConfigDelegatesToSharedSurfaceResolver() {
        // ERA 75：APP 与 Wallpaper/Dream 共用唯一映射真值（resolveSurfaceConfig）
        for (pref in listOf("QUIET", "LIVELY", "DEFAULT", "UNKNOWN")) {
            for (reduce in listOf(false, true)) {
                val app = echoVisualSurfaceConfig(pref, reduce, nightMode = true)
                val shared = com.yunjue.echo.mind.presence.resolveSurfaceConfig(
                    SurfaceMode.APP, reduce, pref, nightMode = true
                )
                assertEquals(shared.motionLevel, app.motionLevel)
                assertEquals(shared.surface, app.surface)
                assertEquals(shared.nightMode, app.nightMode)
            }
        }
    }
}
