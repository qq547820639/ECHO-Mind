package com.yunjue.echo.mind.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V3 §54 — Awakening 固定时间线测试（2200ms 不变；关键帧锚定）。 */
class AwakeningTimelineTest {

    @Test
    fun totalDurationUnchanged() {
        assertEquals(2200L, AwakeningTimeline.TOTAL_MS)
        assertEquals(AWAKENING_DURATION_MS, AwakeningTimeline.TOTAL_MS)
    }

    @Test
    fun keyframesMatchSpec() {
        // halo 0 → .55 于 120–520ms
        assertEquals(0f, AwakeningTimeline.at(100).haloScale, 1e-4f)
        assertEquals(1f, AwakeningTimeline.at(520).haloScale, 1e-4f)
        // filament richness min → target 于 300–900ms
        assertEquals(0.30f, AwakeningTimeline.at(300).detailScale, 1e-4f)
        assertEquals(1.0f, AwakeningTimeline.at(900).detailScale, 1e-4f)
        // outer ring 0 → 1 于 520–1180ms
        assertEquals(0f, AwakeningTimeline.at(520).ringAlphaScale, 1e-4f)
        assertEquals(1f, AwakeningTimeline.at(1180).ringAlphaScale, 1e-4f)
        // first breath .985 → 1.018 → 1.000 于 850–1550ms
        assertEquals(0.985f, AwakeningTimeline.at(850).breathScale, 1e-4f)
        assertEquals(1.018f, AwakeningTimeline.at(1150).breathScale, 1e-4f)
        assertEquals(1.000f, AwakeningTimeline.at(1550).breathScale, 1e-4f)
        // headline fade 1350–1650ms
        assertEquals(0f, AwakeningTimeline.at(1350).headlineAlpha, 1e-4f)
        assertEquals(1f, AwakeningTimeline.at(1650).headlineAlpha, 1e-4f)
        // 2200ms 进入 Home
        assertFalse(AwakeningTimeline.at(2199).finished)
        assertTrue(AwakeningTimeline.at(2200).finished)
    }

    @Test
    fun awakeningEndFrameMatchesHomeStart() {
        // §54 连续性：末帧 breath=1.000 / ring=1 / detail=1 / halo=1 —— 与 Home 首帧同源同 identity
        val end = AwakeningTimeline.at(2200)
        assertEquals(1.000f, end.breathScale, 1e-4f)
        assertEquals(1f, end.ringAlphaScale, 1e-4f)
        assertEquals(1f, end.detailScale, 1e-4f)
        assertEquals(1f, end.haloScale, 1e-4f)
    }
}
