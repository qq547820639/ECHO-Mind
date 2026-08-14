package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.PORTRAIT_DIMENSIONS
import com.yunjue.echo.mind.model.PORTRAIT_SUMMARY_NO_DATA
import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.dimensionTrendSymbol
import com.yunjue.echo.mind.model.dimensionValueText
import com.yunjue.echo.mind.model.portraitStabilitySummary
import com.yunjue.echo.mind.journey.coveragePercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Milestone G：Portrait Timeline 纯计算（纯 JVM，无 Android 依赖）。
 *
 * 覆盖：
 * - 维度中文标签 / 取值映射（spec 表逐条断言）
 * - 7 日趋势相对符号（↑↓→~–，不做精确数字强调）
 * - 28 日确定性综述（最稳定 = SIMILAR 比例最高；变化较明显 = 非 SIMILAR 最多）
 * - 数据覆盖度百分比
 * - 7 日矩阵仅渲染节律/移动/屏幕三个维度（spec：不做心理状态解释）
 *
 * Phase 1.1：dimensions 为强类型 PortraitDimensionDto（value/metric/z 嵌套对象）。
 */
class PortraitTimelineTest {

    private fun portrait(date: String, dims: Map<String, String>): DailyPortraitDto =
        DailyPortraitDto(
            date = date,
            status = "READY",
            confidence = "MEDIUM",
            baselineDays = 8,
            dimensions = dims.mapValues { (_, v) -> PortraitDimensionDto(value = v) }
        )

    // ---------- 维度中文标签（spec 映射） ----------

    @Test
    fun dimensionLabelsMatchSpec() {
        assertEquals("作息", dimensionDisplayName("RHYTHM"))
        assertEquals("移动", dimensionDisplayName("MOVEMENT"))
        // Phase 5：SCREEN_PATTERN 拆为 SCREEN_AMOUNT / SCREEN_TIMING
        assertEquals("屏幕总量", dimensionDisplayName("SCREEN_AMOUNT"))
        assertEquals("屏幕时段", dimensionDisplayName("SCREEN_TIMING"))
        assertEquals("行为分布", dimensionDisplayName("DAY_STRUCTURE"))
        assertEquals("整体节律", dimensionDisplayName("STABILITY"))
        assertEquals("UNKNOWN_DIM", dimensionDisplayName("UNKNOWN_DIM"))
    }

    @Test
    fun dimensionValuesMatchSpec() {
        assertEquals("偏早", dimensionValueText("RHYTHM", "EARLIER"))
        assertEquals("偏晚", dimensionValueText("RHYTHM", "LATER"))
        assertEquals("接近", dimensionValueText("RHYTHM", "SIMILAR"))
        assertEquals("不规律", dimensionValueText("RHYTHM", "IRREGULAR"))
        // Phase 6.2：行为措辞（替换旧 "↓ 减少"/"↑ 增多"）
        assertEquals("较少", dimensionValueText("MOVEMENT", "LESS"))
        assertEquals("较多", dimensionValueText("MOVEMENT", "MORE"))
        assertEquals("更集中", dimensionValueText("DAY_STRUCTURE", "MORE_CONCENTRATED"))
        assertEquals("更零散", dimensionValueText("DAY_STRUCTURE", "MORE_FRAGMENTED"))
        assertEquals("非常接近", dimensionValueText("STABILITY", "VERY_SIMILAR"))
        assertEquals("稍有不同", dimensionValueText("STABILITY", "SLIGHTLY_DIFFERENT"))
        assertEquals("明显不同", dimensionValueText("STABILITY", "CLEARLY_DIFFERENT"))
        assertEquals("RAW_VALUE", dimensionValueText("X", "RAW_VALUE"))
    }

    // ---------- 7 日趋势相对符号（spec：↑↓→，不做精确数字强调） ----------

    @Test
    fun trendSymbolsMatchRelativeDirection() {
        assertEquals("↑", dimensionTrendSymbol("EARLIER")) // 作息偏早
        assertEquals("↓", dimensionTrendSymbol("LATER"))   // 作息偏晚
        assertEquals("→", dimensionTrendSymbol("SIMILAR")) // 接近
        assertEquals("~", dimensionTrendSymbol("IRREGULAR")) // 不规律（波动）
        assertEquals("↓", dimensionTrendSymbol("LESS"))    // 移动减少
        assertEquals("↑", dimensionTrendSymbol("MORE"))    // 移动增多
        assertEquals("↑", dimensionTrendSymbol("CLEARLY_DIFFERENT"))
        assertEquals("→", dimensionTrendSymbol("VERY_SIMILAR"))
        assertEquals("–", dimensionTrendSymbol(null))      // 缺失
        assertEquals("–", dimensionTrendSymbol("NOPE"))    // 未知
    }

