package com.yunjue.echo.mind.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 15.5 §105 — Memory Maturity 测试矩阵：
 * decay / expiry / reinforce / retrieval rank / derived pattern。
 */
class MemoryMaturityTest {

    private val now = 1_000_000L * 86_400_000L

    private fun memory(
        type: MemoryType,
        content: String,
        importance: Int = 50,
        confidence: Float = 0.7f,
        lastConfirmedAt: Long = now,
        retentionClass: RetentionClass = RetentionClass.LONG_TERM,
        deleted: Boolean = false,
    ) = EchoMemory(
        id = "id-${type.name}-${content.hashCode()}",
        userId = "u",
        type = type,
        content = content,
        source = "t",
        confidence = confidence,
        createdAt = now,
        lastConfirmedAt = lastConfirmedAt,
        importance = importance,
        retentionClass = retentionClass,
        provenance = "t:v1",
        deleted = deleted,
    )

    // ===== §105 decay / expiry / reinforce =====

    @Test
    fun expiryBasedOnRetention() {
        val ephemeral = memory(MemoryType.TEMPORARY_INTERPRETATION, "临时解释", retentionClass = RetentionClass.EPHEMERAL)
        assertTrue(shouldForget(ephemeral, now + 8L * 86_400_000L)) // 7 天保留后过期
        val pinned = memory(MemoryType.USER_CONFIRMED, "固定", retentionClass = RetentionClass.USER_PINNED)
        assertTrue(!shouldForget(pinned, now + 3650L * 86_400_000L))
    }

    @Test
    fun decayScoreDecreasesOverTime() {
        val m = memory(MemoryType.OBSERVATION, "观察", importance = 80)
        val fresh = memoryDecayScore(m, now)
        val stale = memoryDecayScore(m, now + 300L * 86_400_000L)
        assertTrue(fresh > stale)
        assertEquals(0f, memoryDecayScore(m.copy(deleted = true), now))
    }

    @Test
    fun reinforceBumpsImportanceAndRefreshesConfirmation() {
        val reinforced = reinforceMemory(memory(MemoryType.USER_CONFIRMED, "确认", importance = 90), now + 1000L)
        assertEquals(100, reinforced.importance) // 上限 100
        assertEquals(now + 1000L, reinforced.lastConfirmedAt)
    }

    // ===== §105 retrieval rank（§75 优先级） =====

    @Test
    fun retrievalRankPutsConfirmedCorrectionContextFirst() {
        val correction = memory(MemoryType.CORRECTION, "纠正", importance = 30)
        val context = memory(MemoryType.CONTEXT, "出差", importance = 30)
        val confirmed = memory(MemoryType.USER_CONFIRMED, "确认", importance = 30)
        val observation = memory(MemoryType.OBSERVATION, "观察", importance = 95)
        val ranked = rankMemories(listOf(observation, context, correction, confirmed), now)
        assertEquals(setOf("确认", "纠正"), setOf(ranked[0].content, ranked[1].content)) // 两类并列最高
        assertTrue(ranked.indexOfFirst { it.type == MemoryType.CONTEXT } < ranked.indexOfFirst { it.type == MemoryType.OBSERVATION })
        assertTrue(ranked.last().type == MemoryType.OBSERVATION || ranked.last().deleted)
    }

    @Test
    fun deletedSinksToBottom() {
        val live = memory(MemoryType.OBSERVATION, "活")
        val dead = memory(MemoryType.OBSERVATION, "删", deleted = true)
        val ranked = rankMemories(listOf(dead, live), now)
        assertEquals(live, ranked[0])
        assertEquals(dead, ranked[1])
    }

    // ===== §105 derived pattern（§77） =====

    @Test
    fun derivedPatternRequiresRepetition() {
        val observations = listOf(
            memory(MemoryType.OBSERVATION, "周一晚睡"),
            memory(MemoryType.OBSERVATION, "周一晚睡"),
            memory(MemoryType.OBSERVATION, "周一晚睡"),
            memory(MemoryType.OBSERVATION, "偶尔早睡"), // 只出现一次
        )
        val patterns = derivePatterns(observations, minOccurrences = 3)
        assertEquals(1, patterns.size)
        assertEquals("周一晚睡", patterns[0].content)
        assertEquals(3, patterns[0].evidenceCount)
        assertTrue(patterns[0].confidence in 0.5f..0.95f)
    }

    @Test
    fun derivedPatternIgnoresShortAndDeleted() {
        val observations = listOf(
            memory(MemoryType.OBSERVATION, "abc"), // 过短
            memory(MemoryType.OBSERVATION, "反复出现的事实", deleted = true),
            memory(MemoryType.OBSERVATION, "反复出现的事实"),
            memory(MemoryType.OBSERVATION, "反复出现的事实"),
        )
        assertEquals(0, derivePatterns(observations, minOccurrences = 3).size)
    }

    @Test
    fun patternConfidenceGrowsWithEvidence() {
        val three = derivePatterns(List(3) { memory(MemoryType.OBSERVATION, "同一个事实") }, minOccurrences = 3)
        val six = derivePatterns(List(6) { memory(MemoryType.OBSERVATION, "同一个事实") }, minOccurrences = 3)
        assertTrue(six[0].confidence > three[0].confidence) // 更多证据 → 更高置信
    }
}
