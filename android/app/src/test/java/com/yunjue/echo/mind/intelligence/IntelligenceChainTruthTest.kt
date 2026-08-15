package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.model.containsBlockedVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 55（ADR-059 第 1 轮）——Personal Intelligence 解释链端到端真值锚点：
 * 分类 → 排序（含 §68 task relevance）→ 编译（§69 预算）→ Grounding（§72）→ 答案降级（§73）。
 * 与 IntelligenceDepthTest（纯函数单点）互补：本测试锚定整条链路的输入输出契约。
 */
class IntelligenceChainTruthTest {

    private fun item(
        category: DataSourceCategory,
        text: String,
        id: String = "",
        type: String = "observation",
        confidence: Float = 0.5f,
    ) = EvidenceItem(
        category = category,
        label = text.take(8),
        text = text,
        id = id,
        type = type,
        confidence = confidence,
    )

    // ===== 1. 解释链端到端 =====

    @Test
    fun explanationChainRunsEndToEnd() {
        // 分类：长期作息漂移问题 → FIND_LONGITUDINAL_PATTERN
        val classification = QuestionClassifier.classify("最近两周我的作息越来越晚，为什么？")
        assertEquals(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, classification.task)

        val evidence = listOf(
            item(DataSourceCategory.PORTRAIT_HISTORY, "两周前 23 点入睡，最近三天 01 点入睡", id = "evt_rhythm_1"),
            // 纵向任务策略：纠正是记忆类型（allowedMemoryTypes=CORRECTION）进入，而非 USER_CORRECTIONS 证据类
            item(DataSourceCategory.CONTEXT_EXCEPTIONS, "我最近在赶项目，不是失眠", id = "evt_corr_1", type = "correction"),
            item(DataSourceCategory.CONTEXT_EXCEPTIONS, "这段时间在出差", id = "evt_exc_1", type = "context_exception"),
            item(DataSourceCategory.TODAY_AGGREGATE, "今天活跃起点明显偏晚", id = "evt_today_1"),
        )

        // 编译：排名 + 预算（§69）
        val compiled = EchoContextCompiler.compile(classification.task, evidence, question = "最近两周我的作息越来越晚，为什么？")
        assertTrue("纠正在编译内容中必须存在", compiled.userContent.contains("不是失眠"))
        assertTrue("上下文例外必须存在（用户解释优先）", compiled.userContent.contains("出差"))
        assertTrue("证据总量受预算约束", compiled.userContent.length <= 1600 * 3)

        // Grounding：AI 叙事带证据引用 → 通过并携带引用
        val grounded = GroundingValidator.buildAnswer(
            text = "你最近两周的作息在变晚（evt_rhythm_1），结合你提到的赶项目（evt_corr_1）……",
            evidence = evidence,
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
            model = "test-model",
        )
        assertFalse("有证据引用与无禁词时应通过", grounded.fallbackUsed)
        assertTrue(grounded.evidenceIds.contains("evt_rhythm_1"))

        // Grounding：违规推断词 → 降级为观察事实（§73 兜底，不编造）
        val degraded = GroundingValidator.buildAnswer(
            text = "你最近情绪低落了（depressed）",
            evidence = evidence,
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
            model = "test-model",
        )
        assertTrue("违规文本必须降级", degraded.fallbackUsed)
        assertTrue("降级文案必须是观察事实", degraded.text.startsWith("我能确定的事实是"))
        assertFalse("降级文案不得保留推断词", containsBlockedVocabulary(degraded.text))
    }

    // ===== 2. §68 task relevance：只影响同 tier，永不跨层级 =====

    @Test
    fun taskAffinityOrdersWithinTierOnly() {
        val history = item(DataSourceCategory.PORTRAIT_HISTORY, "两周作息时间线", confidence = 0.5f)
        val today = item(DataSourceCategory.TODAY_AGGREGATE, "今天作息摘要", confidence = 0.5f)
        val correction = item(DataSourceCategory.USER_CORRECTIONS, "用户纠正", type = "correction")

        // 纵向任务：同 tier 内 history > today（task relevance +0.5）
        val longitudinal = ContextRanker.rank(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, listOf(history, today, correction))
        assertEquals("纠正永远最高", DataSourceCategory.USER_CORRECTIONS, longitudinal.first().item.category)
        assertTrue(
            "纵向任务同 tier 内 history 应排 today 前",
            longitudinal.indexOfFirst { it.item.category == DataSourceCategory.PORTRAIT_HISTORY } <
                longitudinal.indexOfFirst { it.item.category == DataSourceCategory.TODAY_AGGREGATE },
        )

        // 当前状态解释任务：同 tier 内 today > history
        val current = ContextRanker.rank(ReasoningTaskId.EXPLAIN_CURRENT_STATE, listOf(history, today, correction))
        assertTrue(
            "当前状态任务同 tier 内 today 应排 history 前",
            current.indexOfFirst { it.item.category == DataSourceCategory.TODAY_AGGREGATE } <
                current.indexOfFirst { it.item.category == DataSourceCategory.PORTRAIT_HISTORY },
        )

        // 亲和永不跨 tier：低置信度纠正仍高于高置信度 history（tier 差 10 分）
        val weakCorrection = item(DataSourceCategory.USER_CORRECTIONS, "弱纠正", type = "correction", confidence = 0.05f)
        val strongHistory = item(DataSourceCategory.PORTRAIT_HISTORY, "高置信历史", confidence = 1f)
        val mixed = ContextRanker.rank(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, listOf(strongHistory, weakCorrection))
        assertEquals("task relevance 不得使观察事实越过用户纠正", weakCorrection.id, mixed.first().item.id)
    }
}
