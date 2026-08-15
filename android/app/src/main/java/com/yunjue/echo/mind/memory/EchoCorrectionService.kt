package com.yunjue.echo.mind.memory

import com.yunjue.echo.mind.ports.CorrectionMemoryWriter
import com.yunjue.echo.mind.ports.EchoMemoryWriter
import java.time.LocalDate

/**
 * v3 §17 — EchoCorrectionService：用户纠错统一入口。
 *
 * 「像我 / 不太像 + 原因」→ Correction Memory（UI 不得自己创建 MemoryEntity）。
 * 用户自述（Felt）永远最高置信：confidence=1，importance 高。
 */
class EchoCorrectionService(
    private val memoryWriter: EchoMemoryWriter,
    private val correctionWriter: CorrectionMemoryWriter,
) {

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
            correctionWriter.recordCorrection(
                date = LocalDate.now().toString(),
                reason = reason ?: "其他",
                originalStatement = answer,
            )
        }
    }
}
