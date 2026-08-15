package com.yunjue.echo.mind.journey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 16 §85 — Visual Memory River：
 * 平稳/密集/漂移/特殊/转变分类、合并、空输入、视觉距离。
 */
class JourneyRiverTest {

    @Test
    fun emptyDaysProduceNoRiver() {
        assertTrue(buildVisualMemoryRiver(emptyList()).isEmpty())
    }

    @Test
    fun singleDayIsStableSegment() {
        val river = buildVisualMemoryRiver(listOf(journeyDay("2026-08-14")))
        assertEquals(1, river.size)
        assertEquals(RiverSegmentKind.STABLE, river.first().kind)
        assertEquals("2026-08-14", river.first().startDate)
        assertEquals("2026-08-14", river.first().endDate)
    }

    @Test
    fun identicalDaysMergeIntoOneStableSegment() {
        val days = (1..14).map { i ->
            journeyDay("2026-08-${i.toString().padStart(2, '0')}")
        }
        val river = buildVisualMemoryRiver(days, chunkDays = 7)
        assertEquals(1, river.size)
        assertEquals(RiverSegmentKind.STABLE, river.first().kind)
        assertEquals("2026-08-01", river.first().startDate)
        assertEquals("2026-08-14", river.first().endDate)
        assertTrue(river.first().intensity >= 0.9f)
    }

    @Test
    fun denseActivityClassifiesAsDense() {
        val days = (1..7).map { i ->
            journeyDay(
                "2026-08-${i.toString().padStart(2, '0')}",
                params = visualParams { flowSpeed = 0.8f; particleDensity = 0.8f },
            )
        }
        val river = buildVisualMemoryRiver(days, chunkDays = 7)
        assertEquals(RiverSegmentKind.DENSE, river.first().kind)
        assertEquals("密集时期", river.first().label)
    }

    @Test
    fun contextExceptionDateClassifiesAsSpecial() {
        val days = (1..7).map { i -> journeyDay("2026-08-${i.toString().padStart(2, '0')}") }
        val river = buildVisualMemoryRiver(
            days,
            contextExceptions = mapOf("2026-08-04" to "travel"),
            chunkDays = 7,
        )
        assertEquals(RiverSegmentKind.SPECIAL, river.first().kind)
        assertEquals("特殊阶段", river.first().label)
    }

    @Test
    fun bigStepChangeClassifiesAsTransition() {
        val stable = (1..7).map { i ->
            journeyDay("2026-08-${i.toString().padStart(2, '0')}", params = visualParams {})
        }
        val changed = (8..14).map { i ->
            journeyDay(
                "2026-08-${i.toString().padStart(2, '0')}",
                params = visualParams { flowSpeed = 0.9f; coherence = 0.9f; turbulence = 0.9f; particleDensity = 0.9f; accentIntensity = 0.9f; structureComplexity = 0.9f },
            )
        }
        val river = buildVisualMemoryRiver(stable + changed, chunkDays = 7)
        assertTrue(
            "应存在 TRANSITION 段",
            river.any { it.kind == RiverSegmentKind.TRANSITION }
        )
    }

    @Test
    fun gradualMovementClassifiesAsDrift() {
        val chunk1 = (1..7).map { i ->
            journeyDay(
                "2026-08-${i.toString().padStart(2, '0')}",
                params = visualParams { flowSpeed = 0.3f; coherence = 0.5f },
            )
        }
        val chunk2 = (8..14).map { i ->
            journeyDay(
                "2026-08-${i.toString().padStart(2, '0')}",
                params = visualParams { flowSpeed = 0.45f; coherence = 0.65f },
            )
        }
        val chunk3 = (15..21).map { i ->
            journeyDay(
                "2026-08-${i.toString().padStart(2, '0')}",
                params = visualParams { flowSpeed = 0.6f; coherence = 0.8f },
            )
        }
        val river = buildVisualMemoryRiver(chunk1 + chunk2 + chunk3, chunkDays = 7)
        assertTrue(
            "应存在 DRIFT 段（实际：${river.map { it.kind }}）",
            river.any { it.kind == RiverSegmentKind.DRIFT }
        )
        assertEquals("节律漂移", river.first { it.kind == RiverSegmentKind.DRIFT }.label)
    }

    @Test
    fun visualDistanceIsZeroForIdenticalAndGrowsWithDifference() {
        assertEquals(0f, visualDistance(visualParams {}, visualParams {}), 1e-6f)
        val far = visualDistance(
            visualParams { flowSpeed = 0f; coherence = 0f },
            visualParams { flowSpeed = 1f; coherence = 1f },
        )
        assertTrue(far > RIVER_TRANSITION_DISTANCE)
    }

    @Test
    fun chunkingIsDeterministic() {
        val days = (1..21).map { i ->
            journeyDay("2026-08-${i.toString().padStart(2, '0')}")
        }
        val a = buildVisualMemoryRiver(days, chunkDays = 7)
        val b = buildVisualMemoryRiver(days, chunkDays = 7)
        assertEquals(a, b)
    }

    @Test
    fun segmentKindLabelLocatesContainingSegment() {
        // ERA 31 R27：主河流聚合格标注所属河段种类（一条河流 = 时间线 + 故事）
        val segments = listOf(
            JourneyRiverSegment("2026-08-01", "2026-08-07", RiverSegmentKind.STABLE, 0.9f, null, "平稳时期"),
            JourneyRiverSegment("2026-08-08", "2026-08-14", RiverSegmentKind.DRIFT, 0.4f, null, "节律漂移"),
        )
        assertEquals("平稳时期", journeySegmentKindLabel("2026-08-01", segments))
        assertEquals("平稳时期", journeySegmentKindLabel("2026-08-05", segments))
        assertEquals("节律漂移", journeySegmentKindLabel("2026-08-14", segments))
        assertEquals(null, journeySegmentKindLabel("2026-07-31", segments))
        assertEquals(null, journeySegmentKindLabel("", emptyList()))
    }
}
