package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.ContextRanker
import com.yunjue.echo.mind.intelligence.EchoContextCompiler
import com.yunjue.echo.mind.intelligence.EchoContextRetriever
import com.yunjue.echo.mind.intelligence.QuestionClassifier
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.ports.EchoMemoryReader
import com.yunjue.echo.mind.ports.ObservationEvidenceSource
import com.yunjue.echo.mind.model.DailyPortraitDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * ERA 22 §28 — Correction 必须明显改变下一次回答：
 *
 * 第一次：ECHO 判断 A（出差窗口内「越来越早」）；
 * 用户纠正：「不太像，因为最近在出差。」；
 * 下一次相似问题：Context 必须检索到该 Correction/Context，
 * 并且编译出的上下文（给 AI 的证据）随之改变。
 */
class QaCorrectionReuseTest {

    private val timeline = QaTimeline(QaProfiles.D_TRAVEL)

    /** 可注入记忆的假 MemoryReader（端口实现）。 */
    private class FakeMemoryReader : EchoMemoryReader {
        val store = linkedMapOf<MemoryType, MutableList<EchoMemory>>()
        override suspend fun memoriesByType(type: MemoryType): List<EchoMemory> =
            store[type].orEmpty().toList()
    }

    private class FakeObservationSource(private val timeline: QaTimeline, private val dayIndex: Int) :
        ObservationEvidenceSource {
        override suspend fun computeToday(userId: String, today: LocalDate, zoneId: ZoneId): DailyPortraitDto? =
            timeline.portraitFor(dayIndex)
        override suspend fun computeTimeline(
            userId: String,
            days: Int,
            endDate: LocalDate,
            zoneId: ZoneId,
        ): List<DailyPortraitDto> =
            timeline.portraitsUpTo(dayIndex).takeLast(days)
    }

    @Test
    fun correctionIsRetrievedAndChangesCompiledContext() = runBlocking {
        val reader = FakeMemoryReader()
        val source = FakeObservationSource(timeline, 28) // 出差窗口 Day 20-27 刚结束
        val retriever = EchoContextRetriever(source, reader) { "qa-user" }

        val question = "最近我是不是越来越晚？"
        val task = QuestionClassifier.classify(question).task

        // ===== 第一次回答：无纠正 → 证据只有画像 =====
        val before = retriever.retrieve(task)
        val compiledBefore = EchoContextCompiler.compile(
            task,
            ContextRanker.rank(task, before).map { it.item },
            question = question,
        )
        assertTrue("第一次证据非空（有画像）", before.isNotEmpty())
        assertFalse("第一次编译上下文不得包含「出差」（尚无记忆）", compiledBefore.userContent.contains("出差"))

        // ===== 用户纠正 → 写入 Correction + Context 记忆 =====
        val correction = QaRetrievalEval.travelScenarioMemories(28)
            .first { it.id == QaRetrievalEval.MEM_CORRECTION_ID }
        val context = QaRetrievalEval.travelScenarioMemories(28)
            .first { it.id == QaRetrievalEval.MEM_CONTEXT_ID }
        reader.store[MemoryType.CORRECTION] = mutableListOf(correction)
        reader.store[MemoryType.CONTEXT] = mutableListOf(context)

        // ===== 下一次相似问题：必须检索到纠正与上下文 =====
        val after = retriever.retrieve(task)
        val rankedAfter = ContextRanker.rank(task, after)
        val topIds = rankedAfter.take(5).map { it.item.id }
        assertTrue("下一次必须检索到 Correction（top5=$topIds）", QaRetrievalEval.MEM_CORRECTION_ID in topIds)
        assertTrue("下一次必须检索到 Context（top5=$topIds）", QaRetrievalEval.MEM_CONTEXT_ID in topIds)
        assertEquals(
            "纠正证据必须排第 1",
            QaRetrievalEval.MEM_CORRECTION_ID,
            rankedAfter.firstOrNull { it.item.id.isNotBlank() }?.item?.id,
        )

        val compiledAfter = EchoContextCompiler.compile(
            task,
            rankedAfter.map { it.item },
            question = question,
        )
        assertTrue("纠正后编译上下文必须包含「出差」（${compiledAfter.userContent.take(200)}）",
            compiledAfter.userContent.contains("出差"))
        assertTrue("纠正后编译上下文必须包含用户原话（'不太像'）",
            compiledAfter.userContent.contains("不太像"))
        // 数据源清单同步变化
        assertTrue(
            "usedSources 必须包含 USER_CORRECTIONS",
            compiledAfter.usedSources.contains(com.yunjue.echo.mind.intelligence.DataSourceCategory.USER_CORRECTIONS),
        )
        assertTrue(
            "usedSources 必须包含 CONTEXT_EXCEPTIONS",
            compiledAfter.usedSources.contains(com.yunjue.echo.mind.intelligence.DataSourceCategory.CONTEXT_EXCEPTIONS),
        )
    }

    @Test
    fun sameQuestionLaterStillCarriesCorrection() = runBlocking {
        val reader = FakeMemoryReader()
        val source = FakeObservationSource(timeline, 90) // 出差窗口早已结束
        reader.store[MemoryType.CORRECTION] = mutableListOf(
            QaRetrievalEval.travelScenarioMemories(90).first { it.id == QaRetrievalEval.MEM_CORRECTION_ID },
        )
        reader.store[MemoryType.CONTEXT] = mutableListOf(
            QaRetrievalEval.travelScenarioMemories(90).first { it.id == QaRetrievalEval.MEM_CONTEXT_ID },
        )
        val retriever = EchoContextRetriever(source, reader) { "qa-user" }
        val task = QuestionClassifier.classify("我之前跟你说过我在出差，还记得吗？").task
        assertEquals(
            com.yunjue.echo.mind.intelligence.ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
            task,
        )
        val evidence = retriever.retrieve(task)
        val ranked = ContextRanker.rank(task, evidence)
        val topIds = ranked.take(5).map { it.item.id }
        assertTrue("Day 90 问出差仍检索到纠正（top5=$topIds）", QaRetrievalEval.MEM_CORRECTION_ID in topIds)
        assertTrue("Day 90 问出差仍检索到上下文（top5=$topIds）", QaRetrievalEval.MEM_CONTEXT_ID in topIds)
    }
}
