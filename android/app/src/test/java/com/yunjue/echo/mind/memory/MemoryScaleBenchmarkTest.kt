package com.yunjue.echo.mind.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 63（ADR-061 第 1 轮）——§109 Memory 长历史 JVM 代表性基准：
 * 1000/5000 条记忆的检索排序与过期判定扫掠耗时锚点（防数量级退化；
 * 预算 = JVM 典型值 × 大安全边际，CI 波动不误报）。真机矩阵由 CI connected-test 承接。
 */
class MemoryScaleBenchmarkTest {

    private fun memories(n: Int): List<EchoMemory> = (1..n).map { i ->
        EchoMemory(
            id = "m$i",
            userId = "u1",
            type = MemoryType.entries[i % MemoryType.entries.size],
            content = "记忆内容编号 $i",
            source = "bench",
            confidence = i % 100 / 100f,
            createdAt = i * 60_000L,
            lastConfirmedAt = i * 120_000L,
            importance = i % 100,
            retentionClass = RetentionClass.entries[i % RetentionClass.entries.size],
            provenance = "bench",
            deleted = false,
        )
    }

    @Test
    fun rankThousandAndFiveThousandWithinBudget() {
        val now = 10_000_000_000L
        for (n in listOf(1000, 5000)) {
            val input = memories(n)
            val start = System.nanoTime()
            val ranked = rankMemories(input, now)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertEquals("排序不得丢条目", n, ranked.size)
            assertTrue("$n 条排序耗时 ${ms}ms 超出预算", ms < 5000)
        }
    }

    @Test
    fun expirySweepOverFiveThousandWithinBudget() {
        val now = 10_000_000_000L
        val input = memories(5000)
        val start = System.nanoTime()
        var expiring = 0
        for (m in input) {
            if (shouldForget(m, now)) expiring++
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("5000 条过期判定扫掠耗时 ${ms}ms 超出预算", ms < 5000)
        // EPHEMERAL（7 天）+ createdAt 远早于 now → 必有过期命中（语义正确性顺手锚定）
        assertTrue("样本应包含可过期记忆", expiring > 0)
    }
}
