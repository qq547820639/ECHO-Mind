package com.yunjue.echo.mind.memory

import com.yunjue.echo.mind.model.EchoMemory
import com.yunjue.echo.mind.model.MemoryType
import com.yunjue.echo.mind.model.RetentionClass
import com.yunjue.echo.mind.ports.CorrectionMemoryWriter
import com.yunjue.echo.mind.ports.EchoMemoryReader
import com.yunjue.echo.mind.ports.EchoMemoryWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 76 §80/§75 — Personal Correction 环路捕获段锚点：
 * 画像纠错 → CORRECTION 写入（用户自述最高置信）；
 * 对话「像我」→ USER_CONFIRMED（层标签真值，不得冒充纠错）；
 * 对话「不太像」→ 纠错 + 原因 + 原判断；
 * ERA 31 R18（§22 Correction Reuse）：上下文类原因同时写入 CONTEXT 记忆（3 天同 kind 去重）。
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
        override suspend fun pin(id: String, now: Long) = Unit
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

    private class FakeMemoryReader(private val memories: List<EchoMemory> = emptyList()) : EchoMemoryReader {
        override suspend fun memoriesByType(type: MemoryType): List<EchoMemory> =
            memories.filter { it.type == type }
    }

    private fun contextMemory(id: String, kind: String, createdAt: Long) = EchoMemory(
        id = id, userId = "u1", type = MemoryType.CONTEXT,
        content = contextExceptionContent(kind, note = "", date = null),
        source = "user-feedback", confidence = 1f, createdAt = createdAt, lastConfirmedAt = 0L,
        importance = 70, retentionClass = RetentionClass.LONG_TERM, provenance = "context-from-correction:v1",
    )

    private fun service(
        memory: FakeMemoryWriter = FakeMemoryWriter(),
        correction: FakeCorrectionWriter = FakeCorrectionWriter(),
        reader: FakeMemoryReader = FakeMemoryReader(),
    ) = Triple(memory, correction, EchoCorrectionService(memory, correction, reader))

    @Test
    fun portraitCorrectionWritesCorrectionMemory() = runBlocking {
        val (memory, correction, service) = service()
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
        val (memory, correction, service) = service()
        service.recordConversationFeedback(question = "为什么今天不一样？", answer = "因为屏幕更碎。", like = true)
        val (type, content, confidence) = memory.recorded.single()
        assertEquals(MemoryType.USER_CONFIRMED, type)
        assertTrue(content.contains("像我"))
        assertEquals(1f, confidence)
        assertTrue(correction.corrections.isEmpty())
    }

    @Test
    fun conversationDislikeWritesCorrectionWithStatement() = runBlocking {
        val (memory, correction, service) = service()
        service.recordConversationFeedback(
            question = "为什么今天不一样？",
            answer = "因为屏幕更碎。",
            like = false,
            reason = "只是很专注",
        )
        assertTrue(memory.recorded.isEmpty())
        val (_, reason, statement) = correction.corrections.single()
        assertEquals("只是很专注", reason)
        assertEquals("因为屏幕更碎。", statement)
    }

    @Test
    fun dislikeWithoutReasonFallsBackToOther() = runBlocking {
        val (_, correction, service) = service()
        service.recordConversationFeedback(question = "q", answer = "a", like = false, reason = null)
        assertEquals("其他", correction.corrections.single().second)
    }

    // ===== ERA 31 R18：纠正 → 上下文桥梁（§22 Correction Reuse 关键验收） =====

    @Test
    fun contextReasonCorrectionAlsoWritesContextMemory() = runBlocking {
        val (memory, correction, service) = service()
        service.recordConversationFeedback(
            question = "最近我是不是越来越晚？",
            answer = "是的，最近明显更晚。",
            like = false,
            reason = "旅行",
        )
        // 纠正本身照常落盘
        assertEquals("旅行", correction.corrections.single().second)
        // 上下文桥梁：CONTEXT 记忆同格式落盘（kind = 纠正原因，用户自述最高置信）
        val context = memory.recorded.single { it.first == MemoryType.CONTEXT }
        assertEquals("旅行", contextExceptionInfo(context.second)?.kind)
        assertEquals(1f, context.third)
    }

    @Test
    fun nonContextReasonDoesNotWriteContextMemory() = runBlocking {
        val (memory, _, service) = service()
        service.recordConversationFeedback(question = "q", answer = "a", like = false, reason = "不想说")
        assertTrue("非上下文原因不建上下文", memory.recorded.isEmpty())
    }

    @Test
    fun portraitCorrectionWithContextReasonAlsoBridges() = runBlocking {
        val (memory, _, service) = service()
        service.recordPortraitCorrection(date = "2026-08-15", reason = "假期", originalStatement = "今天开始得晚。")
        val context = memory.recorded.single { it.first == MemoryType.CONTEXT }
        val info = contextExceptionInfo(context.second)!!
        assertEquals("假期", info.kind)
        assertEquals("2026-08-15", info.date)
    }

    @Test
    fun sameKindContextWithinThreeDaysIsNotDuplicated() = runBlocking {
        val now = System.currentTimeMillis()
        // 昨天已有「旅行」上下文 → 今天再纠正「旅行」不重复建上下文
        val existing = FakeMemoryReader(listOf(contextMemory("ctx_1", "旅行", now - 24 * 60 * 60_000L)))
        val (memory, _, service) = service(reader = existing)
        service.recordConversationFeedback(question = "q", answer = "a", like = false, reason = "旅行")
        assertTrue("3 天内同 kind 不重复建上下文", memory.recorded.none { it.first == MemoryType.CONTEXT })

        // 10 天前的旧上下文 → 新周期纠正会新建
        val stale = FakeMemoryReader(listOf(contextMemory("ctx_1", "旅行", now - 10 * 24 * 60 * 60_000L)))
        val (memory2, _, service2) = service(reader = stale)
        service2.recordConversationFeedback(question = "q", answer = "a", like = false, reason = "旅行")
        assertTrue("新周期纠正应新建上下文", memory2.recorded.any { it.first == MemoryType.CONTEXT })
    }

    @Test
    fun likeNeverCreatesContextMemory() = runBlocking {
        val (memory, _, service) = service()
        service.recordConversationFeedback(question = "q", answer = "a", like = true, reason = null)
        assertTrue(memory.recorded.none { it.first == MemoryType.CONTEXT })
    }
}
