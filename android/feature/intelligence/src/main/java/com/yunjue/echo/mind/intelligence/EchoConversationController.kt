package com.yunjue.echo.mind.intelligence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v3 §15/§16 — EchoConversationController：Ask ECHO 整条链的领域控制器。
 *
 * 链：user question → Reasoning Task → Context Retriever → Context Compiler →
 * Provider → Validation → UI result → feedback。
 * Screen 不得自己完成整条链；状态机（§16）：IDLE / COMPILING_CONTEXT /
 * WAITING_PROVIDER / COMPLETE / FAILED / FALLBACK。
 * 依赖注入为函数（JVM 可测；生产接 EchoContextRetriever + AiNarrativeService）。
 */
enum class ConversationPhase {
    IDLE,
    COMPILING_CONTEXT,
    WAITING_PROVIDER,
    COMPLETE,
    FAILED,
    FALLBACK,
}

/** 用户可见相位文案（普通用户人话；技术相位不进 UI）。 */
fun conversationPhaseText(phase: ConversationPhase): String = when (phase) {
    ConversationPhase.IDLE -> ""
    ConversationPhase.COMPILING_CONTEXT -> "正在整理你的节律数据…"
    ConversationPhase.WAITING_PROVIDER -> "正在思考…"
    ConversationPhase.COMPLETE -> ""
    ConversationPhase.FALLBACK -> ""
    ConversationPhase.FAILED -> "暂时无法回答"
}

/** 一问一答（含依据来源与最终相位；会话仅存内存，Memory ≠ 聊天记录）。 */
data class ConversationTurn(
    val id: Long,
    val question: String,
    val answer: String,
    val sources: List<DataSourceCategory>,
    val phase: ConversationPhase,
)

class EchoConversationController(
    private val retrieve: suspend (ReasoningTaskId) -> List<EvidenceItem>,
    private val answer: suspend (String, List<EvidenceItem>, List<EvidenceItem>) -> AiNarrativeService.NarrativeResult,
) {
    private val _turns = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val turns: StateFlow<List<ConversationTurn>> = _turns

    private val _phase = MutableStateFlow(ConversationPhase.IDLE)
    val phase: StateFlow<ConversationPhase> = _phase

    private var nextId = 0L

    /** 提问（完整链路；任何失败都产出一条诚实降级的回答，用户永不面对空白）。 */
    suspend fun ask(question: String): ConversationTurn {
        val q = question.trim()
        _phase.value = ConversationPhase.COMPILING_CONTEXT
        val evidence = runCatching { retrieve(ReasoningTaskId.ANSWER_PERSONAL_QUESTION) }
            .getOrDefault(emptyList())
        _phase.value = ConversationPhase.WAITING_PROVIDER
        val history = _turns.value.takeLast(4).flatMap { turn ->
            listOf(
                EvidenceItem(DataSourceCategory.CONVERSATION_HISTORY, "我们的对话", "问：${turn.question}"),
                EvidenceItem(DataSourceCategory.CONVERSATION_HISTORY, "我们的对话", "答：${turn.answer}"),
            )
        }
        val result = answer(q, evidence, history)
        val phase = when (result.level) {
            NarrativeFallbackLevel.AI_NARRATIVE -> ConversationPhase.COMPLETE
            NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE -> ConversationPhase.FALLBACK
            NarrativeFallbackLevel.OBSERVATION_FACTS -> ConversationPhase.FAILED
        }
        val turn = ConversationTurn(
            id = nextId++,
            question = q,
            answer = result.text,
            sources = result.usedSources,
            phase = phase,
        )
        _turns.value = _turns.value + turn
        _phase.value = ConversationPhase.IDLE
        return turn
    }

    /** 会话重置（不持久化，不进 Memory）。 */
    fun clear() {
        _turns.value = emptyList()
        _phase.value = ConversationPhase.IDLE
    }
}
