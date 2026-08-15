package com.yunjue.echo.mind.memory

import com.yunjue.echo.mind.model.MemoryType
import com.yunjue.echo.mind.ports.CorrectionMemoryWriter
import com.yunjue.echo.mind.ports.EchoMemoryReader
import com.yunjue.echo.mind.ports.EchoMemoryWriter
import java.time.LocalDate

/**
 * v3 §17 — EchoCorrectionService：用户纠错统一入口。
 *
 * 「像我 / 不太像 + 原因」→ Correction Memory（UI 不得自己创建 MemoryEntity）。
 * 用户自述（Felt）永远最高置信：confidence=1，importance 高。
 *
 * ERA 31 R18（§22 Correction Reuse 关键验收）：上下文类原因（工作/旅行/假期/身体不舒服/
 * 特殊事件）的「不太像」同时写入 CONTEXT 记忆——未来上下文感知回答（如「我说过最近在出差，
 * 这有没有影响？」）自然融入该上下文，而不是只剩机械回放「你之前说过…」。
 */
class EchoCorrectionService(
    private val memoryWriter: EchoMemoryWriter,
    private val correctionWriter: CorrectionMemoryWriter,
    private val memoryReader: EchoMemoryReader,
) {

    /** 上下文类纠正原因（这些原因 = 用户自述的特殊时期，进入 CONTEXT 记忆；其余只是纠正本身）。 */
    private val CONTEXT_LIKE_REASONS = setOf("工作", "旅行", "假期", "身体不舒服", "特殊事件")

    /** 同 kind 上下文去重窗口（3 天内重复纠正不重复建上下文；新周期再纠正会新建）。 */
    private val CONTEXT_DEDUP_WINDOW_MS = 3L * 24 * 60 * 60 * 1000

    /** 画像反馈纠错（Today 一句话「不太像」）。 */
    suspend fun recordPortraitCorrection(
        date: String,
        reason: String,
        originalStatement: String?,
    ) {
        correctionWriter.recordCorrection(
            date = date,
            reason = reason,
            originalStatement = originalStatement,
        )
        recordContextIfRelevant(reason, date, System.currentTimeMillis())
    }

    /** 对话回答反馈（v2 §52）：像我 = 正向确认记忆（USER_CONFIRMED，§80 层标签真值）；不太像 = 纠错 + 原因。 */
    suspend fun recordConversationFeedback(
        question: String,
        answer: String,
        like: Boolean,
        reason: String? = null,
    ) {
        if (like) {
            memoryWriter.record(
                type = MemoryType.USER_CONFIRMED,
                content = "问答反馈：像我（问：${question.take(40)}）",
                source = "user-feedback",
                provenance = "conversation-feedback:v1",
                confidence = 1f,
                importance = 60,
            )
        } else {
            val date = LocalDate.now().toString()
            correctionWriter.recordCorrection(
                date = date,
                reason = reason ?: "其他",
                originalStatement = answer,
            )
            recordContextIfRelevant(reason ?: "其他", date, System.currentTimeMillis())
        }
    }

    /**
     * ERA 31 R18：上下文类原因 → CONTEXT 记忆（用户自述的特殊时期）。
     * 3 天内同 kind 已有非删除 CONTEXT → 跳过（防重复反馈堆叠同上下文）；内容与
     * `recordContextException` 同格式（kind 直存纠正原因，Journey/What ECHO Knows 同词表展示）。
     */
    private suspend fun recordContextIfRelevant(reason: String, date: String, now: Long) {
        if (reason !in CONTEXT_LIKE_REASONS) return
        val existing = memoryReader.memoriesByType(MemoryType.CONTEXT).filter { !it.deleted }
        val recentSameKind = existing.any { memory ->
            contextExceptionInfo(memory.content)?.kind == reason && now - memory.createdAt < CONTEXT_DEDUP_WINDOW_MS
        }
        if (recentSameKind) return
        memoryWriter.record(
            type = MemoryType.CONTEXT,
            content = contextExceptionContent(reason, note = "", date = date),
            source = "user-feedback",
            provenance = "context-from-correction:v1",
            confidence = 1f, // 用户自述 = 最高置信来源
            importance = 70,
            now = now,
        )
    }
}