    @Test
    fun sevenDayMatrixUsesOnlyRhythmMovementScreen() {
        // spec：7 日视图渲染节律/移动/屏幕维度（不解释心理状态）；Phase 5 拆分为
        // SCREEN_AMOUNT + SCREEN_TIMING（共 4 个趋势维度）
        assertEquals(
            listOf("RHYTHM", "MOVEMENT", "SCREEN_AMOUNT", "SCREEN_TIMING"),
            PORTRAIT_TREND_DIMENSIONS
        )
        // 全量维度顺序（Today 对照表用）包含六个维度（STABILITY 在最后）
        assertEquals(6, PORTRAIT_DIMENSIONS.size)
        assertTrue(PORTRAIT_DIMENSIONS.containsAll(PORTRAIT_TREND_DIMENSIONS))
    }

    // ---------- 28 日综述（确定性计算） ----------

    @Test
    fun stabilitySummaryReturnsNoDataCopyWhenEmpty() {
        assertEquals(PORTRAIT_SUMMARY_NO_DATA, portraitStabilitySummary(emptyList()))
        // 有画像但无任何维度数据
        assertEquals(
            PORTRAIT_SUMMARY_NO_DATA,
            portraitStabilitySummary(listOf(portrait("2026-08-01", emptyMap())))
        )
    }

    @Test
    fun stabilitySummaryPicksHighestSimilarRatioAsMostStable() {
        // SCREEN_AMOUNT：4/4 SIMILAR（比例 1.0）唯一最高 → 最接近（Phase 6.3：替换「最稳定」）；
        // RHYTHM / MOVEMENT：0/4 SIMILAR → 非 SIMILAR 最多（并列取首个）→ 变化较明显：作息
        val portraits = (1..4).map { d ->
            portrait(
                date = "2026-08-0$d",
                dims = mapOf(
                    "RHYTHM" to (if (d % 2 == 0) "EARLIER" else "LATER"),
                    "MOVEMENT" to (if (d % 2 == 0) "LESS" else "MORE"),
                    "SCREEN_AMOUNT" to "SIMILAR"
                )
            )
        }
        val summary = portraitStabilitySummary(portraits)
        assertTrue("应选 SIMILAR 比例最高维度为最接近：$summary", summary.contains("最接近：屏幕总量"))
        assertTrue("应选非 SIMILAR 最多维度为变化较明显：$summary", summary.contains("变化较明显：作息"))
    }

    @Test
    fun stabilitySummaryTieBreaksDeterministically() {
        // 全部维度相同比例时仍返回确定结果（不抛异常、不空）
        val portraits = (1..2).map { d ->
            portrait(
                date = "2026-08-0$d",
                dims = mapOf("RHYTHM" to "SIMILAR", "MOVEMENT" to "SIMILAR", "SCREEN_AMOUNT" to "SIMILAR")
            )
        }
        val summary = portraitStabilitySummary(portraits)
        assertTrue(summary.startsWith("最接近："))
        assertTrue(summary.contains("变化较明显："))
        assertTrue(summary == portraitStabilitySummary(portraits)) // 确定性
    }

    @Test
    fun dimensionValueTextAvoidsQuietActiveStable() {
        // Phase 6.3（规格 §3.2 / §8.1）：headline 无「安静/活跃/稳定」——
        // MOVEMENT=LESS/MORE → 「较少/较多」（替换「安静/活跃」）；
        // STABILITY=VERY_SIMILAR → 「非常接近」（替换「稳定」）。
        val values = listOf(
            "EARLIER", "LATER", "SIMILAR", "IRREGULAR", "LESS", "MORE",
            "MORE_CONCENTRATED", "MORE_FRAGMENTED", "VERY_SIMILAR",
            "SLIGHTLY_DIFFERENT", "CLEARLY_DIFFERENT"
        )
        for (value in values) {
            val text = dimensionValueText("X", value)
            assertTrue(
                "维度取值文案不得含「安静/活跃/稳定」：$text",
                listOf("安静", "活跃", "稳定").none { text.contains(it) }
            )
        }
    }

    // ---------- 数据覆盖度 ----------

    @Test
    fun coveragePercentCountsOnlyInWindowDays() {
        val today = LocalDate.now()
        // 最近 7 天窗口内 5 天有画像 → 5/7 = 71%
        val portraits = (0 until 5).map { portrait(today.minusDays(it.toLong()).toString(), emptyMap()) }
        assertEquals(71, coveragePercent(portraits, 7))
        // 窗口外日期不计入
        val outOfWindow = portrait(today.minusDays(30).toString(), emptyMap())
        assertEquals(0, coveragePercent(listOf(outOfWindow), 7))
        // 全部覆盖 → 100%
        val full = (0 until 7).map { portrait(today.minusDays(it.toLong()).toString(), emptyMap()) }
        assertEquals(100, coveragePercent(full, 7))
        // 非法窗口 → 0
        assertEquals(0, coveragePercent(full, 0))
    }
}
