package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_SUMMARY_NO_DATA
import com.yunjue.echo.mind.model.PortraitDimensionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 31 R19 — Journey 确定性叙事（免费用户 Journey 长期叙事）契约：
 * 与 portraitStabilitySummary 同源统计但说成人话（§30「看见自己的时间」，
 * 不是「最接近：X；变化较明显：Y」指标行）；同输入确定性；无数据不编造。
 */
class JourneyNaturalSummaryTest {

    private fun portrait(date: String, dims: Map<String, String>) = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = 10,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = dims.mapValues { (_, v) -> PortraitDimensionDto(value = v, metric = null, z = null) },
    )

    @Test
    fun namesStableAndChangedDimensionsInNaturalLanguage() {
        val portraits = listOf(
            portrait("2026-08-01", mapOf("SCREEN_AMOUNT" to "SIMILAR", "RHYTHM" to "LATER")),
            portrait("2026-08-02", mapOf("SCREEN_AMOUNT" to "SIMILAR", "RHYTHM" to "LATER")),
            portrait("2026-08-03", mapOf("SCREEN_AMOUNT" to "SIMILAR", "RHYTHM" to "SIMILAR")),
        )
        val summary = journeyNaturalSummary(portraits)
        assertTrue("应为自然句而非指标行：$summary", summary.startsWith("过去 3 天里，你的"))
        assertTrue("应点出最接近的维度：$summary", summary.contains("屏幕总量最接近平常"))
        assertTrue("应点出变化较明显的维度：$summary", summary.contains("变化较明显的是作息"))
        assertTrue("不应出现指标冒号格式：$summary", !summary.contains("最接近："))
    }

    @Test
    fun singleDimensionSaysStableInsteadOfSelfContradiction() {
        // 只有一个维度时（新用户早期），stable == changed——不得说「X 最接近又变化明显」
        val portraits = listOf(
            portrait("2026-08-01", mapOf("RHYTHM" to "SIMILAR")),
            portrait("2026-08-02", mapOf("RHYTHM" to "LATER")),
        )
        val summary = journeyNaturalSummary(portraits)
        assertTrue("单一维度应整体平稳句：$summary", summary.contains("整体比较平稳"))
    }

    @Test
    fun noDataReturnsHonestPlaceholder() {
        assertEquals(PORTRAIT_SUMMARY_NO_DATA, journeyNaturalSummary(emptyList()))
        assertEquals(
            PORTRAIT_SUMMARY_NO_DATA,
            journeyNaturalSummary(listOf(portrait("2026-08-01", emptyMap()))),
        )
    }

    @Test
    fun summaryIsDeterministic() {
        val portraits = listOf(
            portrait("2026-08-01", mapOf("RHYTHM" to "LATER", "MOVEMENT" to "SIMILAR")),
            portrait("2026-08-02", mapOf("RHYTHM" to "SIMILAR", "MOVEMENT" to "MORE")),
        )
        assertEquals(journeyNaturalSummary(portraits), journeyNaturalSummary(portraits))
    }
}
