package com.yunjue.echo.mind

import com.yunjue.echo.mind.actions.InterventionInputs
import com.yunjue.echo.mind.actions.InterventionLevel
import com.yunjue.echo.mind.actions.InterventionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ERA 9：Intervention Policy 回归（默认 L0-L1；L3 必须 opt-in + 高置信 + 低频）。
 */
class InterventionPolicyTest {

    private val NOW = 1_800_000_000_000L

    private fun inputs(
        confidence: Float = 0.9f,
        ambientKnown: Boolean = true,
        suggestionsEnabled: Boolean = false,
        proactiveOptIn: Boolean = false,
        lastProactiveAtMs: Long = 0L,
    ) = InterventionInputs(
        confidence = confidence,
        ambientKnown = ambientKnown,
        suggestionsEnabled = suggestionsEnabled,
        proactiveOptIn = proactiveOptIn,
        lastProactiveAtMs = lastProactiveAtMs,
        nowMs = NOW,
    )

    @Test
    fun unknownStateIsVisualOnly() {
        assertEquals(InterventionLevel.L0_VISUAL_ONLY, InterventionPolicy.resolve(inputs(ambientKnown = false)))
    }

    @Test
    fun lowConfidenceIsVisualOnly() {
        // 低置信度：可以影响抽象视觉，不得形成语言/建议/通知
        assertEquals(InterventionLevel.L0_VISUAL_ONLY, InterventionPolicy.resolve(inputs(confidence = 0.4f)))
    }

    @Test
    fun defaultIsExplainWhenOpened() {
        assertEquals(InterventionLevel.L1_EXPLAIN_WHEN_OPENED, InterventionPolicy.resolve(inputs()))
    }

    @Test
    fun suggestionsRequireOptInAndConfidence() {
        assertEquals(
            InterventionLevel.L2_SUGGEST_WHEN_OPENED,
            InterventionPolicy.resolve(inputs(suggestionsEnabled = true))
        )
        // opt-in 但置信度不足（< 0.7）→ 连解释级语言都不给，回 L0
        assertEquals(
            InterventionLevel.L0_VISUAL_ONLY,
            InterventionPolicy.resolve(inputs(confidence = 0.65f, suggestionsEnabled = true))
        )
    }

    @Test
    fun proactiveRequiresOptInHighConfidenceAndInterval() {
        assertEquals(
            InterventionLevel.L3_PROACTIVE_NOTIFICATION,
            InterventionPolicy.resolve(inputs(proactiveOptIn = true))
        )
        // 置信度不足 → 不给 L3
        assertEquals(
            InterventionLevel.L2_SUGGEST_WHEN_OPENED,
            InterventionPolicy.resolve(inputs(confidence = 0.8f, suggestionsEnabled = true, proactiveOptIn = true))
        )
        // 间隔不足 → 降级（绝不因用户没互动而频繁打扰）
        assertEquals(
            InterventionLevel.L2_SUGGEST_WHEN_OPENED,
            InterventionPolicy.resolve(
                inputs(
                    suggestionsEnabled = true,
                    proactiveOptIn = true,
                    lastProactiveAtMs = NOW - InterventionPolicy.PROACTIVE_MIN_INTERVAL_MS / 2,
                )
            )
        )
    }
}
