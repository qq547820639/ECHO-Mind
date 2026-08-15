package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.memory.MemoryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 56（ADR-059 第 2 轮）——检索策略矩阵与 Grounding 边界锚点：
 *
 * - 所有任务：原始通知/音频/麦克风特征硬禁止（隐私硬边界永不因策略演化回退）；
 * - §78/§79：所有解释/总结/建议类任务的记忆白名单必须含 CONTEXT——
 *   用户「最近在出差」解释必须可进入相关 reasoning；
 * - 预算字段始终有限（§69 不把全历史塞模型）；
 * - Grounding 引用判定边界：空证据 AI 叙事必须失败 / 空 id 不崩 / 短 label 可引用 / 观察层级不要求证据。
 */
class IntelligencePolicyMatrixTest {

    private val userExplanationTasks = setOf(
        ReasoningTaskId.GENERATE_NOW_INTERPRETATION,
        ReasoningTaskId.EXPLAIN_CURRENT_STATE,
        ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
        ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
        ReasoningTaskId.SUMMARIZE_WEEK,
        ReasoningTaskId.SUMMARIZE_MONTH,
        ReasoningTaskId.PROPOSE_ACTION,
    )

    @Test
    fun rawSensitiveSourcesAreProhibitedForEveryTask() {
        val neverAllowed = setOf(
            DataSourceCategory.RAW_NOTIFICATIONS,
            DataSourceCategory.RAW_AUDIO,
            DataSourceCategory.MIC_FEATURES,
        )
        for (task in ReasoningTaskId.entries) {
            val policy = contextPolicyFor(task)
            assertTrue("${task.name} 必须硬禁止原始通知/音频/麦克风", policy.prohibited.containsAll(neverAllowed))
        }
    }

    @Test
    fun userExplanationsReachEveryReasoningTask() {
        for (task in userExplanationTasks) {
            val policy = contextPolicyFor(task)
            assertTrue(
                "${task.name} 的记忆白名单必须含 CONTEXT（§78/§79 用户解释优先）",
                MemoryType.CONTEXT in policy.allowedMemoryTypes,
            )
            assertTrue(
                "${task.name} 的证据类别必须含 CONTEXT_EXCEPTIONS",
                DataSourceCategory.CONTEXT_EXCEPTIONS in policy.allowed,
            )
        }
    }

    @Test
    fun budgetsAreAlwaysFinite() {
        for (task in ReasoningTaskId.entries) {
            val policy = contextPolicyFor(task)
            assertTrue("${task.name} 证据上限应在 1..40", policy.maxEvidenceItems in 1..40)
            assertTrue("${task.name} token 预算应 >= 200", policy.maxTokens >= 200)
            assertTrue("${task.name} 时间窗应在 1..365 天", policy.timeWindowDays in 1..365)
        }
    }

    // ===== Grounding 引用判定边界 =====

    @Test
    fun emptyEvidenceFailsAiNarrative() {
        val report = GroundingValidator.validate(
            text = "这是模型生成的解释",
            evidence = emptyList(),
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
        )
        assertFalse("AI 叙事无证据必须失败（Evidence exists）", report.passed)
    }

    @Test
    fun emptyEvidenceIsFineForObservationLevel() {
        val report = GroundingValidator.validate(
            text = "当前证据不足。",
            evidence = emptyList(),
            fallbackLevel = NarrativeFallbackLevel.OBSERVATION_FACTS,
        )
        assertTrue("观察事实层级不要求证据存在", report.passed)
    }

    @Test
    fun blankIdDoesNotCrashAndCitesNothing() {
        val evidence = listOf(
            EvidenceItem(category = DataSourceCategory.TODAY_AGGREGATE, label = "今日观察", text = "活跃起点偏晚", id = ""),
        )
        val answer = GroundingValidator.buildAnswer(
            text = "活跃起点偏晚。",
            evidence = evidence,
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
        )
        assertFalse("空 id 无法被引用 → 引用列表为空（不崩）", answer.fallbackUsed)
        assertTrue(answer.evidenceIds.isEmpty())
    }

    @Test
    fun shortLabelCanBeCited() {
        val evidence = listOf(
            EvidenceItem(
                category = DataSourceCategory.TODAY_AGGREGATE,
                label = "晚睡",
                text = "活跃起点偏晚",
                id = "evt_x",
            ),
        )
        val answer = GroundingValidator.buildAnswer(
            text = "这与你提到的晚睡一致。",
            evidence = evidence,
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
        )
        assertFalse(answer.fallbackUsed)
        assertTrue("短 label（take(6) 引用）应命中", answer.evidenceIds.contains("evt_x"))
    }
}
