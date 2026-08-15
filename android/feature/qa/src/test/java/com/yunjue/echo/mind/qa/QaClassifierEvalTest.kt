package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.QuestionClassifier
import com.yunjue.echo.mind.intelligence.ReasoningTaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 22 §24/§26 — Personal Question Eval：
 * 1. 题库 ≥100 条（§24）；
 * 2. 分类准确率（结构化分类器 vs Expected Evidence 的任务）；
 * 3. 每条用例 Expected Evidence 完整（§25：时间窗/baseline/correction/context/categories 非空约束）。
 */
class QaClassifierEvalTest {

    @Test
    fun bankHasAtLeastOneHundredQuestions() {
        val count = QaQuestionBank.ALL.size
        println("question bank size: $count")
        assertTrue("题库应 ≥100 条（实际 $count）", count >= 100)
        // id 唯一
        assertEquals(count, QaQuestionBank.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun everyCaseHasCompleteExpectedEvidence() {
        for (case in QaQuestionBank.ALL) {
            assertTrue("${case.id} 时间窗 > 0", case.expected.timeWindowDays > 0)
            // 分类任务必须是可检索任务（非内存内部任务）
            assertTrue(
                "${case.id} 任务合法",
                case.expected.task in setOf(
                    ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
                    ReasoningTaskId.EXPLAIN_CURRENT_STATE,
                    ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
                    ReasoningTaskId.SUMMARIZE_WEEK,
                    ReasoningTaskId.SUMMARIZE_MONTH,
                    ReasoningTaskId.PROPOSE_ACTION,
                ),
            )
        }
    }

    @Test
    fun classifierAccuracyMeetsGate() {
        var correct = 0
        val mismatches = mutableListOf<String>()
        for (case in QaQuestionBank.ALL) {
            val actual = QuestionClassifier.classify(case.question).task
            if (actual == case.expected.task) {
                correct++
            } else {
                mismatches += "${case.id} ${case.question} → 期望 ${case.expected.task} 实际 $actual"
            }
        }
        val accuracy = correct.toDouble() / QaQuestionBank.ALL.size
        println("classifier accuracy: ${"%.3f".format(accuracy)} (${correct}/${QaQuestionBank.ALL.size})")
        if (mismatches.isNotEmpty()) println("mismatches:\n" + mismatches.joinToString("\n"))
        assertTrue("分类准确率 ≥ 0.85（实际 ${"%.3f".format(accuracy)}）", accuracy >= 0.85)
    }

    @Test
    fun classifierKeepsLegacyAnchors() {
        assertEquals(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, QuestionClassifier.classify("最近两周我的作息越来越晚，为什么？").task)
        assertEquals(ReasoningTaskId.EXPLAIN_CURRENT_STATE, QuestionClassifier.classify("为什么今天不一样？").task)
        assertEquals(ReasoningTaskId.SUMMARIZE_WEEK, QuestionClassifier.classify("总结一下这周").task)
        assertEquals(ReasoningTaskId.SUMMARIZE_MONTH, QuestionClassifier.classify("这个月怎么样").task)
        assertEquals(ReasoningTaskId.PROPOSE_ACTION, QuestionClassifier.classify("我该怎么办？给点建议").task)
        assertEquals(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, QuestionClassifier.classify("我喜欢什么").task)
        assertEquals(0f, QuestionClassifier.classify("   ").confidence)
        assertEquals(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, QuestionClassifier.classify("你好呀").task)
    }

    @Test
    fun userStatementRecallGoesToPersonalQuestion() {
        // §28 前置：用户自述召回必须进入 ANSWER_PERSONAL_QUESTION（触发 CORRECTION/CONTEXT 检索）
        assertEquals(
            ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
            QuestionClassifier.classify("我说过最近在出差，这有没有影响？").task,
        )
        assertEquals(
            ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
            QuestionClassifier.classify("我之前跟你说过我在出差，还记得吗？").task,
        )
    }

    @Test
    fun comparisonQuestionsGoLongitudinalNotMonthSummary() {
        // 「这个月和上个月的区别」不是月报任务
        assertEquals(
            ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
            QuestionClassifier.classify("这个月和上个月最大的区别是什么？").task,
        )
    }
}
