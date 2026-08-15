package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.ContextRanker
import com.yunjue.echo.mind.intelligence.EvidenceAssembler
import com.yunjue.echo.mind.intelligence.EvidenceItem
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass

/**
 * ERA 22 §27/§28 — Context Retrieval 离线 eval 基础设施。
 *
 * Query → Candidate evidence → Expected top-k：
 * 评估 Recall@K / CorrectionRecall / ContextExceptionRecall / UserConfirmedRecall，
 * 以及「纠正后下一次相似问题必须检索到 Correction/Context」。
 */
object QaRetrievalEval {

    const val MEM_CORRECTION_ID = "mem_corr_travel"
    const val MEM_CONTEXT_ID = "mem_ctx_travel"
    const val MEM_CONFIRMED_ID = "mem_conf_weekend"
    const val MEM_PREFERENCE_ID = "mem_pref_quiet"

    /** D（出差）场景的固定记忆集：纠正 + 上下文 + 用户确认 + 偏好。 */
    fun travelScenarioMemories(dayIndex: Int): List<EchoMemory> {
        val epochMs = java.time.Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
        val dayMs = dayIndex * 86_400_000L
        return listOf(
            EchoMemory(
                id = MEM_CORRECTION_ID,
                userId = "qa-user",
                type = MemoryType.CORRECTION,
                content = "不太像——我最近在出差，白天的节奏更早是因为出差，不是长期变化。",
                source = "ask-echo-correction",
                confidence = 0.9f,
                createdAt = epochMs + dayMs,
                lastConfirmedAt = epochMs + dayMs,
                importance = 80,
                retentionClass = RetentionClass.LONG_TERM,
                provenance = "user-correction:v1",
            ),
            EchoMemory(
                id = MEM_CONTEXT_ID,
                userId = "qa-user",
                type = MemoryType.CONTEXT,
                content = "我在出差：这段时间的活跃节奏会和平常不同。",
                source = "ask-echo-context",
                confidence = 0.9f,
                createdAt = epochMs + dayMs,
                lastConfirmedAt = epochMs + dayMs,
                importance = 70,
                retentionClass = RetentionClass.LONG_TERM,
                provenance = "user-context:v1",
            ),
            EchoMemory(
                id = MEM_CONFIRMED_ID,
                userId = "qa-user",
                type = MemoryType.USER_CONFIRMED,
                content = "周末确实会晚起。",
                source = "ask-echo-confirm",
                confidence = 0.95f,
                createdAt = epochMs + dayMs,
                lastConfirmedAt = epochMs + dayMs,
                importance = 60,
                retentionClass = RetentionClass.LONG_TERM,
                provenance = "user-confirmed:v1",
            ),
            EchoMemory(
                id = MEM_PREFERENCE_ID,
                userId = "qa-user",
                type = MemoryType.PREFERENCE,
                content = "喜欢安静一点的视觉。",
                source = "me-preferences",
                confidence = 0.8f,
                createdAt = epochMs + dayMs,
                lastConfirmedAt = epochMs + dayMs,
                importance = 40,
                retentionClass = RetentionClass.LONG_TERM,
                provenance = "preference:v1",
            ),
        )
    }

    /** 候选证据语料：画像时间线 + 今日画像 + 记忆（顺序故意打乱——排序必须与输入顺序无关）。 */
    fun corpus(
        timeline: QaTimeline,
        dayIndex: Int,
        memories: List<EchoMemory> = emptyList(),
    ): List<EvidenceItem> {
        val history = EvidenceAssembler.fromPortraitHistory(timeline.portraitsUpTo(dayIndex))
        val today = EvidenceAssembler.fromPortrait(timeline.portraitFor(dayIndex))
        val memoryEvidence = EvidenceAssembler.fromMemories(memories)
        val all = history + today + memoryEvidence
        // 确定性打乱（rank 结果必须独立于输入顺序）
        val rng = QaRng(1234L)
        return all.sortedBy { rng.nextDouble() }
    }

