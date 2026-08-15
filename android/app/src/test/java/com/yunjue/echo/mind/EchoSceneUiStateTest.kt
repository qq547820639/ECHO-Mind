package com.yunjue.echo.mind
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.BehaviorState

import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitFactDto
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.ui.echo.assembleEchoSceneUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 §16：EchoSceneUiState 装配纯函数回归。
 * 一句话 fallback 链 + 干预建议门槛 + 依据来源，全部集中在装配层（UI 不得自行拼状态）。
 */
class EchoSceneUiStateTest {

    private fun portrait(
        headline: List<String> = listOf("开始得比平常晚"),
        summary: String = "今天开始活跃的时间比平常晚一些。",
        facts: List<PortraitFactDto> = listOf(
            PortraitFactDto("活跃开始", "10:14", "09:21", "+53 分钟")
        ),
    ) = DailyPortraitDto(
        date = "2026-08-14",
        status = "READY",
        confidence = "HIGH",
        baselineDays = 12,
        headline = headline,
        summary = summary,
        facts = facts,
    )

    private fun portraitState(status: PortraitStatus = PortraitStatus.READY) =
        PortraitUiState(status = status, portrait = portrait())

    private fun presence(confidence: Float = 0.8f, maturity: EchoMaturity = EchoMaturity.KNOWN) =
        EchoPresenceState(
            sensingStatus = SensingRuntimeStatus.ACTIVE,
            maturity = maturity,
            rhythmState = RhythmState(activityLevel = 0.5f, coverage = 0.7f),
            behaviorState = BehaviorState(density = 0.5f, deviation = 0.3f),
            confidence = confidence,
            identityGenome = EchoIdentityGenome(seed = 1L),
        )

    @Test
    fun aiNarrativeWinsHeadlineLayer() {
        val narrative = AiNarrativeService.NarrativeResult(
            level = NarrativeFallbackLevel.AI_NARRATIVE,
            text = "今天开始得比平常晚一些。",
            usedSources = listOf(DataSourceCategory.TODAY_AGGREGATE),
        )
        val state = assembleEchoSceneUiState(
            portraitState = portraitState(),
            presence = presence(),
            sensing = SensingRuntimeStatus.ACTIVE,
            narrative = narrative,
            intelligenceAvailable = true,
            suggestionsEnabled = true,
            now = 0L,
        )
        // ERA 20 §9：AI 不覆盖确定性 headline；作为增量 AI 层展示（三层区分）
        assertEquals("开始得比平常晚", state.headline)
        assertEquals("今天开始得比平常晚一些。", state.aiLayer)
        assertEquals(NarrativeFallbackLevel.AI_NARRATIVE, state.headlineLevel)
        assertEquals(listOf(DataSourceCategory.TODAY_AGGREGATE), state.headlineSources)
    }

    @Test
    fun aiLayerIsSuppressedWhenIdenticalToDeterministicHeadline() {
        val narrative = AiNarrativeService.NarrativeResult(
            level = NarrativeFallbackLevel.AI_NARRATIVE,
            text = "开始得比平常晚", // 与确定性 headline 完全相同 → 不重复展示
            usedSources = listOf(DataSourceCategory.TODAY_AGGREGATE),
        )
        val state = assembleEchoSceneUiState(
            portraitState = portraitState(),
            presence = presence(),
            sensing = SensingRuntimeStatus.ACTIVE,
            narrative = narrative,
            intelligenceAvailable = true,
            suggestionsEnabled = true,
            now = 0L,
        )
        assertEquals("开始得比平常晚", state.headline)
        assertEquals(null, state.aiLayer)
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, state.headlineLevel)
    }

    @Test
    fun deterministicHeadlineFallsBack() {
        val state = assembleEchoSceneUiState(
            portraitState = portraitState(),
            presence = presence(),
            sensing = SensingRuntimeStatus.ACTIVE,
            narrative = null,
            intelligenceAvailable = false,
            suggestionsEnabled = true,
        )
        assertEquals("开始得比平常晚", state.headline)
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, state.headlineLevel)
    }

    @Test
    fun emptyPortraitFallsBackToFactsCopy() {
        val state = assembleEchoSceneUiState(
            portraitState = PortraitUiState(status = PortraitStatus.WARMING_UP, portrait = null),
            presence = null,
            sensing = SensingRuntimeStatus.STARTING,
            narrative = null,
            intelligenceAvailable = false,
            suggestionsEnabled = true,
        )
        assertEquals("初见。", state.headline)
        assertEquals(NarrativeFallbackLevel.OBSERVATION_FACTS, state.headlineLevel)
        assertEquals(EchoMaturity.SEED, state.maturity)
    }

    @Test
    fun suggestionRequiresKnownHighConfidencePresence() {
        // 无 presence（数据不足）→ 不打扰
        val noPresence = assembleEchoSceneUiState(
            portraitState = portraitState(), presence = null,
            sensing = SensingRuntimeStatus.ACTIVE, narrative = null,
            intelligenceAvailable = true, suggestionsEnabled = true,
        )
        assertFalse(noPresence.suggestedAction)

        // 低置信 → 不打扰
        val lowConfidence = assembleEchoSceneUiState(
            portraitState = portraitState(), presence = presence(confidence = 0.3f),
            sensing = SensingRuntimeStatus.ACTIVE, narrative = null,
            intelligenceAvailable = true, suggestionsEnabled = true,
        )
        assertFalse(lowConfidence.suggestedAction)

        // 关闭建议 opt-in → 不打扰
        val optedOut = assembleEchoSceneUiState(
            portraitState = portraitState(), presence = presence(),
            sensing = SensingRuntimeStatus.ACTIVE, narrative = null,
            intelligenceAvailable = true, suggestionsEnabled = false,
        )
        assertFalse(optedOut.suggestedAction)

        // 高置信 + 已知 + opt-in → 建议
        val suggest = assembleEchoSceneUiState(
            portraitState = portraitState(), presence = presence(confidence = 0.9f),
            sensing = SensingRuntimeStatus.ACTIVE, narrative = null,
            intelligenceAvailable = true, suggestionsEnabled = true,
        )
        assertTrue(suggest.suggestedAction)
    }

    @Test
    fun factsCarryThroughForWhyLayer() {
        val state = assembleEchoSceneUiState(
            portraitState = portraitState(), presence = presence(),
            sensing = SensingRuntimeStatus.ACTIVE, narrative = null,
            intelligenceAvailable = true, suggestionsEnabled = true,
        )
        assertEquals(1, state.facts.size)
        assertEquals("活跃开始", state.facts.first().label)
        assertEquals(12, state.baselineDays)
        assertEquals(EchoMaturity.KNOWN, state.maturity)
    }
}
