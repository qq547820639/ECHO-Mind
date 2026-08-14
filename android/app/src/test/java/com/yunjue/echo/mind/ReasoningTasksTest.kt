package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.ContextPolicy
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.ReasoningTaskId
import com.yunjue.echo.mind.intelligence.contextPolicyFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 5：Reasoning Task 策略回归。
 * 隐私硬边界：原始通知内容 / 原始音频 / 麦克风特征永不进入任何任务。
 */
class ReasoningTasksTest {

    private val policies: Map<ReasoningTaskId, ContextPolicy> =
        ReasoningTaskId.entries.associateWith { contextPolicyFor(it) }

    @Test
    fun everyTaskHasBoundedContext() {
        for ((task, policy) in policies) {
            assertTrue("任务 $task 必须限制证据条数", policy.maxEvidenceItems > 0)
            assertTrue("任务 $task 必须有允许的数据源", policy.allowed.isNotEmpty())
        }
    }

    @Test
    fun rawSensitiveDataIsNeverAllowed() {
        val hard = setOf(
            DataSourceCategory.RAW_NOTIFICATIONS,
            DataSourceCategory.RAW_AUDIO,
            DataSourceCategory.MIC_FEATURES,
        )
        for ((task, policy) in policies) {
            for (category in hard) {
                assertFalse("任务 $task 不得允许 $category", category in policy.allowed)
                assertTrue("任务 $task 必须禁止 $category", category in policy.prohibited)
            }
        }
    }

    @Test
    fun personalQuestionAllowsConversationButNotAudio() {
        val policy = contextPolicyFor(ReasoningTaskId.ANSWER_PERSONAL_QUESTION)
        assertTrue(DataSourceCategory.CONVERSATION_HISTORY in policy.allowed)
        assertTrue(DataSourceCategory.USER_CORRECTIONS in policy.allowed)
        assertTrue(DataSourceCategory.RAW_AUDIO in policy.prohibited)
    }

    @Test
    fun nowInterpretationRequiresStructuredOutput() {
        assertTrue(contextPolicyFor(ReasoningTaskId.GENERATE_NOW_INTERPRETATION).structuredOutput)
        assertFalse(contextPolicyFor(ReasoningTaskId.ANSWER_PERSONAL_QUESTION).structuredOutput)
    }

    @Test
    fun longitudinalTasksAllowPortraitHistory() {
        for (task in listOf(
            ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
            ReasoningTaskId.SUMMARIZE_WEEK,
            ReasoningTaskId.SUMMARIZE_MONTH,
        )) {
            assertTrue("任务 $task 应允许历史画像", DataSourceCategory.PORTRAIT_HISTORY in contextPolicyFor(task).allowed)
        }
    }

    @Test
    fun timeWindowsAreInstantiatedPerTask() {
        // v2 §42：EvidencePolicy.TimeWindow 实例化
        assertEquals(1, contextPolicyFor(ReasoningTaskId.GENERATE_NOW_INTERPRETATION).timeWindowDays)
        assertEquals(7, contextPolicyFor(ReasoningTaskId.EXPLAIN_CURRENT_STATE).timeWindowDays)
        assertEquals(28, contextPolicyFor(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN).timeWindowDays)
        assertEquals(28, contextPolicyFor(ReasoningTaskId.ANSWER_PERSONAL_QUESTION).timeWindowDays)
        assertEquals(7, contextPolicyFor(ReasoningTaskId.SUMMARIZE_WEEK).timeWindowDays)
        assertEquals(28, contextPolicyFor(ReasoningTaskId.SUMMARIZE_MONTH).timeWindowDays)
    }

    @Test
    fun memoryPoliciesAreInstantiatedPerTask() {
        // v2 §42：MemoryPolicy 实例化——记忆类型白名单
        assertTrue(com.yunjue.echo.mind.memory.MemoryType.CORRECTION in
            contextPolicyFor(ReasoningTaskId.ANSWER_PERSONAL_QUESTION).allowedMemoryTypes)
        assertTrue(com.yunjue.echo.mind.memory.MemoryType.CONTEXT in
            contextPolicyFor(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN).allowedMemoryTypes)
        // NOW 叙事只允许上下文例外，不允许偏好/观察记忆（最小权限）
        assertEquals(
            setOf(com.yunjue.echo.mind.memory.MemoryType.CONTEXT),
            contextPolicyFor(ReasoningTaskId.GENERATE_NOW_INTERPRETATION).allowedMemoryTypes
        )
    }
}
