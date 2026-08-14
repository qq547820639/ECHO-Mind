package com.yunjue.echo.mind

import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass
import com.yunjue.echo.mind.memory.defaultRetentionFor
import com.yunjue.echo.mind.memory.memoryDecayScore
import com.yunjue.echo.mind.memory.memoryExpiresAt
import com.yunjue.echo.mind.memory.reinforceMemory
import com.yunjue.echo.mind.memory.retentionDaysFor
import com.yunjue.echo.mind.memory.shouldForget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 6：EchoMemory 生命周期回归（decay / reinforcement / expiry / delete）。
 */
class EchoMemoryTest {

    private val DAY = 86_400_000L
    private val NOW = 1_800_000_000_000L

    private fun memory(
        type: MemoryType = MemoryType.CONTEXT,
        retention: RetentionClass = RetentionClass.LONG_TERM,
        importance: Int = 60,
        lastConfirmedAt: Long = NOW,
    ) = EchoMemory(
        id = "m1",
        userId = "u1",
        type = type,
        content = "最近一个月在准备考试。",
        source = "user-statement",
        confidence = 1f,
        createdAt = NOW,
        lastConfirmedAt = lastConfirmedAt,
        importance = importance,
        retentionClass = retention,
        provenance = "user:v1",
    )

    @Test
    fun retentionDaysPerClass() {
        assertEquals(7L, retentionDaysFor(RetentionClass.EPHEMERAL))
        assertEquals(30L, retentionDaysFor(RetentionClass.SHORT_TERM))
        assertEquals(365L, retentionDaysFor(RetentionClass.LONG_TERM))
    }

    @Test
    fun pinnedMemoryNeverExpires() {
        assertNull(memoryExpiresAt(memory(retention = RetentionClass.USER_PINNED), NOW))
        assertFalse(shouldForget(memory(retention = RetentionClass.USER_PINNED, lastConfirmedAt = 0L), NOW))
    }

    @Test
    fun ephemeralExpiresAfterSevenDays() {
        val ephemeral = memory(retention = RetentionClass.EPHEMERAL, lastConfirmedAt = NOW - 8 * DAY)
        assertTrue(shouldForget(ephemeral, NOW))
        val fresh = memory(retention = RetentionClass.EPHEMERAL, lastConfirmedAt = NOW - 3 * DAY)
        assertFalse(shouldForget(fresh, NOW))
    }

    @Test
    fun reinforcementExtendsLifeAndBoostsImportance() {
        val old = memory(retention = RetentionClass.SHORT_TERM, importance = 90, lastConfirmedAt = NOW - 25 * DAY)
        assertFalse(shouldForget(old, NOW)) // 还没到 30 天
        val reinforced = reinforceMemory(old, NOW)
        assertEquals(NOW, reinforced.lastConfirmedAt)
        assertEquals(100, reinforced.importance) // 90+10 封顶 100
        assertFalse(shouldForget(reinforced, NOW + 29 * DAY))
        assertTrue(shouldForget(reinforced, NOW + 31 * DAY))
    }

    @Test
    fun decayScoreReflectsImportanceAndRecency() {
        val high = memory(importance = 100, lastConfirmedAt = NOW)
        val low = memory(importance = 10, lastConfirmedAt = NOW)
        assertTrue(memoryDecayScore(high, NOW) > memoryDecayScore(low, NOW))
        val stale = memory(importance = 100, lastConfirmedAt = NOW - 350 * DAY)
        assertTrue(memoryDecayScore(stale, NOW) < memoryDecayScore(high, NOW))
    }

    @Test
    fun defaultRetentionByType() {
        assertEquals(RetentionClass.EPHEMERAL, defaultRetentionFor(MemoryType.TEMPORARY_INTERPRETATION, 90))
        assertEquals(RetentionClass.SHORT_TERM, defaultRetentionFor(MemoryType.OBSERVATION, 90))
        assertEquals(RetentionClass.LONG_TERM, defaultRetentionFor(MemoryType.CORRECTION, 10))
        assertEquals(RetentionClass.LONG_TERM, defaultRetentionFor(MemoryType.DERIVED_PATTERN, 10))
        assertEquals(RetentionClass.SHORT_TERM, defaultRetentionFor(MemoryType.PREFERENCE, 30))
        assertEquals(RetentionClass.LONG_TERM, defaultRetentionFor(MemoryType.PREFERENCE, 80))
    }
}
