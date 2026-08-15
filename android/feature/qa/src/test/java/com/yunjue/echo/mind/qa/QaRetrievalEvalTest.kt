package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.ContextRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 22 §27 — Context Retrieval Recall 离线 eval：
 * Query → Candidate evidence → Expected top-k。
 *
 * 语料：D（出差）Day 90 的真实 fixture 画像时间线（60 天）+ 今日画像
 * + 出差场景记忆（纠正/上下文/用户确认/偏好），输入顺序确定性打乱。
 *
 * 门：Recall@5 = 1.0、CorrectionRecall = 1.0、ContextExceptionRecall = 1.0、
 * UserConfirmedRecall = 1.0、排序与输入顺序无关。
 */
class QaRetrievalEvalTest {

    private val timeline = QaTimeline(QaProfiles.D_TRAVEL)

    /** 需要记忆证据的用例（出差/纠正/确认类）。 */
    private val memoryCases: List<QaQuestionBank.QuestionCase> = listOf(
        QaQuestionBank.byId("q038"),
        QaQuestionBank.byId("q039"),
        QaQuestionBank.byId("q040"),
        QaQuestionBank.byId("q041"),
        QaQuestionBank.byId("q042"),
        QaQuestionBank.byId("q043"),
    )

    @Test
    fun recallMetricsMeetGates() {
        val metrics = QaRetrievalEval.measure(
            cases = memoryCases,
            corpusFor = QaRetrievalEval.corpusWithMemories(timeline),
            expectedFor = QaRetrievalEval::memoryExpectedFor,
        )
        println("recall: $metrics")
        assertEquals("Recall@5 必须 1.0", 1f, metrics.recallAt5)
        assertEquals("Recall@10 必须 1.0", 1f, metrics.recallAt10)
        assertEquals("CorrectionRecall 必须 1.0", 1f, metrics.correctionRecall)
        assertEquals("ContextExceptionRecall 必须 1.0", 1f, metrics.contextExceptionRecall)
        assertEquals("UserConfirmedRecall 必须 1.0", 1f, metrics.userConfirmedRecall)
        assertTrue("排序必须与输入顺序无关", metrics.orderInvariant)
    }

    @Test
    fun correctionsOutrankEverythingInEveryMemoryTask() {
        // 用户纠正永远最高（§68 层级；任何允许 USER_CORRECTIONS 的任务）
        val corpus = QaRetrievalEval.corpus(timeline, 90, QaRetrievalEval.travelScenarioMemories(90))
        for (task in com.yunjue.echo.mind.intelligence.ReasoningTaskId.entries) {
            val ranked = ContextRanker.rank(task, corpus)
            val top = ranked.firstOrNull { it.item.id.isNotBlank() }
            // 凡语料中存在纠正证据，任何任务的 top 非空 id 都必须是纠正（tier 5 最高）
            assertEquals("任务 $task 下纠正必须第一", QaRetrievalEval.MEM_CORRECTION_ID, top?.item?.id)
        }
    }

    @Test
    fun baselineAndPortraitHistoryRankAboveTodayForLongitudinal() {
        val corpus = QaRetrievalEval.corpus(timeline, 90)
        val ranked = ContextRanker.rank(
            com.yunjue.echo.mind.intelligence.ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
            corpus,
        )
        // 纵向任务：画像历史（含 task affinity 0.5）应排在今日聚合之前
        val firstHistory = ranked.firstOrNull { it.item.category == com.yunjue.echo.mind.intelligence.DataSourceCategory.PORTRAIT_HISTORY }
        val firstToday = ranked.firstOrNull { it.item.category == com.yunjue.echo.mind.intelligence.DataSourceCategory.TODAY_AGGREGATE }
        assertTrue("纵向任务下历史证据应排在今日之前", firstHistory != null)
        if (firstToday != null) {
            assertTrue("历史先于今日", ranked.indexOf(firstHistory) < ranked.indexOf(firstToday))
        }
    }
}
