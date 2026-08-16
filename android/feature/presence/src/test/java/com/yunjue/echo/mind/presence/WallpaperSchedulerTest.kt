package com.yunjue.echo.mind.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V3 §69/§71/§72 — Wallpaper/Dream 调度与 burn-in 测试。 */
class WallpaperSchedulerTest {

    @Test
    fun invisibleMeansZeroFps() {
        assertNull(
            WallpaperScheduler.wallpaperFrameDelayMs(
                visible = false, reducedMotion = false, thermalSevereOrWorse = false,
                powerSave = false, msSinceTouch = 99_999, msSincePresenceUpdate = 99_999, night = false,
            ),
        )
    }

    @Test
    fun priorityTable() {
        fun fps(
            reduced: Boolean = false, thermal: Boolean = false, power: Boolean = false,
            touchMs: Long = 99_999, presenceMs: Long = 99_999, night: Boolean = false,
        ) = 1000L / WallpaperScheduler.wallpaperFrameDelayMs(
            visible = true, reducedMotion = reduced, thermalSevereOrWorse = thermal,
            powerSave = power, msSinceTouch = touchMs, msSincePresenceUpdate = presenceMs, night = night,
        )!!
        assertEquals(8L, fps(reduced = true))
        assertEquals(8L, fps(thermal = true))
        assertEquals(10L, fps(power = true))
        assertEquals(30L, fps(touchMs = 1_000))
        assertEquals(30L, fps(presenceMs = 2_000))
        assertEquals(12L, fps(night = true))
        assertEquals(18L, fps())
        // 优先级：reducedMotion 先于 touch
        assertEquals(8L, fps(reduced = true, touchMs = 500))
        // 30fps cap
        assertTrue(fps(touchMs = 0) <= 30L)
    }

    @Test
    fun dreamSchedule() {
        assertEquals(1000L / 24, WallpaperScheduler.dreamFrameDelayMs(1_000, reducedMotion = false))
        assertEquals(1000L / 15, WallpaperScheduler.dreamFrameDelayMs(10_000, reducedMotion = false))
        assertEquals(1000L / 8, WallpaperScheduler.dreamFrameDelayMs(10_000, reducedMotion = true))
        assertEquals(1000L / 8, WallpaperScheduler.dreamFrameDelayMs(1_000, reducedMotion = true))
    }

    @Test
    fun burnInOffsetsDeterministicAndBounded() {
        for (minute in 0 until 1440) {
            val (x, y) = WallpaperScheduler.burnInOffsetDp(minute)
            assertTrue("x in [-3,+3]", x in -3.01f..3.01f)
            assertTrue("y in [-2,+2]", y in -2.01f..2.01f)
        }
        assertEquals(
            WallpaperScheduler.burnInOffsetDp(42),
            WallpaperScheduler.burnInOffsetDp(42),
        )
    }
}
