package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.EchoReasoningRequest
import com.yunjue.echo.mind.intelligence.EchoReasoningResponse
import com.yunjue.echo.mind.intelligence.EvidenceItem
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.intelligence.ProviderStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 5：AiNarrativeService fallback 链回归（AI 失败不得破坏 ECHO，PART 75/76）。
 */
class AiNarrativeServiceTest {

    private val evidence = listOf(
        EvidenceItem(DataSourceCategory.TODAY_AGGREGATE, "今天", "屏幕互动比平常多"),
        EvidenceItem(DataSourceCategory.BASELINE, "基线", "28 天基线"),
    )

    private fun service(
        hasProvider: Boolean,
        status: ProviderStatus,
        text: String = "",
    ) = AiNarrativeService(
        hasProvider = { hasProvider },
        reason = { _: EchoReasoningRequest ->
            EchoReasoningResponse(text = text, structuredJson = text, status = status)
        },
    )

    @Test
    fun noProviderFallsBackToDeterministic() = runBlocking {
        val result = service(hasProvider = false, status = ProviderStatus.NOT_CONFIGURED)
            .nowNarrative(evidence, "今天和平时很接近。", "事实兜底")
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("今天和平时很接近。", result.text)
    }

    @Test
    fun providerFailureFallsBackToDeterministic() = runBlocking {
        val result = service(hasProvider = true, status = ProviderStatus.RATE_LIMITED)
            .nowNarrative(evidence, "确定性叙事", "事实")
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("确定性叙事", result.text)
    }

    @Test
    fun authFailureFallsBackToFacts() = runBlocking {
        val result = service(hasProvider = true, status = ProviderStatus.AUTH_FAILED)
            .nowNarrative(evidence, "", "今天数据还不足。")
        assertEquals(NarrativeFallbackLevel.OBSERVATION_FACTS, result.level)
        assertEquals("今天数据还不足。", result.text)
    }

    @Test
    fun validAiNarrativePassesThroughWithSources() = runBlocking {
        val json = """{"statement":"今天开始得比平常晚一些。","confidence":0.8,"evidence_count":2}"""
        val result = service(hasProvider = true, status = ProviderStatus.READY, text = json)
            .nowNarrative(evidence, "确定性叙事", "事实")
        assertEquals(NarrativeFallbackLevel.AI_NARRATIVE, result.level)
        assertEquals("今天开始得比平常晚一些。", result.text)
        assertTrue(DataSourceCategory.TODAY_AGGREGATE in result.usedSources)
    }

    @Test
    fun unsafeAiNarrativeIsRejectedToDeterministic() = runBlocking {
        // AI 输出含心理推断词 → 词表门禁否决 → 降级（不把越界话给用户）
        val json = """{"statement":"你今天可能有些焦虑。","confidence":0.9,"evidence_count":2}"""
        val result = service(hasProvider = true, status = ProviderStatus.READY, text = json)
            .nowNarrative(evidence, "确定性叙事", "事实")
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("确定性叙事", result.text)
    }

    @Test
    fun answerQuestionWithoutProviderIsHonest() = runBlocking {
        val result = service(hasProvider = false, status = ProviderStatus.NOT_CONFIGURED)
            .answerQuestion("我最近是不是越来越晚？", evidence)
        // ERA 31 R17：无 Provider 兜底不得出现「连接 AI」催促/网络状态泄漏，应引导可答的问法
        assertTrue(result.text.contains("换一种问法"))
        assertTrue(!result.text.contains("连接 AI"))
        assertTrue(!result.text.contains("网络"))
        assertEquals(NarrativeFallbackLevel.OBSERVATION_FACTS, result.level)
    }

    @Test
    fun answerQuestionWithProviderReturnsGroundedAnswer() = runBlocking {
        val result = service(hasProvider = true, status = ProviderStatus.READY, text = "从最近 28 天看，你的开始时间有轻微后移。")
            .answerQuestion("我最近是不是越来越晚？", evidence)
        assertEquals(NarrativeFallbackLevel.AI_NARRATIVE, result.level)
        assertTrue(result.usedSources.isNotEmpty())
    }

    // ===== ERA 57（§73 审计第 3 轮）：有限重试（仅瞬态失败，共 2 次尝试） =====

    @Test
    fun transientFailureRetriesOnceThenSucceeds() = runBlocking {
        var calls = 0
        val service = AiNarrativeService(
            hasProvider = { true },
            reason = { _ ->
                calls++
                if (calls == 1) {
                    EchoReasoningResponse(text = "", status = ProviderStatus.NETWORK_ERROR)
                } else {
                    EchoReasoningResponse(text = "今天和平时很接近。", structuredJson = "{\"statement\":\"今天和平时很接近。\"}", status = ProviderStatus.READY)
                }
            },
        )
        val result = service.nowNarrative(evidence, "确定性叙事", "事实")
        assertEquals("瞬态失败应重试一次后成功", NarrativeFallbackLevel.AI_NARRATIVE, result.level)
        assertEquals("重试成功后应取第二次结果", 2, calls)
    }

