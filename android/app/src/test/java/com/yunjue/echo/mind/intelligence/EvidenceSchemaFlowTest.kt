package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitFactDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 59（ADR-059 收官）——§70 证据 schema 全字段流通 + §71 EchoAnswer 字段一致性：
 *
 * - 画像事实的 baseline/comparison 进入 schema 指定字段，并经编译显式进入模型上下文；
 * - EchoAnswer.timeRange 由被引用证据的时间范围导出（最早~最晚；无引用 → null）；
 * - 无对比字段时编译文本不产生空括号。
 */
class EvidenceSchemaFlowTest {

    private fun portrait() = DailyPortraitDto(
        date = "2026-08-15",
        status = "READY",
        confidence = "HIGH",
        baselineDays = 30,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        facts = listOf(
            PortraitFactDto(label = "作息", todayText = "23:10 入睡", baselineText = "00:40 入睡", deltaText = "更早"),
        ),
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = "SIMILAR", metric = "m", z = 0.5),
        ),
    )

    @Test
    fun portraitBaselineAndComparisonFlowIntoSchemaFieldsAndCompile() {
        val evidence = EvidenceAssembler.fromPortrait(portrait())
        val fact = evidence.first { it.label == "作息" }
        assertEquals("今天：23:10 入睡", fact.text)
        assertEquals("00:40 入睡", fact.baseline)
        assertEquals("更早", fact.comparison)

        val compiled = EchoContextCompiler.compile(
            task = ReasoningTaskId.EXPLAIN_CURRENT_STATE,
            evidence = evidence,
            question = "为什么今天不一样？",
        )
        assertTrue("编译文本应含结构化平常对比", compiled.userContent.contains("（平常：00:40 入睡）"))
        assertTrue("编译文本应含结构化变化对比", compiled.userContent.contains("（变化：更早）"))
    }

    @Test
    fun noContrastFieldsProduceNoEmptySuffix() {
        val evidence = listOf(
            EvidenceItem(category = DataSourceCategory.TODAY_AGGREGATE, label = "维度", text = "移动：接近"),
        )
        val compiled = EchoContextCompiler.compile(ReasoningTaskId.EXPLAIN_CURRENT_STATE, evidence)
        assertTrue("无对比字段不得出现空括号", !compiled.userContent.contains("（）"))
    }

    @Test
    fun answerTimeRangeDerivedFromCitedEvidence() {
        val evidence = listOf(
            EvidenceItem(
                category = DataSourceCategory.PORTRAIT_HISTORY, label = "一", text = "第一条",
                id = "evt_a", timeRange = "2026-08-01",
            ),
            EvidenceItem(
                category = DataSourceCategory.PORTRAIT_HISTORY, label = "二", text = "第二条",
                id = "evt_b", timeRange = "2026-08-10",
            ),
        )
        val answer = GroundingValidator.buildAnswer(
            text = "从 evt_a 到 evt_b 的节奏在变。",
            evidence = evidence,
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
            model = "m",
        )
        assertEquals("被引用证据的时间范围应导出（最早~最晚）", "2026-08-01~2026-08-10", answer.timeRange)

        val single = GroundingValidator.buildAnswer(
            text = "这与你提到的第一条 evt_a 一致。",
            evidence = evidence,
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
        )
        assertEquals("单一引用 → 单一时间范围", "2026-08-01", single.timeRange)

        val none = GroundingValidator.buildAnswer(
            text = "基于证据的回答。",
            evidence = evidence,
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
        )
        assertNull("无引用 → null", none.timeRange)
    }
}
