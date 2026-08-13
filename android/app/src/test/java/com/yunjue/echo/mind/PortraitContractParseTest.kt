package com.yunjue.echo.mind

import com.yunjue.echo.mind.data.PortraitParsers
import com.yunjue.echo.mind.model.DailyPortraitDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.2 跨端契约解析测试（纯 JVM，不依赖 Robolectric / Android 框架）。
 *
 * 读取 canonical JSON fixtures（与 backend/tests/fixtures/portrait_contract 同源，
 * 变更需两端同步——Phase 1.2 主理人将建立单一事实源机制），使用生产解析逻辑
 * （[PortraitParsers.parseDailyPortrait] / [PortraitParsers.parsePortraitList] /
 * [PortraitParsers.parseBaselineStatus]，顶层 internal 钩子）断言 DTO 精确匹配：
 * - dimensions 嵌套对象 {value, metric, z}（Phase 5 键：RHYTHM/MOVEMENT/
 *   SCREEN_AMOUNT/SCREEN_TIMING/DAY_STRUCTURE/STABILITY，SCREEN_PATTERN 已拆分）；
 * - facts 四要素（label/today_text/baseline_text/delta_text）；
 * - BaselineStatusOut.bucket_usage（String）/ today_coverage（Double）；
 * - timezone_used / date 解析。
 *
 * 依赖 app/build.gradle.kts 的 testImplementation("org.json:json:20240303")
 * （android.jar stub 在纯 JVM 下方法抛异常/返回默认值，无法解析 JSONObject）。
 *
 * 注意：fixture 中 STABILITY 维度额外携带 diff_count（后端输出），端侧 DTO 仅消费
 * value/metric/z，额外字段被忽略——本测试显式断言这一点（不强约束 diff_count）。
 */
class PortraitContractParseTest {

    // ===== fixtures 读取（classpath resources，与 backend 同源） =====

