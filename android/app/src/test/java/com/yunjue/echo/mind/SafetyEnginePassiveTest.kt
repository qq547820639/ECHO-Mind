package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.Severity
import com.yunjue.echo.mind.security.SafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T05 被动特征安全评估单测：
 *
 * - 命中确定性红色关键词 → RED + freezeGeneration + scriptKey="l2_stabilization"
 * - 多关键词命中 → matchedRuleIds 含多条
 * - 否定语境仍触发 RED（被动信号保守策略，区别于主动文本 evaluate 的否定守卫）
 * - 正常 summary 不触发 → NONE
 * - 预处理：标点剥离后仍命中
 */
class SafetyEnginePassiveTest {

    @Test
    fun passiveRedTermFreezesGeneration() {
        val result = SafetyEngine.evaluatePassive("用户输入多次提及自杀倾向")
        assertEquals(Severity.RED, result.severity)
        assertTrue("被动 RED 必须冻结生成", result.freezeGeneration)
        assertEquals("l2_stabilization", result.scriptKey)
        // "自杀" 是列表首项 → PASSIVE-RED-001
        assertEquals(listOf("PASSIVE-RED-001"), result.matchedRuleIds)
    }

    @Test
    fun multiplePassiveRedTermsAllMatched() {
        val result = SafetyEngine.evaluatePassive("最近不想活，多次出现自杀念头")
        assertEquals(Severity.RED, result.severity)
        assertTrue(result.freezeGeneration)
        // "自杀" → 001，"不想活" → 005；按列表顺序编号，不按文本出现顺序
        assertTrue(
            "应同时命中两条规则，实际：${result.matchedRuleIds}",
            result.matchedRuleIds.containsAll(listOf("PASSIVE-RED-001", "PASSIVE-RED-005"))
        )
        assertEquals(2, result.matchedRuleIds.size)
    }

    /**
     * 被动信号保守策略：即使 summary 含否定语境（如"没有自杀念头"），
     * 仍判 RED。区别于主动文本 [SafetyEngine.evaluate] 的 NEGATION_GUARDS ——
     * 被动摘要不像主动文本那样可靠地承载否定结构，保守起见命中即升级，
     * 与后端 evaluate_passive 一致（后端只做 substring 匹配，无否定守卫）。
     */
    @Test
    fun negationContextStillTriggersRed() {
        val result = SafetyEngine.evaluatePassive("用户多次表示没有自杀念头")
        assertEquals(
            "被动信号保守策略：含'自杀'即 RED，不做否定检查",
            Severity.RED,
            result.severity
        )
        assertTrue(result.freezeGeneration)
    }

    @Test
    fun normalSummaryDoesNotTrigger() {
        val result = SafetyEngine.evaluatePassive("过去5分钟活动量低")
        assertEquals(Severity.NONE, result.severity)
        assertFalse(result.freezeGeneration)
        assertTrue(result.matchedRuleIds.isEmpty())
    }

    @Test
    fun punctuationStrippedBeforeMatch() {
        // 标点应被预处理剥离，与 evaluate 一致
        val result = SafetyEngine.evaluatePassive("自杀。")
        assertEquals(Severity.RED, result.severity)
        assertTrue(result.freezeGeneration)
        assertEquals(listOf("PASSIVE-RED-001"), result.matchedRuleIds)
    }
}
