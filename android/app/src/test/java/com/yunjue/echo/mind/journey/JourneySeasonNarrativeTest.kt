package com.yunjue.echo.mind.journey
import com.yunjue.echo.mind.model.EchoLifeSeason

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 16 §87 — Life Season × Journey：
 * 变化维度检测 / 中性解释 / §57 禁医学心理结论词表 / 无变化 → 空解释。
 */
class JourneySeasonNarrativeTest {

    @Test
    fun changedAspectsDetectsDirectionAndThreshold() {
        val before = visualParams { flowSpeed = 0.3f; coherence = 0.5f }
        val after = visualParams { flowSpeed = 0.6f; coherence = 0.4f }
        val aspects = changedVisualAspects(before, after)
        assertTrue("flow 应变化", "flow" in aspects)
        assertFalse("coherence 变化低于阈值不应计入", "coherence" in aspects)
    }

    @Test
    fun noChangeProducesNoAspects() {
        val params = visualParams {}
        assertTrue(changedVisualAspects(params, params).isEmpty())
        assertTrue(changedVisualAspects(null, params).isEmpty())
        assertTrue(changedVisualAspects(params, null).isEmpty())
    }

    @Test
    fun lifeSeasonTrendsMapToNeutralExplanations() {
        val season = EchoLifeSeason(
            phaseIndex = 3,
            drift = 0.6f,
            rhythmShift = "later",
            screenFragmentation = "more_fragmented",
            activityVariability = "more_variable",
            mobilityTrend = "less_mobile",
            regularityTrend = "less_regular",
        )
        val lines = explainLifeSeasonVisual(season)
        assertTrue(lines.size >= 5)
        assertEquals(6, lines.size)
    }

    @Test
    fun stableSeasonHasNoExplanation() {
        val lines = explainLifeSeasonVisual(EchoLifeSeason(drift = 0f))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun explanationsNeverContainMedicalOrPsychologicalTerms() {
        // §57：禁止医学/心理结论词表（中英文）
        val banned = listOf(
            "depressed", "anxious", "burned out", "burnout",
            "抑郁", "焦虑", "燃尽", "疲惫", "精神", "心理", "疾病", "健康"
        )
        val season = EchoLifeSeason(
            drift = 0.8f,
            rhythmShift = "later",
            screenFragmentation = "more_fragmented",
            activityVariability = "more_variable",
            mobilityTrend = "less_mobile",
            regularityTrend = "more_regular",
        )
        val lines = explainLifeSeasonVisual(season)
        for (line in lines) {
            for (word in banned) {
                assertFalse("解释文案禁止出现「$word」：$line", line.contains(word))
            }
        }
    }

    @Test
    fun periodChangeProducesRangePrefixedLine() {
        val before = visualParams { flowSpeed = 0.2f; structureComplexity = 0.2f }
        val after = visualParams { flowSpeed = 0.8f; structureComplexity = 0.8f }
        val lines = explainPeriodChange(before, after, "2026-08-01", "2026-09-01")
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.first().startsWith("2026-08-01 → 2026-09-01"))
    }

    @Test
    fun periodChangeWithoutDifferenceIsEmpty() {
        val params = visualParams {}
        assertTrue(explainPeriodChange(params, params, "2026-08-01", "2026-09-01").isEmpty())
        assertTrue(explainPeriodChange(null, params, null, null).isEmpty())
    }

    @Test
    fun aspectExplanationsAreDeterministic() {
        assertEquals(explainVisualAspect("flow", 1), explainVisualAspect("flow", 1))
        assertEquals(explainVisualAspect("flow", -1), explainVisualAspect("flow", -1))
    }
}
