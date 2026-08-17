package com.yunjue.echo.mind.ui.journey

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneySyncStatus
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.assembleJourneyUiState
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * V3 §AK–§AO — Journey 熟悉时间导航 outcome tests：
 * - §AN 月历真实月长（31/28/29；月前月后空位惰性不可点；年月标题；上下月导航）；
 * - §AK 单一纵向滚动容器（源码结构：无 verticalScroll 嵌套 / 无 Page 包裹）；
 * - §AM 周 = 7 天水平条（WeekArc 已删）；
 * - §AL 日时间线：最新在前、行高 ≤88dp（「找昨天」一屏内）；
 * - §AO 年 = 月份列表（点击 → 月尺度）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class JourneyTimeNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val today: LocalDate = LocalDate.of(2026, 8, 14)

    private fun portrait(date: String) = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = 10,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = "SIMILAR", metric = "movement_index", z = 0.5),
        ),
    )

    private fun dates(end: LocalDate, count: Int): List<String> =
        (0 until count).map { end.minusDays((count - 1 - it).toLong()).toString() }

    private fun stateOf(
        scale: JourneyScale,
        end: LocalDate,
        count: Int,
        window: Int = count,
    ): JourneyUiState = assembleJourneyUiState(
        scale = scale,
        timeline = PortraitTimelineUiState(
            days = window,
            loading = false,
            portraits = dates(end, count).map { portrait(it) },
        ),
        permissionEnabled = true,
        narrative = null,
        runtimeAvailability = null,
        runtimeDiagnostics = null,
        showEvidence = false,
        intelligenceAvailable = true,
        syncStatus = JourneySyncStatus(consent = true, permissionEnabled = true),
        journeySeed = 42L,
    )

    private fun setContent(
        state: JourneyUiState,
        events: MutableList<JourneyEvent> = mutableListOf(),
    ) {
        compose.setContent {
            MaterialTheme {
                JourneyScreenContent(
                    state = state,
                    onEvent = { events += it },
                    feedback = { null },
                    today = today,
                )
            }
        }
    }

    // ===== §AN 月历真实月长（纯函数） =====

    @Test
    fun monthGridUsesTrueMonthLength() {
        // 31 天月
        val march = journeyMonthGrid(YearMonth.of(2026, 3))
        assertEquals(31, march.count { it.inMonth })
        // 2 月平年 28
        val feb = journeyMonthGrid(YearMonth.of(2026, 2))
        assertEquals(28, feb.count { it.inMonth })
        // 2 月闰年 29
        val leapFeb = journeyMonthGrid(YearMonth.of(2028, 2))
        assertEquals(29, leapFeb.count { it.inMonth })
        // 30 天月
        assertEquals(30, journeyMonthGrid(YearMonth.of(2026, 4)).count { it.inMonth })
        // 每行恰好 7 列；空位不携带日期
        listOf(march, feb, leapFeb).forEach { grid ->
            assertEquals(0, grid.size % 7)
            assertTrue(grid.filterNot { it.inMonth }.all { it.date == null })
            assertTrue(grid.filter { it.inMonth }.all { it.date != null })
        }
        // 周一为首列：2026-03-01 是周日 → 6 个前导空位
        assertEquals(6, march.takeWhile { !it.inMonth }.size)
    }

    // ===== §AN 月历（Compose 渲染） =====

    @Test
    fun monthGridRendersTrueMonthLength() {
        // 锚定 2026-03-31（28 天数据窗）→ 渲染 2026 年 3 月完整 31 天
        setContent(stateOf(JourneyScale.MONTH, end = LocalDate.of(2026, 3, 31), count = 28))
        compose.onNodeWithTag("journey_month_title").assertTextEquals("2026年3月")
        val dayCells = compose.onAllNodesWithTag("journey_month_day").fetchSemanticsNodes()
        assertEquals("31 天月必须渲染 31 个日期单元", 31, dayCells.size)
        // 月前/月后空位：可见惰性占位，不可点击
        val expectedBlanks = journeyMonthGrid(YearMonth.of(2026, 3)).count { !it.inMonth }
        val blanks = compose.onAllNodesWithTag("journey_month_blank")
        assertEquals(expectedBlanks, blanks.fetchSemanticsNodes().size)
        blanks.assertAll(!hasClickAction())
    }

    @Test
    fun monthGridRendersFebruaryNonLeapAs28Days() {
        setContent(stateOf(JourneyScale.MONTH, end = LocalDate.of(2026, 2, 28), count = 28))
        compose.onNodeWithTag("journey_month_title").assertTextEquals("2026年2月")
        assertEquals(
            "2 月平年必须渲染 28 个日期单元",
            28,
            compose.onAllNodesWithTag("journey_month_day").fetchSemanticsNodes().size,
        )
        compose.onAllNodesWithTag("journey_month_blank").assertAll(!hasClickAction())
    }

    @Test
    fun monthGridRendersLeapFebruaryAs29Days() {
        setContent(stateOf(JourneyScale.MONTH, end = LocalDate.of(2028, 2, 29), count = 28))
        compose.onNodeWithTag("journey_month_title").assertTextEquals("2028年2月")
        assertEquals(
            "2 月闰年必须渲染 29 个日期单元",
            29,
            compose.onAllNodesWithTag("journey_month_day").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun monthNavigationArrowsSwitchMonth() {
        // 数据跨 2026-02 / 2026-03 → 锚定 3 月，可回看 2 月
        setContent(stateOf(JourneyScale.MONTH, end = LocalDate.of(2026, 3, 24), count = 28))
        compose.onNodeWithTag("journey_month_title").assertTextEquals("2026年3月")
        compose.onNodeWithTag("journey_month_previous").performClick()
        compose.onNodeWithTag("journey_month_title").assertTextEquals("2026年2月")
        compose.onNodeWithTag("journey_month_next").performClick()
        compose.onNodeWithTag("journey_month_title").assertTextEquals("2026年3月")
    }

    @Test
    fun monthDayCellTapEmitsSelectDay() {
        val events = mutableListOf<JourneyEvent>()
        setContent(stateOf(JourneyScale.MONTH, end = LocalDate.of(2026, 3, 31), count = 28), events)
        // 3 月 31 日有数据 → 可点击 → 选中该日
        compose.onAllNodes(
            hasTestTag("journey_month_day") and hasAnyDescendant(hasText("31")),
            useUnmergedTree = true,
        )[0].performClick()
        assertTrue(events.contains(JourneyEvent.SelectDay("2026-03-31")))
    }

    // ===== §AK 单一纵向滚动容器（源码结构） =====

    @Test
    fun dayTimelineHasSingleVerticalScrollOwner() {
        val source = File("src/main/java/com/yunjue/echo/mind/ui/journey/JourneyScreen.kt").readText()
        assertFalse("Journey 不得再有 verticalScroll 包裹（§AK）", source.contains(".verticalScroll("))
        assertFalse("Journey 不得回到 Page(verticalScroll) 包裹（§AK）", source.contains("Page("))
        assertTrue("每个尺度应有 LazyColumn 主滚动容器", source.contains("LazyColumn"))
    }

    // ===== §AM 周 = 7 天水平条 =====

    @Test
    fun weekIsHorizontalStripNotArc() {
        val source = File("src/main/java/com/yunjue/echo/mind/ui/journey/JourneyScreen.kt").readText()
        assertFalse("WeekArc 已删除（§AM）", source.contains("WeekArc"))
        assertTrue(source.contains("journey_week_strip"))
        assertTrue(source.contains("journey_week_day"))

        setContent(stateOf(JourneyScale.WEEK, end = LocalDate.of(2026, 8, 14), count = 7))
        assertEquals(
            "周条必须恰好 7 天",
            7,
            compose.onAllNodesWithTag("journey_week_day").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun weekStripDayCellTapEmitsSelectDay() {
        val events = mutableListOf<JourneyEvent>()
        setContent(stateOf(JourneyScale.WEEK, end = LocalDate.of(2026, 8, 14), count = 7), events)
        // 最后一个 cell = 锚点日（最新数据日）
        compose.onAllNodesWithTag("journey_week_day")[6].performClick()
        assertTrue(events.contains(JourneyEvent.SelectDay("2026-08-14")))
    }

    // ===== §AL 日时间线：最新在前 + 行高 ≤88dp（找昨天一屏内） =====

    @Test
    fun findYesterdayWithinOneScreen() {
        setContent(stateOf(JourneyScale.DAY, end = LocalDate.of(2026, 8, 14), count = 7))
        // DAY 主滚动容器 = LazyColumn（journey_memory_visual）
        compose.onNodeWithTag("journey_memory_visual").assertExists()
        val items = compose.onAllNodesWithTag("journey_day_item", useUnmergedTree = true)
        val nodes = items.fetchSemanticsNodes()
        assertTrue("时间线至少渲染今天 + 昨天", nodes.size >= 2)
        // 第一项 = 今天（最新在前）
        items[0].assert(hasAnyDescendant(hasText("8月14日 · 周五")))
        // 昨天（8月13日 周四）紧随其后——单次滚动可达
        items[1].assert(hasAnyDescendant(hasText("8月13日 · 周四")))
        // 行高 ≤ 88dp（§AL 64–88dp 区间上限）
        val maxItemPx = with(compose.density) { 88.dp.roundToPx() }
        assertTrue(
            "时间线行高不得超过 88dp（实际 ${nodes.map { it.size.height }}）",
            nodes.all { it.size.height in 1..maxItemPx },
        )
    }

    // ===== §AO 季/年 = 月份列表 =====

    @Test
    fun seasonMonthRowsAreInformativeNotNavigational() {
        setContent(stateOf(JourneyScale.SEASON, end = LocalDate.of(2026, 8, 14), count = 90))
        val rows = compose.onAllNodesWithTag("journey_month_item")
        assertTrue("季尺度应按自然月分组", rows.fetchSemanticsNodes().size >= 3)
        rows.assertAll(!hasClickAction())
    }

    @Test
    fun yearMonthListTapOpensMonthScale() {
        val events = mutableListOf<JourneyEvent>()
        setContent(stateOf(JourneyScale.YEAR, end = LocalDate.of(2026, 8, 14), count = 120), events)
        val rows = compose.onAllNodesWithTag("journey_month_item")
        assertTrue(rows.fetchSemanticsNodes().isNotEmpty())
        rows[0].performClick()
        // 年 → 月：切换尺度 + 选中该月最后一天
        assertTrue(events.contains(JourneyEvent.SelectScale(JourneyScale.MONTH)))
        assertTrue(
            events.contains(JourneyEvent.SelectDay(LocalDate.of(2026, 4, 30).toString())),
        )
    }
}
