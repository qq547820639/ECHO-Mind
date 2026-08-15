package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.localportrait.LocalBaselineSnapshot
import com.yunjue.echo.mind.localportrait.LocalDayAggregate
import com.yunjue.echo.mind.localportrait.LocalMetricStats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

/**
 * ERA 31 — QaPortraitMirror 跨语言黄金门（QA 测产品，不是 QA 重写产品）。
 *
 * fixture.json（语言中立输入）→ backend compute_dimensions 产出 golden.json（产品真值，
 * 由 backend/scripts/export_mirror_golden.py 生成，backend test_mirror_golden.py 保证零漂移）。
 * 本测试用同一 fixture 构建 Kotlin 结构并对拍 mirror 输出：
 * 镜像漂移在 Android CI 立即变红，且修复必须跨语言同 commit（禁止分叉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QaPortraitMirrorGoldenTest {

    @Test
    fun mirrorMatchesBackendGoldenForAllCases() {
        val dir = mirrorGoldenDir()
        val fixture = JSONObject(File(dir, "fixture.json").readText())
        val golden = JSONObject(File(dir, "golden.json").readText())
        val fixtureCases = fixture.getJSONArray("cases")
        val goldenCases = golden.getJSONArray("cases")
        assertEquals("case 数量一致", fixtureCases.length(), goldenCases.length())
        assertEquals("golden 版本", 1, golden.getInt("golden_version"))

        for (i in 0 until fixtureCases.length()) {
            val fixtureCase = fixtureCases.getJSONObject(i)
            val goldenCase = goldenCases.getJSONObject(i)
            val name = fixtureCase.getString("name")
            assertEquals("case 名一致", name, goldenCase.getString("name"))

            val aggregate = aggregateFrom(fixtureCase.getJSONObject("today"))
            val baselineMetrics = fixtureCase.getJSONObject("baseline_metrics")
            val snapshot = if (baselineMetrics.length() == 0) null else baselineFrom(baselineMetrics)

            val dims = QaPortraitMirror.computeDimensions(aggregate, snapshot)
            val expected = goldenCase.getJSONObject("dimensions")
            assertDimensionsEqual(name, expected, dims)
        }
    }

    private fun assertDimensionsEqual(
        caseName: String,
        expected: JSONObject,
        actual: Map<String, com.yunjue.echo.mind.model.PortraitDimensionDto>,
    ) {
        val expectedKeys = expected.keys().asSequence().toSet()
        assertEquals("$caseName 维度键集合", expectedKeys, actual.keys)
        for (key in expectedKeys) {
            val expDim = expected.getJSONObject(key)
            val actDim = actual.getValue(key)
            assertEquals("$caseName $key.value", expDim.getString("value"), actDim.value)
            val expMetric = if (expDim.isNull("metric")) null else expDim.getString("metric")
            assertEquals("$caseName $key.metric", expMetric, actDim.metric)
            if (expDim.isNull("z")) {
                assertNull("$caseName $key.z 应为 null", actDim.z)
            } else {
                val expZ = expDim.getDouble("z")
                val actZ = actDim.z ?: error("$caseName $key.z 缺失")
                assertTrue(
                    "$caseName $key.z 漂移（golden=$expZ mirror=$actZ）",
                    abs(round4(actZ) - expZ) < 1e-9,
                )
            }
            // STABILITY 的 diff_count 是 backend 附加字段（UI 不消费），mirror 不实现——记录在审计报告。
            if (key == "STABILITY") {
                assertTrue("$caseName STABILITY.diff_count 存在", expDim.has("diff_count"))
            }
        }
    }

    private fun round4(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0

    private fun aggregateFrom(today: JSONObject): LocalDayAggregate = LocalDayAggregate(
        localDate = LocalDate.parse("2026-08-10"),
        timezone = "Asia/Shanghai",
        coverageScore = 0.9,
        validWindowCount = 250,
        expectedWindowCount = 288,
        movementIndex = today.optDoubleOrNull("movement_index"),
        movementVariability = null,
        screenOnMinutes = today.optDoubleOrNull("screen_on_minutes") ?: 0.0,
        screenOpenCount = 0,
        lateScreenMinutes = today.optDoubleOrNull("late_screen_minutes") ?: 0.0,
        appSwitchCount = 0,
        notificationCount = 0,
        activeStartMinute = today.optIntOrNull("active_start_minute"),
        activeEndMinute = today.optIntOrNull("active_end_minute"),
        activeHourSpread = today.optDoubleOrNull("active_hour_spread"),
        sourcesPresent = emptyList(),
        missingSources = emptyList(),
    )

    private fun baselineFrom(metricsJson: JSONObject): LocalBaselineSnapshot = LocalBaselineSnapshot(
        bucket = "weekday",
        windowStart = LocalDate.parse("2026-07-14"),
        windowEnd = LocalDate.parse("2026-08-09"),
        validDays = 21,
        metrics = metricsJson.keys().asSequence().associateWith { key ->
            statsFrom(metricsJson.getJSONObject(key))
        },
    )

    private fun statsFrom(stats: JSONObject): LocalMetricStats = LocalMetricStats(
        median = stats.optDoubleOrNull("median"),
        mad = stats.optDoubleOrNull("mad"),
        p10 = stats.optDoubleOrNull("p10"),
        p25 = stats.optDoubleOrNull("p25"),
        p75 = stats.optDoubleOrNull("p75"),
        p90 = stats.optDoubleOrNull("p90"),
        validDays = 21,
    )

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        optDouble(name, Double.NaN).takeIf { !it.isNaN() }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (isNull(name)) null else optInt(name)

    private fun mirrorGoldenDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, ".git").exists()) dir = dir.parentFile
        val repo = dir ?: error("找不到仓库根（.git）")
        return File(repo, "qa/reports/mirror_goldens")
    }
}