    /** 用例 → 期望出现在 top-k 的证据 id。 */
    fun expectedEvidenceIds(case: QaQuestionBank.QuestionCase): List<String> = buildList {
        if (case.expected.needsCorrection) add(MEM_CORRECTION_ID)
        if (case.expected.needsContextException) add(MEM_CONTEXT_ID)
        if (case.expected.needsUserConfirmed) add(MEM_CONFIRMED_ID)
    }

    data class RecallMetrics(
        val recallAt5: Float,
        val recallAt10: Float,
        val correctionRecall: Float,
        val contextExceptionRecall: Float,
        val userConfirmedRecall: Float,
        val orderInvariant: Boolean,
    )

    /** 对一批用例计算召回（corpus 由 [corpusFor] 提供；期望 id 由 [expectedFor] 提供）。 */
    fun measure(
        cases: List<QaQuestionBank.QuestionCase>,
        corpusFor: (QaQuestionBank.QuestionCase) -> List<EvidenceItem>,
        expectedFor: (QaQuestionBank.QuestionCase) -> List<String>,
    ): RecallMetrics {
        var hit5 = 0
        var hit10 = 0
        var total = 0
        var corrCases = 0
        var corrHit = 0
        var ctxCases = 0
        var ctxHit = 0
        var confCases = 0
        var confHit = 0
        var orderInvariant = true

        for (case in cases) {
            val corpus = corpusFor(case)
            val expected = expectedFor(case)
            if (expected.isEmpty()) continue
            total++
            val ranked = ContextRanker.rank(case.expected.task, corpus)
            val top5 = ranked.take(5).map { it.item.id }.toSet()
            val top10 = ranked.take(10).map { it.item.id }.toSet()
            if (top5.containsAll(expected)) hit5++
            if (top10.containsAll(expected)) hit10++
            if (MEM_CORRECTION_ID in expected) {
                corrCases++
                if (MEM_CORRECTION_ID in top5) corrHit++
            }
            if (MEM_CONTEXT_ID in expected) {
                ctxCases++
                if (MEM_CONTEXT_ID in top5) ctxHit++
            }
            if (MEM_CONFIRMED_ID in expected) {
                confCases++
                if (MEM_CONFIRMED_ID in top5) confHit++
            }
            // 顺序不变性：正序与确定性打乱序的排名一致
            val reranked = ContextRanker.rank(case.expected.task, corpus.sortedBy { it.id })
            if (reranked.map { it.item.id } != ranked.map { it.item.id }) orderInvariant = false
        }
        return RecallMetrics(
            recallAt5 = if (total > 0) hit5.toFloat() / total else 0f,
            recallAt10 = if (total > 0) hit10.toFloat() / total else 0f,
            correctionRecall = if (corrCases > 0) corrHit.toFloat() / corrCases else 0f,
            contextExceptionRecall = if (ctxCases > 0) ctxHit.toFloat() / ctxCases else 0f,
            userConfirmedRecall = if (confCases > 0) confHit.toFloat() / confCases else 0f,
            orderInvariant = orderInvariant,
        )
    }

    /** 语料构造（含出差场景记忆；dayIndex 固定 90）。 */
    fun corpusWithMemories(timeline: QaTimeline): (QaQuestionBank.QuestionCase) -> List<EvidenceItem> =
        { corpus(timeline, 90, travelScenarioMemories(90)) }

    /** 用例是否以「用户自述召回」为主（纠正/上下文/确认）。 */
    fun wantsMemory(case: QaQuestionBank.QuestionCase): Boolean =
        case.expected.needsCorrection || case.expected.needsContextException || case.expected.needsUserConfirmed

    /** 需要记忆的用例才给出期望 id（避免无记忆用例空期望被跳过导致指标虚高）。 */
    fun memoryExpectedFor(case: QaQuestionBank.QuestionCase): List<String> =
        if (wantsMemory(case)) expectedEvidenceIds(case) else emptyList()
}