    @Test
    fun transientFailureRetriesOnceThenFallsBack() = runBlocking {
        var calls = 0
        val service = AiNarrativeService(
            hasProvider = { true },
            reason = { _ ->
                calls++
                EchoReasoningResponse(text = "", status = ProviderStatus.PROVIDER_ERROR)
            },
        )
        val result = service.nowNarrative(evidence, "确定性叙事", "事实")
        assertEquals("两次瞬态失败后降级确定性叙事", NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("共 2 次尝试（1 次重试），不无限重试", 2, calls)
    }

    @Test
    fun semanticFailureDoesNotRetry() = runBlocking {
        var calls = 0
        val service = AiNarrativeService(
            hasProvider = { true },
            reason = { _ ->
                calls++
                EchoReasoningResponse(text = "", status = ProviderStatus.RATE_LIMITED)
            },
        )
        val result = service.nowNarrative(evidence, "确定性叙事", "事实")
        assertEquals("限流立即降级（重试只会放大伤害）", NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("语义失败不重试", 1, calls)
    }

    // ===== ERA 31 BATCH 2：确定性个人回答钩子（无 Provider 也回答个人问题） =====

    @Test
    fun noProviderWithDeterministicHookAnswersPersonalQuestion() = runBlocking {
        val service = AiNarrativeService(
            hasProvider = { false },
            reason = { _ -> EchoReasoningResponse(text = "", status = ProviderStatus.NOT_CONFIGURED) },
            deterministicPersonalAnswer = { q ->
                if (q == "最近我是不是越来越晚？") {
                    AiNarrativeService.DeterministicPersonalResult(
                        text = "是的，最近这一个月你明显开始得比前一个月晚。",
                        usedSources = listOf(DataSourceCategory.PORTRAIT_HISTORY),
                    )
                } else {
                    null
                }
            },
        )
        val result = service.answerQuestion("最近我是不是越来越晚？", evidence)
        assertEquals("无 Provider 也应由确定性引擎回答", NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertTrue("回答来自个人数据", result.text.contains("明显开始得比前一个月晚"))
        assertTrue("来源标注画像历史", DataSourceCategory.PORTRAIT_HISTORY in result.usedSources)
    }

    @Test
    fun providerFailureFallsBackToDeterministicPersonalAnswer() = runBlocking {
        val service = AiNarrativeService(
            hasProvider = { true },
            reason = { _ -> EchoReasoningResponse(text = "", status = ProviderStatus.PROVIDER_ERROR) },
            deterministicPersonalAnswer = { _ ->
                AiNarrativeService.DeterministicPersonalResult("周末比工作日晚起 60 分钟。", emptyList())
            },
        )
        val result = service.answerQuestion("周末和平时有什么变化？", evidence)
        assertEquals("Provider 失败应降级到确定性个人回答", NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertTrue(result.text.contains("晚起 60 分钟"))
    }

    // ===== T8-P2-4 补缺：longitudinalNarrative（Journey 半年故事；生产 JourneyRepository 调用，此前零测试） =====

    @Test
    fun longitudinalWithoutProviderFallsBackToDeterministic() = runBlocking {
        val result = service(hasProvider = false, status = ProviderStatus.NOT_CONFIGURED)
            .longitudinalNarrative(evidence, "最接近：作息；变化较明显：屏幕总量")
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("最接近：作息；变化较明显：屏幕总量", result.text)
    }

    @Test
    fun longitudinalProviderFailureFallsBackToDeterministic() = runBlocking {
        val result = service(hasProvider = true, status = ProviderStatus.RATE_LIMITED)
            .longitudinalNarrative(evidence, "确定性综述")
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("确定性综述", result.text)
    }

    @Test
    fun longitudinalValidAiNarrativePassesThroughWithSources() = runBlocking {
        // 最小 happy-path：READY + 词表门禁通过 → AI 层 + 依据来源标注（与 nowNarrative 同规）
        val result = service(hasProvider = true, status = ProviderStatus.READY, text = "从最近 28 天看，你的开始时间有轻微后移。")
            .longitudinalNarrative(evidence, "确定性综述")
        assertEquals(NarrativeFallbackLevel.AI_NARRATIVE, result.level)
        assertEquals("从最近 28 天看，你的开始时间有轻微后移。", result.text)
        // FIND_LONGITUDINAL_PATTERN 的 Context Policy 只允许 PORTRAIT_HISTORY/BASELINE/
        // CONTEXT_EXCEPTIONS/USER_CORRECTIONS——当日聚合（TODAY_AGGREGATE）被排除在依据外
        assertTrue(DataSourceCategory.BASELINE in result.usedSources)
        assertTrue(DataSourceCategory.TODAY_AGGREGATE !in result.usedSources)
    }

    @Test
    fun longitudinalUnsafeAiNarrativeIsRejectedToDeterministic() = runBlocking {
        // 心理推断词 → 词表门禁否决 → 诚实降级（不把越界话给用户，也不重试到天荒地老）
        val result = service(hasProvider = true, status = ProviderStatus.READY, text = "你最近可能有些焦虑。")
            .longitudinalNarrative(evidence, "确定性综述")
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("确定性综述", result.text)
    }

    @Test
    fun longitudinalBlankAiNarrativeFallsBackToDeterministic() = runBlocking {
        val result = service(hasProvider = true, status = ProviderStatus.READY, text = "   ")
            .longitudinalNarrative(evidence, "确定性综述")
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, result.level)
        assertEquals("确定性综述", result.text)
    }

    @Test
    fun unknownQuestionGivesEchoVoicedGuidanceWithoutAiNag() = runBlocking {
        val service = AiNarrativeService(
            hasProvider = { false },
            reason = { _ -> EchoReasoningResponse(text = "", status = ProviderStatus.NOT_CONFIGURED) },
            deterministicPersonalAnswer = { null },
        )
        val result = service.answerQuestion("帮我写一首诗", evidence)
        assertTrue("引擎不认识的问题给出引导而非 AI 催促", result.text.contains("换一种问法"))
        assertTrue(!result.text.contains("连接 AI"))
        assertEquals(NarrativeFallbackLevel.OBSERVATION_FACTS, result.level)
    }
}
