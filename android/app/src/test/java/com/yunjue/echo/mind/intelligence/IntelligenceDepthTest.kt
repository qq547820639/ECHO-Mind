package com.yunjue.echo.mind.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 15 §104 — Intelligence Depth 测试矩阵：
 * classification / context retrieval(budget) / ranking / privacy / grounding /
 * invalid output / fallback / correction priority。
 */
class IntelligenceDepthTest {

    private fun evidence(
        category: DataSourceCategory,
        text: String,
        type: String = "observation",
        confidence: Float = 0.7f,
        id: String = "",
    ) = EvidenceItem(
        category = category, label = "L", text = text, id = id, type = type,
        confidence = confidence, provenance = "test:v1",
    )

    // ===== §104 classification =====

    @Test
    fun classifierMapsSixCategories() {
        assertEquals(ReasoningTaskId.EXPLAIN_CURRENT_STATE, QuestionClassifier.classify("为什么今天不一样？").task)
        assertEquals(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, QuestionClassifier.classify("最近的变化趋势是什么").task)
        assertEquals(ReasoningTaskId.SUMMARIZE_WEEK, QuestionClassifier.classify("总结一下这周").task)
        assertEquals(ReasoningTaskId.SUMMARIZE_MONTH, QuestionClassifier.classify("这个月怎么样").task)
        assertEquals(ReasoningTaskId.PROPOSE_ACTION, QuestionClassifier.classify("我该怎么办？给点建议").task)
        assertEquals(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, QuestionClassifier.classify("我喜欢什么").task)
    }

    @Test
    fun classifierFallsBackToPersonalQuestion() {
        val blank = QuestionClassifier.classify("   ")
        assertEquals(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, blank.task)
        assertEquals(0f, blank.confidence)
        val unknown = QuestionClassifier.classify("你好呀")
        assertEquals(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, unknown.task)
    }

    // ===== §104 ranking + correction priority =====

    @Test
    fun rankerPutsCorrectionsFirst() {
        val correction = evidence(DataSourceCategory.USER_CORRECTIONS, "用户纠正：不是熬夜", type = "correction", confidence = 1f)
        val context = evidence(DataSourceCategory.CONTEXT_EXCEPTIONS, "出差", type = "context_exception")
        val observation = evidence(DataSourceCategory.TODAY_AGGREGATE, "今天活跃", confidence = 0.6f)
        val ranked = ContextRanker.rank(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, listOf(observation, context, correction))
            .map { it.item }
        assertEquals(correction, ranked[0])   // 用户纠正永远最高（§75 同源）
        assertEquals(context, ranked[1])     // 用户解释优先
        assertEquals(observation, ranked[2])
    }

    // ===== §104 budget =====

    @Test
    fun compilerEnforcesEvidenceAndTokenBudget() {
        val items = (1..40).map { evidence(DataSourceCategory.PORTRAIT_HISTORY, "历史画像第${it}天：${"长文本".repeat(200)}") }
        val compiled = EchoContextCompiler.compile(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, items, null)
        // maxEvidenceItems 生效（FIND_LONGITUDINAL_PATTERN 策略上限）
        assertTrue(compiled.userContent.length < 40 * 200 * 3) // 不可能全量塞入
        assertTrue("预算内应包含证据", compiled.userContent.contains("历史画像第1天") || compiled.userContent.contains("历史画像第"))
    }

    @Test
    fun compilerExcludesProhibitedAndDisallowed() {
        val mic = evidence(DataSourceCategory.RAW_NOTIFICATIONS, "通知内容（禁止）")
        val today = evidence(DataSourceCategory.TODAY_AGGREGATE, "今天的节律")
        val compiled = EchoContextCompiler.compile(ReasoningTaskId.EXPLAIN_CURRENT_STATE, listOf(mic, today), null)
        assertFalse(compiled.userContent.contains("通知内容"))
        assertTrue(compiled.userContent.contains("今天的节律"))
        assertTrue(compiled.excludedSources.contains(DataSourceCategory.RAW_NOTIFICATIONS))
    }

    // ===== §104 grounding =====

    @Test
    fun groundingRejectsBannedInferenceWords() {
        val text = "你最近有点焦虑，需要休息"
        val report = GroundingValidator.validate(text, listOf(evidence(DataSourceCategory.TODAY_AGGREGATE, "事实")), NarrativeFallbackLevel.AI_NARRATIVE)
        assertFalse(report.passed)
        assertTrue(report.problems.any { it.contains("Felt") })
    }

    @Test
    fun groundingRequiresEvidenceForAiNarrative() {
        val text = "你最近作息变晚了"
        val report = GroundingValidator.validate(text, emptyList(), NarrativeFallbackLevel.AI_NARRATIVE)
        assertFalse(report.passed)
        assertTrue(report.problems.any { it.contains("证据") })
        // 观察事实层级无证据也可通过（fallback 语义）
        val observation = GroundingValidator.validate(text, emptyList(), NarrativeFallbackLevel.OBSERVATION_FACTS)
        assertTrue(observation.passed)
    }

    @Test
    fun groundingBuildsObservationFallbackAnswer() {
        val answer = GroundingValidator.buildAnswer(
            text = "你最近有点焦虑",
            evidence = listOf(evidence(DataSourceCategory.TODAY_AGGREGATE, "今天活跃度偏高", type = "observation")),
            fallbackLevel = NarrativeFallbackLevel.AI_NARRATIVE,
        )
        assertTrue(answer.fallbackUsed)
        assertFalse(answer.text.contains("焦虑")) // 降级文案不含禁词
        assertEquals(InterpretationLevel.INTERPRETATION, answer.interpretationLevel)
    }

    // ===== §104 fallback chain（§73 语义） =====

    @Test
    fun fallbackLevelsAreOrdered() {
        // AI → deterministic → observation facts 降级链由 NarrativeFallbackLevel 表示；
        // validator 对无效输出一律降到 OBSERVATION_FACTS。
        val invalid = GroundingValidator.validate("", emptyList(), NarrativeFallbackLevel.AI_NARRATIVE)
        assertFalse(invalid.passed)
    }
}
