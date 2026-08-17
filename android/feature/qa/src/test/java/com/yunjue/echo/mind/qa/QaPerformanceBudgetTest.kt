package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.ContextRanker
import com.yunjue.echo.mind.journey.buildJourneyDays
import com.yunjue.echo.mind.journey.buildYearView
import com.yunjue.echo.mind.memory.rankMemories
import com.yunjue.echo.mind.presence.EchoPresenceCodec
import com.yunjue.echo.mind.presence.EchoVisualMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 27（Batch 7）— 性能/电池预算（JVM 确定性预算；设备侧由 instrumented 承接）：
 * - §53/§54 冷启动：先显示 persisted Presence 快照（解码 + 映射 = 与活体同帧，不等 Backend/AI/记忆全量）；
 * - §55 Journey 长历史：365 天窗口化（60 天时间线 + 年视图聚合），禁止全量逐日重算失控；
 * - §56 Context Retrieval：记忆/证据排序规模化（10k 候选确定性排序，预算内完成）；
 * - Dream：同一状态映射，参数有界（性能等价于 APP 表面）。
 *
 * 时间预算只作粗粒度冒烟门（阈值宽松，避免 CI 机器差异误报）；
 * 结构预算（窗口化/上限/同帧）为硬断言。
 */
class QaPerformanceBudgetTest {

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun timed(block: () -> Unit): Long {
        val start = nowMs()
        block()
        return nowMs() - start
    }

    // ===== §53/§54 冷启动：First Meaningful Presence =====

    @Test
    fun coldStartDecodesSnapshotToSameFrameAsLiveState() {
        val timeline = QaTimeline(QaProfiles.A_STABLE)
        val live = timeline.snapshotAt(90)
        val encoded = EchoPresenceCodec.encode(live.presence)
        val decoded = EchoPresenceCodec.decode(encoded)
        requireNotNull(decoded)
        // 冷启动路径：持久化快照解码后直接映射视觉参数 → 与活体完全同帧
        val coldParams = EchoVisualMapper.map(decoded, 12f)
        val liveParams = EchoVisualMapper.map(live.presence, 12f)
        assertEquals("冷启动首帧 = 活体首帧（First Meaningful Presence 语义）", liveParams, coldParams)
        // 解码不重跑身份/季节管道：1000 次解码在宽松预算内
        val decodeMs = timed { repeat(1000) { EchoPresenceCodec.decode(encoded) } }
        assertTrue("快照解码 1000 次预算 < 5s（实际 ${decodeMs}ms）", decodeMs < 5_000L)
    }

    // ===== §55 Journey 长历史 =====

    @Test
    fun yearViewOn365DaysIsBudgetedAndDeterministic() {
        val timeline = QaTimeline(QaProfiles.B_NIGHT_OWL)
        val days = buildJourneyDays(timeline.allPortraitsUpTo(365))
        assertTrue("365 天画像天数有界（窗口化，实际 ${days.size}）", days.size in 200..366)
        val view1 = buildYearView(days, emptyList(), emptyMap())
        val elapsed = timed { buildYearView(days, emptyList(), emptyMap()) }
        val view2 = buildYearView(days, emptyList(), emptyMap())
        assertEquals("年视图确定性", view1, view2)
        assertTrue("365 天年视图预算 < 10s（实际 ${elapsed}ms）", elapsed < 10_000L)
        // §55：聚合产物远小于逐日数据（canonical/period aggregation 语义）
        val shiftCount = view1.majorShifts.size
        val seasonCount = view1.seasons.size
        assertTrue("转变点/季节数是聚合产物（shifts=$shiftCount seasons=$seasonCount）",
            shiftCount + seasonCount < days.size / 10)
    }

    // ===== §56 Context Retrieval 规模化 =====

    @Test
    fun rankingTenThousandCandidatesIsBudgetedAndDeterministic() {
        val rng = QaRng(42L)
        val memories = (1..10_000).map { i ->
            com.yunjue.echo.mind.memory.EchoMemory(
                id = "m$i",
                userId = "qa-user",
                type = com.yunjue.echo.mind.memory.MemoryType.OBSERVATION,
                content = "观察内容 第 $i 条",
                source = "bench",
                confidence = rng.nextDouble().toFloat(),
                createdAt = 1_700_000_000_000L + i,
                lastConfirmedAt = 1_700_000_000_000L + i,
                importance = (rng.nextDouble() * 100).toInt(),
                retentionClass = com.yunjue.echo.mind.memory.RetentionClass.SHORT_TERM,
                provenance = "bench:v1",
            )
        }
        val now = 1_700_000_000_000L + 20_000
        val ranked1 = rankMemories(memories, now)
        val elapsed = timed { rankMemories(memories, now) }
        val ranked2 = rankMemories(memories, now)
        assertEquals("10k 记忆排序确定性", ranked1, ranked2)
        assertTrue("10k 记忆排序预算 < 5s（实际 ${elapsed}ms）", elapsed < 5_000L)
        // §56：检索必须限量（排名后取 top-k，不得全量塞给模型）
        val topK = ranked1.take(6)
        assertEquals(6, topK.size)
    }

    @Test
    fun contextRankingScalesOnEvidenceCorpus() {
        val timeline = QaTimeline(QaProfiles.D_TRAVEL)
        val corpus = QaRetrievalEval.corpus(timeline, 90, QaRetrievalEval.travelScenarioMemories(90))
        // 放大到 ~2000 条（历史画像重复注入）验证排序规模化
        val big = (1..40).flatMap { corpus }.shuffled(java.util.Random(7))
        val ranked = ContextRanker.rank(com.yunjue.echo.mind.intelligence.ReasoningTaskId.ANSWER_PERSONAL_QUESTION, big)
        assertEquals("排名结果数与输入一致", big.size, ranked.size)
        assertEquals("纠正仍排第 1（规模化不破坏优先级）", QaRetrievalEval.MEM_CORRECTION_ID, ranked.first().item.id)
        val elapsed = timed {
            ContextRanker.rank(com.yunjue.echo.mind.intelligence.ReasoningTaskId.ANSWER_PERSONAL_QUESTION, big)
        }
        assertTrue("~2000 证据排序预算 < 5s（实际 ${elapsed}ms）", elapsed < 5_000L)
    }

    // ===== Dream 性能 =====

    @Test
    fun dreamSurfaceParamsAreBoundedAndDeterministic() {
        for (profile in QaProfiles.ALL) {
            val snap = QaTimeline(profile).snapshotAt(90)
            val dream = EchoVisualMapper.map(snap.presence, 22f)
            val dream2 = EchoVisualMapper.map(snap.presence, 22f)
            assertEquals("${profile.id} Dream 映射确定性", dream, dream2)
            assertTrue("${profile.id} Dream flow 有界", dream.flowSpeed in 0f..1f)
            assertTrue("${profile.id} Dream pulse 有界", dream.pulsePeriodSeconds in 3.8f..5.6f)
        }
    }
}
