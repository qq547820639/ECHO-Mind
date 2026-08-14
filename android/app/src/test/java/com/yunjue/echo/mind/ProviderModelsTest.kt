package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.intelligence.ProviderStatus
import com.yunjue.echo.mind.intelligence.narrativeFallbackFor
import com.yunjue.echo.mind.intelligence.providerStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 4：Provider 领域模型回归。
 * 状态文案唯一（防混用）+ 失败降级链（AI 失败不得破坏 ECHO，PART 75/76）。
 */
class ProviderModelsTest {

    @Test
    fun statusCopyIsUniqueAndNonBlank() {
        val statuses = ProviderStatus.entries
        assertEquals(10, statuses.size)
        val copies = statuses.map { providerStatusText(it) }
        assertTrue(copies.all { it.isNotBlank() })
        assertEquals(statuses.size, copies.toSet().size)
    }

    @Test
    fun readyAllowsAiNarrative() {
        assertEquals(
            NarrativeFallbackLevel.AI_NARRATIVE,
            narrativeFallbackFor(ProviderStatus.READY)
        )
    }

    @Test
    fun transientFailuresFallBackToDeterministic() {
        // 限流/额度/网络/服务端错误 → 确定性叙事（现有 deterministic 模板仍在工作）
        for (status in listOf(
            ProviderStatus.RATE_LIMITED,
            ProviderStatus.QUOTA_EXCEEDED,
            ProviderStatus.NETWORK_ERROR,
            ProviderStatus.PROVIDER_ERROR,
            ProviderStatus.MODEL_NOT_FOUND,
            ProviderStatus.UNSUPPORTED_CAPABILITY,
        )) {
            assertEquals(
                "状态 $status 应降级到确定性叙事",
                NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                narrativeFallbackFor(status)
            )
        }
    }

    @Test
    fun noProviderOrBadKeyFallsBackToFacts() {
        // 无 Provider / 密钥无效 → 观察事实层（ECHO 基础体验永远可用）
        assertEquals(
            NarrativeFallbackLevel.OBSERVATION_FACTS,
            narrativeFallbackFor(ProviderStatus.NOT_CONFIGURED)
        )
        assertEquals(
            NarrativeFallbackLevel.OBSERVATION_FACTS,
            narrativeFallbackFor(ProviderStatus.AUTH_FAILED)
        )
    }
}
