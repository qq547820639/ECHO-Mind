package com.yunjue.echo.mind.intelligence

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 58（ADR-059 第 4 轮）——EchoConversationController 多轮状态机锚点：
 * 全链编排 / 失败诚实降级（永不空白）/ 4 轮历史窗口滚动 / 检索异常不破链 / 相位迁移 / 会话重置。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EchoConversationControllerTest {

    private class Harness {
        val historySnapshots = mutableListOf<List<String>>()
        var answerResult: NarrativeResultLevel = NarrativeResultLevel.AI
        var retrieveThrows = false

        fun controller() = EchoConversationController(
            retrieve = { task ->
                if (retrieveThrows) throw IllegalStateException("db fail")
                listOf(
                    EvidenceItem(
                        category = DataSourceCategory.TODAY_AGGREGATE,
                        label = "今天",
                        text = "活跃起点偏晚",
                        id = "evt_today",
                    ),
                )
            },
            answer = { question, evidence, history ->
                historySnapshots += history.map { it.text }
                val result = when (answerResult) {
                    NarrativeResultLevel.AI -> AiNarrativeService.NarrativeResult(
                        level = NarrativeFallbackLevel.AI_NARRATIVE,
                        text = "这是基于证据的回答：$question",
                        usedSources = evidence.map { it.category }.distinct(),
                    )
                    NarrativeResultLevel.DETERMINISTIC -> AiNarrativeService.NarrativeResult(
                        level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                        text = "确定性叙事",
                        usedSources = emptyList(),
                    )
                    NarrativeResultLevel.FACTS -> AiNarrativeService.NarrativeResult(
                        level = NarrativeFallbackLevel.OBSERVATION_FACTS,
                        text = "我现在还不能回答这个问题：还没有连接 AI，或者当前没有网络。",
                        usedSources = emptyList(),
                    )
                }
                result
            },
        )
    }

    private enum class NarrativeResultLevel { AI, DETERMINISTIC, FACTS }

    @Test
    fun askRunsFullChainAndRecordsCompleteTurn() = runTest {
        val h = Harness()
        val controller = h.controller()
        val turn = controller.ask("我最近是不是越来越晚？")

        assertEquals(ConversationPhase.COMPLETE, turn.phase)
        assertTrue(turn.answer.contains("基于证据"))
        assertTrue("依据来源应携带检索到的 TODAY_AGGREGATE", turn.sources.contains(DataSourceCategory.TODAY_AGGREGATE))
        assertEquals(1, controller.turns.value.size)
        assertEquals(ConversationPhase.IDLE, controller.phase.value)
    }

    @Test
    fun failureProducesHonestDegradedTurn() = runTest {
        val h = Harness().apply { answerResult = NarrativeResultLevel.FACTS }
        val controller = h.controller()
        val turn = controller.ask("随便问一句")

        assertEquals("provider 失败 → FAILED 相位（诚实降级）", ConversationPhase.FAILED, turn.phase)
        assertTrue("回答永不空白", turn.answer.isNotBlank())
        assertEquals("失败也记录这一轮", 1, controller.turns.value.size)
    }

    @Test
    fun historyWindowRollsAtFourTurns() = runTest {
        val h = Harness()
        val controller = h.controller()
        repeat(5) { i -> controller.ask("问题 $i") }
        controller.ask("问题 5")

        // 第 6 次 ask 的 history 应只含最近 4 轮（8 条：4 问 4 答）
        val sixthHistory = h.historySnapshots.last()
        assertEquals("4 轮窗口 = 8 条历史", 8, sixthHistory.size)
        assertTrue("窗口应含最近一轮的问题", sixthHistory.any { it.contains("问题 4") })
        assertTrue("窗口应含最近一轮的回答", sixthHistory.any { it.contains("基于证据") })
        assertTrue("窗口外的第 0 轮应被滚动掉", sixthHistory.none { it.contains("问题 0") })
    }

    @Test
    fun retrieveFailureDoesNotBreakChain() = runTest {
        val h = Harness().apply { retrieveThrows = true }
        val controller = h.controller()
        val turn = controller.ask("检索会失败吗")

        // 检索失败 → 空证据 → answer 仍被调用（诚实降级），不抛异常
        assertTrue(turn.answer.isNotBlank())
        assertEquals(1, controller.turns.value.size)
    }

    @Test
    fun deterministicFallbackMapsToFallbackPhase() = runTest {
        val h = Harness().apply { answerResult = NarrativeResultLevel.DETERMINISTIC }
        val turn = h.controller().ask("问题")
        assertEquals(ConversationPhase.FALLBACK, turn.phase)
    }

    @Test
    fun clearResetsTurnsAndPhase() = runTest {
        val h = Harness()
        val controller = h.controller()
        controller.ask("一")
        controller.ask("二")
        assertEquals(2, controller.turns.value.size)

        controller.clear()
        assertEquals(0, controller.turns.value.size)
        assertEquals(ConversationPhase.IDLE, controller.phase.value)
    }
}
