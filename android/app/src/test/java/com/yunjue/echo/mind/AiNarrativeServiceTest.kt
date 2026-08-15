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
        assertTrue(result.text.contains("还没有连接 AI"))
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
}