    private fun fixture(name: String): String {
        val stream = PortraitContractParseTest::class.java.getResourceAsStream("/portrait_contract/$name.json")
            ?: error(
                "缺少契约 fixture: /portrait_contract/$name.json —— 应与 " +
                    "backend/tests/fixtures/portrait_contract/$name.json 同源，变更需两端同步"
            )
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parsePortrait(name: String): DailyPortraitDto {
        val dto = PortraitParsers.parseDailyPortrait(fixture(name))
        assertNotNull("fixture $name 应可解析", dto)
        return dto!!
    }

    /** 所有 fixture 必须可解析（防漂移哨兵）。 */
    @Test
    fun allContractFixturesParseSuccessfully() {
        val names = listOf(
            "ready_full", "warming_up", "early_baseline", "partial_data", "low_confidence",
            "no_portrait", "dimensions_nested", "facts_explain", "timezone"
        )
        for (name in names) {
            assertNotNull("fixture $name 应可解析", PortraitParsers.parseDailyPortrait(fixture(name)))
        }
        assertNotNull("baseline_status 应可解析", PortraitParsers.parseBaselineStatus(fixture("baseline_status")))
    }

    // ===== ready_full：完整画像 =====

    @Test
    fun readyFullParsesAllFields() {
        val dto = parsePortrait("ready_full")
        assertEquals("2026-08-10", dto.date)
        assertEquals("READY", dto.status)
        assertEquals("HIGH", dto.confidence)
        assertEquals(21, dto.baselineDays)
        assertEquals("base-v1", dto.baselineVersion)
        assertEquals(listOf("作息后移", "屏幕时间后移"), dto.headline)
        assertTrue("summary 不应为空", dto.summary.isNotEmpty())
        assertEquals("Asia/Shanghai", dto.timezoneUsed)
        assertEquals("dimensions 应为 6 个 Phase 5 维度", 6, dto.dimensions.size)
        assertEquals(
            setOf("RHYTHM", "MOVEMENT", "SCREEN_AMOUNT", "SCREEN_TIMING", "DAY_STRUCTURE", "STABILITY"),
            dto.dimensions.keys
        )
        // 嵌套强类型：value/metric/z
        val rhythm = dto.dimensions["RHYTHM"]
        assertEquals("LATER", rhythm?.value)
        assertEquals("active_start_minute", rhythm?.metric)
        assertEquals(1.2, rhythm?.z ?: Double.NaN, 1e-9)
        // STABILITY：metric/z 为 null，value 为聚合值；diff_count 额外字段被忽略
        val stability = dto.dimensions["STABILITY"]
        assertEquals("SLIGHTLY_DIFFERENT", stability?.value)
        assertNull("STABILITY.metric 应为 null", stability?.metric)
        assertNull("STABILITY.z 应为 null", stability?.z)
        // facts 四要素
        assertEquals(2, dto.facts.size)
        assertEquals("起床时间", dto.facts[0].label)
        assertEquals("今天 08:12 起床", dto.facts[0].todayText)
        assertEquals("平常 07:30 起床", dto.facts[0].baselineText)
        assertEquals("比平常晚 42 分钟", dto.facts[0].deltaText)
        // coverage 保留数值
        assertEquals(0.92, (dto.coverage?.get("coverage_score") as? Double) ?: -1.0, 1e-9)
    }

    // ===== 状态机各态 =====

    @Test
    fun warmingUpHasNoDimensions() {
        val dto = parsePortrait("warming_up")
        assertEquals("WARMING_UP", dto.status)
        assertEquals("LOW", dto.confidence)
        assertEquals(0, dto.baselineDays)
        assertTrue(dto.dimensions.isEmpty())
        assertTrue(dto.facts.isEmpty())
        assertTrue(dto.headline.isEmpty())
    }

    @Test
    fun earlyBaselineIsSummaryOnly() {
        val dto = parsePortrait("early_baseline")
        assertEquals("EARLY_BASELINE", dto.status)
        assertEquals(5, dto.baselineDays)
        assertTrue(dto.dimensions.isEmpty())
        assertTrue(dto.summary.isNotEmpty())
    }

    @Test
    fun partialDataKeepsPresentDimensionsOnly() {
        val dto = parsePortrait("partial_data")
        assertEquals("PARTIAL_DATA", dto.status)
        assertEquals("MEDIUM", dto.confidence)
        // 屏幕数据缺失 → SCREEN_AMOUNT/SCREEN_TIMING 维度省略（Phase 5 语义）
        assertEquals(setOf("RHYTHM", "MOVEMENT", "DAY_STRUCTURE", "STABILITY"), dto.dimensions.keys)
        assertEquals(1, dto.facts.size)
        assertEquals("比平常早 40 分钟", dto.facts[0].deltaText)
    }

    @Test
    fun lowConfidenceHasNoDimensions() {
        val dto = parsePortrait("low_confidence")
        assertEquals("LOW_CONFIDENCE", dto.status)
        assertEquals("LOW", dto.confidence)
        assertTrue(dto.dimensions.isEmpty())
    }

    @Test
    fun noPortraitIsLightweightStatusView() {
        // GET today 对无画像用户返回轻量状态视图：无 headline/facts 键 → 端侧默认空
        val dto = parsePortrait("no_portrait")
        assertEquals("WARMING_UP", dto.status)
        assertTrue(dto.headline.isEmpty())
        assertTrue(dto.facts.isEmpty())
        assertTrue(dto.dimensions.isEmpty())
        assertNotNull(dto.timezoneUsed)
    }

    // ===== BaselineStatusOut 强类型 =====

    @Test
    fun baselineStatusParsesTypedFields() {
        val dto = PortraitParsers.parseBaselineStatus(fixture("baseline_status"))
        assertNotNull(dto)
        assertEquals("BASELINE_READY", dto!!.status)
        assertEquals(21, dto.baselineDays)
        assertEquals("base-v1", dto.baselineVersion)
        assertEquals("2026-07-20", dto.windowStart)
        assertEquals("2026-08-09", dto.windowEnd)
        // Phase 1.1：bucket_usage 为 String、today_coverage 为 Double
        assertEquals("weekday", dto.bucketUsage)
        assertEquals(0.92, dto.todayCoverage, 1e-9)
    }

    // ===== dimensions 嵌套结构 =====

    @Test
    fun dimensionsNestedValueMetricZ() {
        val dto = parsePortrait("dimensions_nested")
        assertEquals(setOf("RHYTHM", "MOVEMENT", "SCREEN_AMOUNT", "SCREEN_TIMING", "DAY_STRUCTURE", "STABILITY"), dto.dimensions.keys)
        assertEquals(1.2, dto.dimensions["RHYTHM"]?.z ?: Double.NaN, 1e-9)
        assertEquals(0.7, dto.dimensions["MOVEMENT"]?.z ?: Double.NaN, 1e-9)
        assertEquals(-0.5, dto.dimensions["SCREEN_AMOUNT"]?.z ?: Double.NaN, 1e-9)
        assertEquals(-1.1, dto.dimensions["SCREEN_TIMING"]?.z ?: Double.NaN, 1e-9)
        assertEquals("MORE_FRAGMENTED", dto.dimensions["DAY_STRUCTURE"]?.value)
        assertEquals("CLEARLY_DIFFERENT", dto.dimensions["STABILITY"]?.value)
        assertNull(dto.dimensions["STABILITY"]?.metric)
        assertNull(dto.dimensions["STABILITY"]?.z)
        // 便捷方法
        assertEquals("LATER", dto.dimensionValue("RHYTHM"))
        assertNull(dto.dimensionValue("UNKNOWN_KEY"))
    }

    // ===== facts 三/四要素 =====

    @Test
    fun factsExplainFields() {
        val dto = parsePortrait("facts_explain")
        assertEquals(3, dto.facts.size)
        val first = dto.facts[0]
        assertEquals("起床时间", first.label)
        assertEquals("今天 08:12 起床", first.todayText)
        assertEquals("平常 07:30 起床", first.baselineText)
        assertEquals("比平常晚 42 分钟", first.deltaText)
        val third = dto.facts[2]
        assertEquals("活动量", third.label)
        assertEquals("与平常相当", third.deltaText)
    }

    // ===== timezone / local date =====

    @Test
    fun timezoneUsedAndDateParsed() {
        val dto = parsePortrait("timezone")
        assertEquals("America/Los_Angeles", dto.timezoneUsed)
        assertEquals("2026-08-10", dto.date)
        assertEquals("SIMILAR", dto.dimensions["RHYTHM"]?.value)
        assertEquals("VERY_SIMILAR", dto.dimensions["STABILITY"]?.value)
    }

    // ===== parsePortraitList 批量 =====

    @Test
    fun portraitListParsesAndSortsByDate() {
        val body = """
            {
              "portraits": [
                {"date": "2026-08-10", "status": "READY", "confidence": "HIGH", "baseline_days": 21, "dimensions": {}},
                {"date": "2026-08-09", "status": "PARTIAL_DATA", "confidence": "MEDIUM", "baseline_days": 9, "dimensions": {}}
              ]
            }
        """.trimIndent()
        val list = PortraitParsers.parsePortraitList(body)
        assertEquals(listOf("2026-08-09", "2026-08-10"), list.map { it.date })
        assertEquals("PARTIAL_DATA", list[0].status)
    }

    // ===== 隐私/契约不变量 =====

    @Test
    fun dimensionValuesNeverUseForbiddenClinicalWords() {
        // 产品契约：dimensions.value 禁止 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL
        val forbidden = setOf("GOOD", "BAD", "HEALTHY", "NORMAL", "ABNORMAL")
        for (name in listOf("ready_full", "partial_data", "dimensions_nested", "timezone")) {
            val dto = parsePortrait(name)
            val values = dto.dimensions.values.map { it.value }
            assertTrue(
                "fixture $name 维度值 $values 不应包含禁止词 $forbidden",
                values.none { it in forbidden }
            )
        }
    }
}
