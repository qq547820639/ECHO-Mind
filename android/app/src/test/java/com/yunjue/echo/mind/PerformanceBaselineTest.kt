package com.yunjue.echo.mind

import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.journey.buildYearView
import com.yunjue.echo.mind.journey.journeyDayParams
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass
import com.yunjue.echo.mind.memory.rankMemories
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.presence.computeLifeSeason
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * ERA 18 收尾 — JVM 性能基线（PART PERFORMANCE；真机数字由 CI connected-test 矩阵执行）。
 *
 * 语义：**防退化门禁**，不是基准分数。预算取 JVM 典型值的 20-100 倍安全边际，
 * 只在出现数量级退化时失败（CI 硬件波动不误报）。
 */
class PerformanceBaselineTest {

    private fun measureMs(iterations: Int, block: () -> Unit): Double {
        block() // warmup
        var best = Double.MAX_VALUE
        repeat(iterations) {
            val start = System.nanoTime()
            block()
            best = minOf(best, (System.nanoTime() - start) / 1_000_000.0)
        }
        return best
    }

    private fun portrait(date: String, baselineDays: Int): DailyPortraitDto = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = baselineDays,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = "SIMILAR", metric = "movement_index", z = 0.4),
            "SCREEN_AMOUNT" to PortraitDimensionDto(value = "SIMILAR", metric = "screen_on_minutes", z = 0.4),
            "RHYTHM" to PortraitDimensionDto(value = "SIMILAR", metric = "active_start_minute", z = 0.4),
        ),
    )

    /** §108：Journey 365 天载入（画像 → JourneyDay → Year View 全装配）——preaggregation 预算。 */
    @Test
    fun journey365DayAssemblyStaysUnderBudget() {
        val start = LocalDate.of(2026, 1, 1)
        val portraits = (0 until 365).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), baselineDays = (i % 120))
        }
        val days: List<JourneyDay> = portraits.map { dto ->
            JourneyDay(
                date = dto.date,
                baselineDays = dto.baselineDays,
                headline = dto.headline.joinToString(" · "),
                summary = dto.summary,
                dimensionValues = mapOf("RHYTHM" to "SIMILAR"),
                visualParams = journeyDayParams(dto),
            )
        }
        val ms = measureMs(3) {
            buildYearView(days = days, canonicalDays = emptyList(), contextExceptions = emptyMap())
        }
        assertTrue("365 天 Year View 装配耗时 ${"%.1f".format(ms)}ms 超出预算 2000ms", ms < 2000.0)
    }

    /** §108：Life Season 计算（365 画像窗口）——全量重算预算。 */
    @Test
    fun lifeSeason365WindowStaysUnderBudget() {
        val start = LocalDate.of(2026, 1, 1)
        val portraits = (0 until 365).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), baselineDays = (i % 120))
        }
        val ms = measureMs(3) { computeLifeSeason(portraits) }
        assertTrue("Life Season 365 窗口耗时 ${"%.1f".format(ms)}ms 超出预算 1000ms", ms < 1000.0)
    }

    /** §109：Memory Long History——1000 条记忆排序（JVM 重排预算；SQL 侧已 LIMIT + 复合索引）。 */
    @Test
    fun memoryRanking1000StaysUnderBudget() {
        val now = System.currentTimeMillis()
        val memories = (0 until 1000).map { i ->
            EchoMemory(
                id = "mem_$i",
                userId = "u",
                type = MemoryType.entries[i % MemoryType.entries.size],
                content = "内容 $i",
                source = "observation-core",
                confidence = (i % 100) / 100f,
                createdAt = now - i * 3600_000L,
                lastConfirmedAt = now - (i % 30) * 86_400_000L,
                importance = i % 100,
                retentionClass = RetentionClass.LONG_TERM,
                provenance = "observation:v1",
                deleted = false,
            )
        }
        val ms = measureMs(3) { rankMemories(memories, now) }
        assertTrue("1000 条记忆排序耗时 ${"%.1f".format(ms)}ms 超出预算 1000ms", ms < 1000.0)
    }

    /** §109：空/单条边界不退化（空输入开销近零）。 */
    @Test
    fun emptyInputsHaveNearZeroCost() {
        val ms = measureMs(10) {
            computeLifeSeason(emptyList())
            rankMemories(emptyList(), System.currentTimeMillis())
            buildYearView(emptyList(), emptyList(), emptyMap())
        }
        assertTrue("空输入组合耗时 ${"%.1f".format(ms)}ms 超出预算 200ms", ms < 200.0)
    }
}
