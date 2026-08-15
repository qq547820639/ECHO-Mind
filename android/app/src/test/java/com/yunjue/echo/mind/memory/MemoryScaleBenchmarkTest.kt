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

    @Test
    fun derivePatternsOverFiveThousandWithinBudget() {
        // ERA 64（§109 第 2 轮）：五千级派生模式记忆锚点（千级 <1000ms 预算已在 PERFORMANCE_BASELINES）
        val input = memories(5000)
        val start = System.nanoTime()
        val patterns = derivePatterns(input)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("5000 条派生耗时 ${ms}ms 超出预算", ms < 5000)
        // 样本内容各不相同（编号唯一）→ 无 3 次重复 → 无派生（幂等语义顺手锚定）
        assertTrue("唯一内容样本不应产出派生模式", patterns.isEmpty())
    }

    @Test
    fun tenThousandScaleGuardrails() {
        // ERA 65（§109 收官）：万级护栏——预算宽于五千级以区分真数量级退化
        val now = 10_000_000_000L
        val input = memories(10_000)

        val rankStart = System.nanoTime()
        val ranked = rankMemories(input, now)
        val rankMs = (System.nanoTime() - rankStart) / 1_000_000
        assertEquals(10_000, ranked.size)
        assertTrue("10000 条排序耗时 ${rankMs}ms 超出万级护栏", rankMs < 8000)

        val sweepStart = System.nanoTime()
        for (m in input) {
            shouldForget(m, now)
        }
        val sweepMs = (System.nanoTime() - sweepStart) / 1_000_000
        assertTrue("10000 条过期判定耗时 ${sweepMs}ms 超出万级护栏", sweepMs < 8000)

        val deriveStart = System.nanoTime()
        val patterns = derivePatterns(input)
        val deriveMs = (System.nanoTime() - deriveStart) / 1_000_000
        assertTrue("10000 条派生耗时 ${deriveMs}ms 超出万级护栏", deriveMs < 8000)
        assertTrue("唯一内容样本零派生", patterns.isEmpty())
    }

    @Test
    fun twentyThousandScaleGuardrails() {
        // ERA 32 R07（§58）：两万级护栏——长期用户记忆上限；预算宽于万级以区分真数量级退化
        val now = 10_000_000_000L
        val input = memories(20_000)

        val rankStart = System.nanoTime()
        val ranked = rankMemories(input, now)
        val rankMs = (System.nanoTime() - rankStart) / 1_000_000
        assertEquals(20_000, ranked.size)
        assertTrue("20000 条排序耗时 ${rankMs}ms 超出两万级护栏", rankMs < 12_000)

        val sweepStart = System.nanoTime()
        for (m in input) {
            shouldForget(m, now)
        }
        val sweepMs = (System.nanoTime() - sweepStart) / 1_000_000
        assertTrue("20000 条过期判定耗时 ${sweepMs}ms 超出两万级护栏", sweepMs < 12_000)

        val deriveStart = System.nanoTime()
        val patterns = derivePatterns(input)
        val deriveMs = (System.nanoTime() - deriveStart) / 1_000_000
        assertTrue("20000 条派生耗时 ${deriveMs}ms 超出两万级护栏", deriveMs < 12_000)
        assertTrue("唯一内容样本零派生", patterns.isEmpty())
    }
}
