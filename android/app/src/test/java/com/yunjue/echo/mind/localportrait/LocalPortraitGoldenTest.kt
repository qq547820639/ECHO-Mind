package com.yunjue.echo.mind.localportrait

import com.yunjue.echo.mind.model.containsBlockedVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 端侧画像引擎 golden 场景（与后端 tests/test_portrait_golden.py 逐场景镜像）。
 *
 * 输入构造方式与后端完全一致：
 * - make_baseline_rows：28 天确定性阶梯模式（i%7）聚合行；
 * - make_today：pad 个零窗口铺覆盖 + 5 个关键窗口（主屏幕 / 晚间屏幕 / 加速度 / 通知 / 应用切换）；
 * - TODAY = 2026-08-10（周一），时区 Asia/Shanghai。
 *
 * 断言与后端逐条对齐，保证端侧引擎与云端流水线**同输入同输出**。
 */
class LocalPortraitGoldenTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 8, 10) // 周一

    /** 本地日 00:00 的 UTC 起点（镜像后端 local_day_window("Asia/Shanghai", today)[0]）。 */
    private fun utcStartMs(): Long = today.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun windowRow(
        source: String,
        localMinute: Int,
        vector: Map<Int, Double> = emptyMap(),
        sources: List<String> = listOf(source)
    ): LocalWindowRow {
        val v = MutableList(22) { 0f }
        vector.forEach { (idx, value) -> v[idx] = value.toFloat() }
        val startMs = utcStartMs() + localMinute * 60_000L
        return LocalWindowRow(
            windowStartMs = startMs,
            schemaVersion = "passive-core-v1",
            source = source,
            vector = v,
            sourcesPresent = sources
        )
    }

    /** 镜像 make_today：pad 个零窗口铺覆盖 + 5 个关键窗口。 */
    private fun makeToday(
        activeStart: Int = 495,
        movement: Double = 1.06,
        screenMinutes: Double = 126.0,
        lateMinutes: Double = 23.0,
        notif: Int = 13,
        app: Int = 43,
        pad: Int = 240
    ): List<LocalWindowRow> {
        val rows = mutableListOf<LocalWindowRow>()
        for (i in 0 until pad) {
            rows.add(windowRow("screen", 5 * i, sources = emptyList()))
        }
        val mainMs = (screenMinutes - lateMinutes) * 60000.0
        rows.add(windowRow("screen", activeStart, mapOf(14 to 1.0, 16 to mainMs), listOf("screen")))
        if (lateMinutes > 0) {
            rows.add(windowRow("screen", 22 * 60, mapOf(14 to 1.0, 16 to lateMinutes * 60000.0), listOf("screen")))
        }
        rows.add(windowRow("accel", activeStart + 5, mapOf(7 to movement), listOf("accel")))
        rows.add(windowRow("notification", 9 * 60, mapOf(17 to notif.toDouble()), listOf("notification")))
        rows.add(windowRow("app_activity", 10 * 60, mapOf(20 to app.toDouble()), listOf("app_activity")))
        return rows
    }

    /** 镜像 make_baseline_rows：28 天确定性阶梯模式（i%7）。 */
    private fun makeBaselineRows(days: Int = 28): List<LocalDayAggregate> {
        val start = today.minusDays(28)
        val full = listOf("accel", "gyro", "screen", "notification", "app_activity")
        return (0 until days).map { i ->
            val k = i % 7
            LocalDayAggregate(
                localDate = start.plusDays(i.toLong()),
                timezone = "Asia/Shanghai",
                coverageScore = 0.8,
                validWindowCount = 230,
                expectedWindowCount = 288,
                movementIndex = 1.0 + k * 0.02,
                movementVariability = 0.02,
                screenOnMinutes = 120.0 + k * 2.0,
                screenOpenCount = 30,
                lateScreenMinutes = 20.0 + k,
                appSwitchCount = 40 + k,
                notificationCount = 10 + k,
                activeStartMinute = 480 + k * 5,
                activeEndMinute = 1320 + k * 5,
                activeHourSpread = 0.16 + k * 0.005,
                sourcesPresent = full,
                missingSources = emptyList()
            )
        }
    }

    private fun generate(
        todayRows: List<LocalWindowRow>,
        baselineRows: List<LocalDayAggregate>
    ) = LocalPortraitEngine.generate(
        localDate = today,
        zoneId = zone,
        todayAggregate = computeLocalDayAggregate(today, zone, todayRows),
        pastAggregates = baselineRows
    )

    @Test
    fun scenario001LaterMovementLessScreenSimilar() {
        val dto = generate(
            makeToday(activeStart = 540, movement = 0.5, screenMinutes = 126.0, lateMinutes = 23.0),
            makeBaselineRows()
        )
        assertEquals("READY", dto.status)
        assertEquals("LATER", dto.dimensions["RHYTHM"]?.value)
        assertEquals("LESS", dto.dimensions["MOVEMENT"]?.value)
        assertEquals("SIMILAR", dto.dimensions["SCREEN_AMOUNT"]?.value)
        assertEquals("SIMILAR", dto.dimensions["SCREEN_TIMING"]?.value)
        assertEquals("SIMILAR", dto.dimensions["DAY_STRUCTURE"]?.value)
        assertFalse("旧合并维度 SCREEN_PATTERN 应已移除", dto.dimensions.containsKey("SCREEN_PATTERN"))
        assertEquals("SLIGHTLY_DIFFERENT", dto.dimensions["STABILITY"]?.value)
        assertTrue(dto.summary.contains("稍晚"))
        assertTrue(dto.summary.contains("少了一些"))
        assertTrue(dto.summary.contains("比较接近"))
        assertTrue(dto.headline.contains("偏晚"))
        assertTrue(listOf("HIGH", "MEDIUM").contains(dto.confidence))
        assertFalse(containsBlockedVocabulary(dto.summary))
    }

    @Test
    fun scenario002AllSimilar() {
        val dto = generate(
            makeToday(activeStart = 495, movement = 1.06, screenMinutes = 126.0, lateMinutes = 23.0),
            makeBaselineRows()
        )
        assertEquals("READY", dto.status)
        assertEquals("VERY_SIMILAR", dto.dimensions["STABILITY"]?.value)
        assertTrue(dto.summary.contains("非常接近"))
        assertTrue(dto.headline.contains("接近"))
    }

    @Test
    fun scenario003ScreenSurge() {
        val dto = generate(
            makeToday(activeStart = 495, movement = 1.06, screenMinutes = 300.0, lateMinutes = 120.0),
            makeBaselineRows()
        )
        assertEquals("READY", dto.status)
        assertEquals("MORE", dto.dimensions["SCREEN_AMOUNT"]?.value)
        assertEquals("screen_on_minutes", dto.dimensions["SCREEN_AMOUNT"]?.metric)
        assertEquals("LATER", dto.dimensions["SCREEN_TIMING"]?.value)
        assertEquals("late_screen_minutes", dto.dimensions["SCREEN_TIMING"]?.metric)
        assertTrue(dto.summary.contains("屏幕互动比平常多一些"))
        assertTrue(dto.summary.contains("晚间屏幕互动比通常集中"))
        assertTrue(dto.headline.contains("多屏"))
        assertTrue(dto.headline.contains("晚屏"))
    }

    @Test
    fun scenario004LowCoverage() {
        val dto = generate(makeToday(activeStart = 540, movement = 0.5, pad = 0), makeBaselineRows())
        assertEquals("LOW_CONFIDENCE", dto.status)
        assertEquals("LOW", dto.confidence)
        assertTrue(dto.summary.contains("还不够完整"))
        assertEquals(emptyMap<String, com.yunjue.echo.mind.model.PortraitDimensionDto>(), dto.dimensions)
        assertEquals(emptyList<String>(), dto.headline)
    }

    @Test
    fun scenario005ColdStart() {
        val dto = generate(makeToday(pad = 50), emptyList())
        assertEquals("WARMING_UP", dto.status)
        assertEquals("LOW", dto.confidence)
        assertTrue(dto.summary.contains("正在慢慢了解"))
        assertEquals(emptyMap<String, com.yunjue.echo.mind.model.PortraitDimensionDto>(), dto.dimensions)
        assertEquals(emptyList<String>(), dto.headline)
    }

    @Test
    fun scenario006PartialData() {
        val dto = generate(makeToday(activeStart = 540, movement = 0.5, pad = 95), makeBaselineRows())
        assertEquals("PARTIAL_DATA", dto.status)
        assertTrue(
            dto.summary.startsWith("今天的数据还不完整，以下画像仅反映已经采集到的部分。 ")
        )
        assertTrue(dto.dimensions.isNotEmpty())
    }

    @Test
    fun scenario007EarlyBaseline() {
        val dto = generate(makeToday(activeStart = 495, movement = 1.06), makeBaselineRows(days = 5))
        assertEquals("EARLY_BASELINE", dto.status)
        assertEquals("LOW", dto.confidence)
        assertTrue(dto.summary.contains("今天累计屏幕互动"))
        assertFalse(dto.summary.contains("比平常"))
        assertEquals(emptyMap<String, com.yunjue.echo.mind.model.PortraitDimensionDto>(), dto.dimensions)
    }

    @Test
    fun scenario008RhythmMissingOmitted() {
        // 纯 accel 窗口（120 个，coverage≈0.417 → READY）但不满足 active 条件 → active_start=None
        val accelOnly = (0 until 120).map { i ->
            windowRow("accel", 5 * i, mapOf(7 to 0.5), listOf("accel"))
        }
        val dto = generate(accelOnly, makeBaselineRows())
        assertEquals("READY", dto.status)
        assertFalse("missing != irregular：RHYTHM 应省略", dto.dimensions.containsKey("RHYTHM"))
        assertTrue(dto.dimensions.containsKey("MOVEMENT"))
        assertTrue(dto.dimensions.containsKey("STABILITY"))
    }

    @Test
    fun deterministicSameInputSameOutput() {
        val rows1 = makeToday(activeStart = 540, movement = 0.5)
        val rows2 = makeToday(activeStart = 540, movement = 0.5)
        val baseline = makeBaselineRows()
        val d1 = generate(rows1, baseline)
        val d2 = generate(rows2, baseline)
        assertEquals(d1.summary, d2.summary)
        assertEquals(d1.headline, d2.headline)
        assertEquals(d1.dimensions, d2.dimensions)
        assertEquals(d1.facts, d2.facts)
        assertEquals(d1.status, d2.status)
    }

    @Test
    fun nearZeroBaselineNoHugeZ() {
        // 近零基线：|v-med| < MIN_ABS_DELTA(0.02) → z=0（镜像 test_baseline.test_near_zero_baseline_no_huge_z）
        val stats = LocalMetricStats(median = 0.001, mad = 0.0, p25 = 0.0, p75 = 0.0, p10 = 0.0, p90 = 0.0, validDays = 20)
        assertEquals(0.0, LocalPortraitEngine.zOf(0.01, stats, "movement_index")!!, 1e-9)
        val zeroStats = LocalMetricStats(median = 0.0, mad = 0.0, p25 = 0.0, p75 = 0.0, p10 = 0.0, p90 = 0.0, validDays = 20)
        assertEquals(0.0, LocalPortraitEngine.zOf(0.01, zeroStats, "movement_index")!!, 1e-9)
        // 全零基线 → 近零分类仍工作（SIMILAR 而非巨大 z）
        assertEquals("SIMILAR", LocalPortraitEngine.classify(0.01, zeroStats, "LESS", "MORE", "movement_index"))
    }

    @Test
    fun coarseWordingNoExaggeratedPercentages() {
        // 近零基线：禁止 +900% 类数字，回退粗粒度措辞（镜像 explain.py）
        val nearZero = LocalMetricStats(median = 0.001, mad = 0.001, p25 = 0.0, p75 = 0.002, p10 = 0.0, p90 = 0.002, validDays = 20)
        val text = LocalPortraitEngine.deltaText(0.05, nearZero, "movement_index")
        assertFalse("近零基线不得输出百分比数字", Regex("%").containsMatchIn(text))
        assertTrue(text.contains("比近期") && text.contains("多"))
    }

    @Test
    fun crossMidnightRhythmNotFalselyIrregular() {
        // 基线活跃起点跨午夜（23:55/00:05），今天 00:10 起点 → 圆周距离很小 → SIMILAR
        val values = listOf(1435.0, 5.0, 10.0)
        val stats = computeLocalStats(values, circular = true)
        val z = LocalPortraitEngine.zOf(10.0, stats, "active_start_minute")
        assertTrue("跨午夜起点应接近（|z|<=0.7）", kotlin.math.abs(z ?: 99.0) <= 0.7)
        // 对照：线性统计会把 10 分钟差异误判为巨大（圆周统计与线性统计结果必须不同）
        val linear = computeLocalStats(values, circular = false)
        assertNotEquals(stats.median, linear.median)
    }

    @Test
    fun baselineBucketWeekdayWeekendFallback() {
        // 今天周一 → weekday 桶；工作日 20 天 >= 2 → 不 fallback
        val snap = buildLocalBaseline(today, makeBaselineRows())
        assertEquals("weekday", snap.bucket)
        assertEquals(20, snap.validDays)
        // 基线中位数与后端场景一致（工作日 k=0..4 阶梯）
        assertEquals(490.0, snap.metrics["active_start_minute"]?.median!!, 1e-9)
        assertEquals(1.04, snap.metrics["movement_index"]?.median!!, 1e-9)
        // 只有 2 天数据且今天周六 → weekend 桶 0 天 < 2 → fallback all_days
        val saturday = LocalDate.of(2026, 8, 8) // 周六
        val twoDays = listOf(
            makeBaselineRows()[0], makeBaselineRows()[1]
        )
        val snap2 = buildLocalBaseline(saturday, twoDays)
        assertEquals("all_days", snap2.bucket)
    }

    /** ERA 78（ADR-067）：单日聚合构造器（仅基线窗口语义测试所需字段）。 */
    private fun agg(date: LocalDate, coverage: Double = 0.8) = LocalDayAggregate(
        localDate = date,
        timezone = "Asia/Shanghai",
        coverageScore = coverage,
        validWindowCount = 230,
        expectedWindowCount = 288,
        movementIndex = 1.0,
        movementVariability = 0.02,
        screenOnMinutes = 120.0,
        screenOpenCount = 30,
        lateScreenMinutes = 20.0,
        appSwitchCount = 40,
        notificationCount = 10,
        activeStartMinute = 480,
        activeEndMinute = 1320,
        activeHourSpread = 0.16,
        sourcesPresent = listOf("screen"),
        missingSources = emptyList()
    )

    @Test
    fun baselineWindowEdgesExcludeTodayAndBeforeWindow() {
        // 窗口 = [today-28, today-1]：today 与 today-29 均不计入 validDays（镜像后端 SQL 过滤）
        val snap = buildLocalBaseline(
            today,
            makeBaselineRows() + listOf(agg(today), agg(today.minusDays(29))),
        )
        // 今天周一 → weekday 桶 20 个有效日（新增 today/today-29 行不得膨胀计数）
        assertEquals(20, snap.validDays)
        assertEquals(today.minusDays(28), snap.windowStart)
        assertEquals(today.minusDays(1), snap.windowEnd)
    }

    @Test
    fun baselineCoverageBoundaryIncludesExactlyThreshold() {
        // coverage 恰 0.25 计入；0.249 排除（与后端 MIN_COVERAGE 语义一致）
        val snap = buildLocalBaseline(
            today,
            listOf(
                agg(today.minusDays(1), coverage = 0.25),
                agg(today.minusDays(2), coverage = 0.249),
            ),
        )
        assertEquals(1, snap.validDays)
    }

    @Test
    fun baselineStateLadderBoundaries() {
        assertEquals("WARMING_UP", LocalBaselineCalculator.baselineState(2))
        assertEquals("EARLY_BASELINE", LocalBaselineCalculator.baselineState(3))
        assertEquals("EARLY_BASELINE", LocalBaselineCalculator.baselineState(6))
        assertEquals("BASELINE_READY", LocalBaselineCalculator.baselineState(7))
    }

    @Test
    fun firstReadyPortraitAppearsAtExactlySevenValidDays() {
        // ERA 81（ADR-068 Day 1-7 收官锚）：第 7 个有效日（全部工作日 → weekday 桶）即触发
        // 第一个 READY 画像——「你的平常」首次成形（维度非空、headline 非空、baselineDays == 7）。
        // today = 2026-08-10 周一；窗口内工作日 = today-3/-4/-5/-6/-7/-10/-11。
        val weekdayOffsets = listOf(3L, 4L, 5L, 6L, 7L, 10L, 11L)
        val sevenWeekdays = weekdayOffsets.map { agg(today.minusDays(it)) }
        val dto = generate(makeToday(), sevenWeekdays)
        assertEquals("READY", dto.status)
        assertEquals(7, dto.baselineDays)
        assertTrue("第一个 READY 画像必须有维度比较", dto.dimensions.isNotEmpty())
        assertTrue("第一个 READY 画像必须有 headline", dto.headline.isNotEmpty())
        // 第 6 个有效日仍处 EARLY_BASELINE（阶梯边界不提前）
        val sixWeekdays = weekdayOffsets.take(6).map { agg(today.minusDays(it)) }
        assertEquals("EARLY_BASELINE", generate(makeToday(), sixWeekdays).status)
    }

    @Test
    fun narrativeHeadlineAtMostThree() {
        val dto = generate(
            makeToday(activeStart = 700, movement = 0.1, screenMinutes = 300.0, lateMinutes = 120.0, notif = 50, app = 90),
            makeBaselineRows()
        )
        assertTrue("headline 最多 3 个", dto.headline.size <= 3)
        assertFalse(containsBlockedVocabulary(dto.summary))
        dto.facts.forEach { fact ->
            assertFalse(containsBlockedVocabulary(fact.label + fact.todayText + fact.baselineText + fact.deltaText))
        }
    }

    @Test
    fun expectedWindowsDstSemantics() {
        // 普通日 288；Asia/Shanghai 无 DST（2026-08-10）
        assertEquals(288, expectedWindowCountForDay(zone, today))
        // America/New_York 2026-03-08 为 spring-forward（23h → 276）
        val ny = ZoneId.of("America/New_York")
        assertEquals(276, expectedWindowCountForDay(ny, LocalDate.of(2026, 3, 8)))
        // America/New_York 2026-11-01 为 fall-back（25h → 300）
        assertEquals(300, expectedWindowCountForDay(ny, LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun vectorOutOfBoundsReadsZero() {
        val row = LocalWindowRow(0L, "passive-core-v1", "screen", emptyList(), listOf("screen"))
        assertEquals(0.0, row.vectorAt(99), 1e-9)
        assertEquals(0.0, row.vectorAt(-1), 1e-9)
    }

    @Test
    fun micAndHealthRowsExcludedFromAggregate() {
        // mic_opt（mic-feature-v1）与 health source 不参与统计（镜像 calculator._is_aggregate_feature）
        val rows = makeToday(activeStart = 495, movement = 1.06)
        val polluted = rows + listOf(
            LocalWindowRow(
                windowStartMs = utcStartMs() + 100 * 60_000L,
                schemaVersion = "mic-feature-v1",
                source = "mic_opt",
                vector = MutableList(256) { 1f },
                sourcesPresent = listOf("mic_opt")
            ),
            LocalWindowRow(
                windowStartMs = utcStartMs() + 200 * 60_000L,
                schemaVersion = "passive-core-v1",
                source = "health",
                vector = MutableList(22) { 1f },
                sourcesPresent = listOf("health")
            )
        )
        val agg = computeLocalDayAggregate(today, zone, polluted)
        // 与无污染输入比较：聚合结果不受 mic/health 影响
        val clean = computeLocalDayAggregate(today, zone, rows)
        assertEquals(clean.coverageScore, agg.coverageScore, 1e-9)
        assertEquals(clean.screenOnMinutes, agg.screenOnMinutes, 1e-9)
        assertEquals(clean.movementIndex!!, agg.movementIndex!!, 1e-9)
    }

    @Test
    fun duplicateWindowDoesNotDoubleCountCoverage() {
        val single = makeToday(activeStart = 495, movement = 1.06, pad = 0)
        // 同一 (windowStart, schema, source) 重复行：coverage 不重复计数（镜像 unique 窗口语义）
        val duplicated = single + single.map { it.copy() }
        val aggSingle = computeLocalDayAggregate(today, zone, single)
        val aggDup = computeLocalDayAggregate(today, zone, duplicated)
        assertEquals(aggSingle.validWindowCount, aggDup.validWindowCount)
        assertEquals(aggSingle.coverageScore, aggDup.coverageScore, 1e-9)
    }

    @Test
    fun factsExplainShape() {
        val dto = generate(makeToday(activeStart = 540, movement = 0.5), makeBaselineRows())
        assertTrue(dto.facts.isNotEmpty())
        val start = dto.facts.first { it.label == "开始活跃" }
        assertEquals("09:00", start.todayText) // 540 分钟 = 09:00
        assertTrue(start.baselineText.startsWith("约 "))
        val movement = dto.facts.first { it.label == "日间移动" }
        assertTrue(movement.deltaText.contains("比近期") || movement.deltaText.contains("和近期"))
    }

    @Test
    fun warmingUpDayOneShowsFactsForImmediateFeedback() {
        // v0.7.4 UX：第 1 天（WARMING_UP）即给出当天事实句，让「它在记录」立刻可感
        val dto = generate(makeToday(activeStart = 495, movement = 1.06, pad = 20), emptyList())
        assertEquals("WARMING_UP", dto.status)
        assertTrue("等待文案仍在", dto.summary.contains("正在慢慢了解"))
        assertTrue("第 1 天应包含当天事实", dto.summary.contains("今天累计屏幕互动"))
    }
}
