package com.yunjue.echo.mind.memory

import com.yunjue.echo.mind.ports.CorrectionMemoryWriter
import com.yunjue.echo.mind.ports.EchoMemoryWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 76 §80/§75 — Personal Correction 环路捕获段锚点：
 * 画像纠错 → CORRECTION 写入（用户自述最高置信）；
 * 对话「像我」→ USER_CONFIRMED（层标签真值，不得冒充纠错）；
 * 对话「不太像」→ 纠错 + 原因 + 原判断。
 */
class EchoCorrectionServiceTest {

    private class FakeMemoryWriter : EchoMemoryWriter {
        val recorded = mutableListOf<Triple<MemoryType, String, Float>>()
        override suspend fun record(
            type: MemoryType,
            content: String,
            source: String,
            provenance: String,
            confidence: Float,
            importance: Int,
            now: Long,
        ): String {
            recorded += Triple(type, content, confidence)
            return "id_${recorded.size}"
        }

        override suspend fun confirm(id: String, now: Long) = Unit
        override suspend fun forget(id: String) = Unit
        override suspend fun edit(id: String, content: String, now: Long) = Unit
    }

    private class FakeCorrectionWriter : CorrectionMemoryWriter {
        val corrections = mutableListOf<Triple<String, String, String?>>()
        override suspend fun recordCorrection(
            date: String,
            reason: String,
            originalStatement: String?,
            now: Long,
        ): String {
            corrections += Triple(date, reason, originalStatement)
            return "corr_${corrections.size}"
        }
    }

    @Test
    fun portraitCorrectionWritesCorrectionMemory() = runBlocking {
        val memory = FakeMemoryWriter()
        val correction = FakeCorrectionWriter()
        val service = EchoCorrectionService(memory, correction)
        service.recordPortraitCorrection(
            date = "2026-08-15",
            reason = "出差",
            originalStatement = "今天节奏和平时一样。",
        )
        assertEquals(0, memory.recorded.size)
        val (date, reason, statement) = correction.corrections.single()
        assertEquals("2026-08-15", date)
        assertEquals("出差", reason)
        assertEquals("今天节奏和平时一样。", statement)
    }

    @Test
    fun conversationLikeWritesUserConfirmedNotCorrection() = runBlocking {
        val memory = FakeMemoryWriter()
        val correction = FakeCorrectionWriter()
        val service = EchoCorrectionService(memory, correction)
        service.recordConversationFeedback(question = "为什么今天不一样？", answer = "因为屏幕更碎。", like = true)
        val (type, content, confidence) = memory.recorded.single()
        assertEquals(MemoryType.USER_CONFIRMED, type)
        assertTrue(content.contains("像我"))
        assertEquals(1f, confidence)
        assertTrue(correction.corrections.isEmpty())
    }

    @Test
    fun conversationDislikeWritesCorrectionWithStatement() = runBlocking {
        val memory = FakeMemoryWriter()
        val correction = FakeCorrectionWriter()
        val service = EchoCorrectionService(memory, correction)
        service.recordConversationFeedback(
            question = "为什么今天不一样？",
            answer = "因为屏幕更碎。",
            like = false,
            reason = "出差",
        )
        assertTrue(memory.recorded.isEmpty())
        val (_, reason, statement) = correction.corrections.single()
        assertEquals("出差", reason)
        assertEquals("因为屏幕更碎。", statement)
    }

    @Test
    fun dislikeWithoutReasonFallsBackToOther() = runBlocking {
        val memory = FakeMemoryWriter()
        val correction = FakeCorrectionWriter()
        val service = EchoCorrectionService(memory, correction)
        service.recordConversationFeedback(question = "q", answer = "a", like = false, reason = null)
        assertEquals("其他", correction.corrections.single().second)
    }
}
