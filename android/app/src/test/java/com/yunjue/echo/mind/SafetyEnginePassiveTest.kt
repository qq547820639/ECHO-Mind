package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.Severity
import com.yunjue.echo.mind.security.SafetyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T05 被动安全语义收口单测（PRD v0.6 契约点 1，纯 JVM）。
 *
 * - SafetyEngine.evaluatePassive 已删除（反射断言：方法不存在即编译/运行期证明）；
 * - passiveRedTerms 被动词表已删除；
 * - 行为派生摘要（含旧被动词条）不再产生 RED / freezeGeneration（恒 NONE 语义）；
 * - 主动文本 RED 是危机信号唯一来源白名单之一，原有语义保留（正控制）。
 */
class SafetyEnginePassiveTest {

    @Test
    fun passiveEvaluationFunctionIsRemoved() {
        // 编译期保证（下方不引用）+ 运行期断言：方法已删除
        val method = runCatching {
            SafetyEngine::class.java.getDeclaredMethod("evaluatePassive", String::class.java)
        }.getOrNull()
        assertNull("evaluatePassive 应已删除（PRD 契约点 1：行为特征不得触发危机判定）", method)
    }

    @Test
    fun passiveRedTermListIsRemoved() {
        val field = runCatching {
            SafetyEngine::class.java.getDeclaredField("passiveRedTerms")
        }.getOrNull()
        assertNull("passiveRedTerms 被动词表应已删除（静态扫描：不存在行为→危机的映射词表）", field)
    }

    @Test
    fun behavioralSummaryYieldsNoneWithoutPassiveEvaluator() {
        // 行为派生摘要（即使含旧被动词条）经主动文本规则评估应为 NONE（恒 NONE 语义）
        val decision = SafetyEngine.evaluate(
            "过去5分钟活动量低，屏幕开启0次，收到0条通知。"
        )
        assertEquals(Severity.NONE, decision.severity)
        assertFalse("被动摘要不得冻结生成", decision.freezeGeneration)
        assertTrue(decision.matchedRuleIds.isEmpty())
    }

    @Test
    fun behavioralSummaryWithOldRedTermDoesNotTrigger() {
        // 即便摘要包含"自杀"字样（旧被动 RED 词条），主动文本规则下也不产生 RED——
        // 该文本不是用户主动表达，不能触发危机链路
        val decision = SafetyEngine.evaluate(
            "过去5分钟活动量低，屏幕上无操作。用户被提及一次自杀倾向关键词。"
        )
        assertEquals(Severity.NONE, decision.severity)
        assertFalse(decision.freezeGeneration)
    }

    @Test
    fun activeTextRedRemainsTheWhitelistSource() {
        // 主动文本 RED 是危机信号唯一来源白名单（契约点 1），保留原有语义
        val decision = SafetyEngine.evaluate("我已经准备好工具，今晚结束生命")
        assertEquals(Severity.RED, decision.severity)
        assertTrue("主动文本命中 RED 应冻结生成", decision.freezeGeneration)
    }

    @Test
    fun negatedRiskStillRequestsHumanReview() {
        // 既有主动文本语义不变：否定语境降级为 YELLOW（人工复核，不自动危机）
        val decision = SafetyEngine.evaluate("我没有想死，但最近很难受")
        assertEquals(Severity.YELLOW, decision.severity)
        assertFalse(decision.freezeGeneration)
    }
}
