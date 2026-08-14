package com.yunjue.echo.mind.journey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 16 §81/§82 — 五个尺度，每层都有 Visual/Facts/Patterns/Exceptions/Narrative/Evidence。
 */
class JourneyLayerTest {

    @Test
    fun everyScaleAssemblesSixLayerElements() {
        val days = listOf(
            journeyDay("2026-08-12", dimensionValues = mapOf("RHYTHM" to "SIMILAR")),
            journeyDay("2026-08-13", dimensionValues = mapOf("RHYTHM" to "SIMILAR")),
            journeyDay("2026-08-14", dimensionValues = mapOf("RHYTHM" to "LATER")),
        )
        JourneyScale.entries.forEach { scale ->
            val layer = assembleJourneyLayer(
                scale = scale,
                days = days,
                contextExceptions = mapOf("2026-08-13" to "travel"),
                narrative = "窗口叙事",
            )
            assertEquals(scale, layer.scale)
            assertNotNull(layer.visual)          // Visual
            assertTrue(layer.facts.isNotEmpty()) // Facts
            assertTrue(layer.patterns.isNotEmpty()) // Patterns（2/3 天 RHYTHM 相似）
            assertTrue(layer.exceptions.isNotEmpty()) // Exceptions
            assertEquals("窗口叙事", layer.narrative) // Narrative
            assertEquals(3, layer.evidenceIds.size)   // Evidence
        }
    }

    @Test
    fun patternsCountDominantDimensionValues() {
        val days = listOf(
            journeyDay("2026-08-12", dimensionValues = mapOf("RHYTHM" to "SIMILAR")),
            journeyDay("2026-08-13", dimensionValues = mapOf("RHYTHM" to "SIMILAR")),
            journeyDay("2026-08-14", dimensionValues = mapOf("RHYTHM" to "LATER")),
        )
        val patterns = deriveWindowPatterns(days)
        assertTrue(patterns.any { it.contains("3 天中有 2 天 RHYTHM 相似") })
    }

    @Test
    fun singleDayHasNoPatterns() {
        assertTrue(deriveWindowPatterns(listOf(journeyDay("2026-08-14"))).isEmpty())
    }

    @Test
    fun evidenceIdsOnlyForDaysWithVisualParams() {
        val days = listOf(
            journeyDay("2026-08-12"),
            journeyDay("2026-08-13", params = null),
            journeyDay("2026-08-14"),
        )
        assertEquals(
            listOf("portrait:2026-08-12", "portrait:2026-08-14"),
            journeyEvidenceIds(days)
        )
    }

    @Test
    fun exceptionsOnlyForDatesInsideWindow() {
        val layer = assembleJourneyLayer(
            scale = JourneyScale.WEEK,
            days = listOf(journeyDay("2026-08-12"), journeyDay("2026-08-13")),
            contextExceptions = mapOf(
                "2026-08-13" to "travel",
                "2025-01-01" to "exam",
            ),
            narrative = null,
        )
        assertEquals(1, layer.exceptions.size)
        assertTrue(layer.exceptions.first().contains("2026-08-13"))
        assertTrue(layer.exceptions.first().contains("旅行"))
    }

    @Test
    fun emptyWindowAssemblesHonestlyEmptyLayer() {
        val layer = assembleJourneyLayer(
            scale = JourneyScale.YEAR,
            days = emptyList(),
            contextExceptions = emptyMap(),
            narrative = null,
        )
        assertEquals(null, layer.visual)
        assertTrue(layer.facts.isEmpty())
        assertTrue(layer.patterns.isEmpty())
        assertTrue(layer.exceptions.isEmpty())
        assertTrue(layer.evidenceIds.isEmpty())
    }

    @Test
    fun layerAssemblyIsDeterministic() {
        val days = listOf(journeyDay("2026-08-12"), journeyDay("2026-08-13"))
        val a = assembleJourneyLayer(JourneyScale.WEEK, days, emptyMap(), "n")
        val b = assembleJourneyLayer(JourneyScale.WEEK, days, emptyMap(), "n")
        assertEquals(a, b)
    }
}
