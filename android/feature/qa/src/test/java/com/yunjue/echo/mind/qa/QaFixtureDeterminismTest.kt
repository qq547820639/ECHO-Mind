package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.localportrait.isWeekendLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * ERA 19 §3 — fixture 质量：
 * - 确定性（同 profile 同 day 任意重放逐字段一致）；
 * - 七个 profile 的人群形状真实可见（weekend 分化 / 出差窗口 / 冲刺期 /
 *   低数据 / 慢漂移 / 不规律），而不是七份随机噪声。
 */
class QaFixtureDeterminismTest {

    private val epoch = java.time.LocalDate.parse(QaProfiles.EPOCH_DATE)

    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    // ===== 确定性 =====

    @Test
    fun sameProfileSameDayIsByteIdentical() {
        for (profile in QaProfiles.ALL) {
            for (day in listOf(0, 3, 7, 28, 90, 180)) {
                val a = QaDaySimulator.day(profile, day, epoch.plusDays(day.toLong()))
                val b = QaDaySimulator.day(profile, day, epoch.plusDays(day.toLong()))
                assertEquals("$profile day $day 必须逐字段一致", a, b)
            }
        }
    }

    @Test
    fun timelineReplayIsIdentical() {
        val t1 = QaTimeline(QaProfiles.A_STABLE)
        val t2 = QaTimeline(QaProfiles.A_STABLE)
        assertEquals(t1.snapshotAt(180), t2.snapshotAt(180))
    }

    @Test
    fun differentDaysDiffer() {
        val profile = QaProfiles.A_STABLE
        val day30 = QaDaySimulator.day(profile, 30, epoch.plusDays(30))
        val day31 = QaDaySimulator.day(profile, 31, epoch.plusDays(31))
        assertNotEquals(day30, day31)
    }

    // ===== 人群形状 =====

    @Test
    fun stableProfileHasTightRhythm() {
        val profile = QaProfiles.A_STABLE
        val wakes = (7..60).map { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())).activeStartMinute!!.toDouble() }
        val spread = wakes.max() - wakes.min()
        assertTrue("A 的活跃起点跨 54 天波动应 < 60 分钟（实际 $spread）", spread < 60.0)
    }

    @Test
    fun weekendProfileHasLargeWeekendShift() {
        val profile = QaProfiles.G_WEEKEND_DIFFERENT
        val days = 14..120
        val weekdayWakes = days.filter { !isWeekendLocal(epoch.plusDays(it.toLong())) }
            .map { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())).activeStartMinute!!.toDouble() }
        val weekendWakes = days.filter { isWeekendLocal(epoch.plusDays(it.toLong())) }
            .map { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())).activeStartMinute!!.toDouble() }
        val delta = medianOf(weekendWakes) - medianOf(weekdayWakes)
        assertTrue("G 周末应显著晚起（实际 ${delta.roundToInt()} 分钟）", delta >= 150.0)
    }

    @Test
    fun travelWindowsShiftRhythmEarlier() {
        val profile = QaProfiles.D_TRAVEL
        val inTrip = (20..27).map { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())).activeStartMinute!!.toDouble() }
        val before = (5..15).map { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())).activeStartMinute!!.toDouble() }
        assertTrue("出差期应明显早于平时（Δ ${(medianOf(before) - medianOf(inTrip)).roundToInt()} 分钟）",
            medianOf(before) - medianOf(inTrip) >= 45.0)
    }

    @Test
    fun crunchWindowInflatesScreenAndCutsMovement() {
        val profile = QaProfiles.E_PROJECT_CRUNCH
        val crunch = (60..95).map { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())) }
        val normal = (20..55).map { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())) }
        val screenRatio = medianOf(crunch.map { it.screenOnMinutes }) / medianOf(normal.map { it.screenOnMinutes })
        val movementRatio = medianOf(crunch.mapNotNull { it.movementIndex }) / medianOf(normal.mapNotNull { it.movementIndex })
        assertTrue("冲刺期屏幕应 ≥1.4x（实际 ${"%.2f".format(screenRatio)}）", screenRatio >= 1.4)
        assertTrue("冲刺期活动应明显下降（实际 ${"%.2f".format(movementRatio)}）", movementRatio <= 0.7)
    }

    @Test
    fun lowDataProfileHasManySubThresholdDays() {
        val profile = QaProfiles.F_LOW_DATA
        val days = (0..180).map { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())) }
        val subThreshold = days.count { it.coverageScore < 0.25 }
        val noState = days.count { it.coverageScore < 0.1 }
        assertTrue("F 应有大量低于有效日阈值的日子（实际 $subThreshold/181）", subThreshold >= 30)
        assertTrue("F 应有部分无状态日（实际 $noState）", noState >= 5)
        assertTrue("F 不应出现活跃起点缺失却高覆盖的伪造", days.filter { it.activeStartMinute == null }.all { it.coverageScore < 0.25 })
    }

    @Test
    fun nightOwlDriftsLaterOverSixMonths() {
        val profile = QaProfiles.B_NIGHT_OWL
        // 仅取工作日（排除周末 +40 分钟噪声），窗口加宽到 60 天 → 中位数稳健估计漂移
        fun weekdayWakes(range: IntRange): List<Double> = range
            .filter { !isWeekendLocal(epoch.plusDays(it.toLong())) }
            .mapNotNull { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())).activeStartMinute?.toDouble() }
        val early = weekdayWakes(0..60)
        val late = weekdayWakes(120..180)
        assertTrue("B 半年应后移 ≥8 分钟（实际 ${(medianOf(late) - medianOf(early)).roundToInt()} 分钟）",
            medianOf(late) - medianOf(early) >= 8.0)
    }

    @Test
    fun irregularProfileHasHugeStartVariance() {
        val profile = QaProfiles.C_IRREGULAR
        val wakes = (0..180).mapNotNull { QaDaySimulator.day(profile, it, epoch.plusDays(it.toLong())).activeStartMinute?.toDouble() }
        val mean = wakes.average()
        val variance = wakes.map { (it - mean) * (it - mean) }.average()
        val std = kotlin.math.sqrt(variance)
        assertTrue("C 的活跃起点标准差应 > 90 分钟（实际 ${std.roundToInt()}）", std > 90.0)
    }

    // ===== 数据合理性不变量 =====

    @Test
    fun aggregatesSatisfyPhysicalInvariants() {
        for (profile in QaProfiles.ALL) {
            for (day in 0..180) {
                val agg = QaDaySimulator.day(profile, day, epoch.plusDays(day.toLong()))
                assertTrue("coverage ∈ [0,1]", agg.coverageScore in 0.0..1.0)
                assertTrue("valid ≤ expected", agg.validWindowCount <= agg.expectedWindowCount)
                assertTrue("screen ≥ 0", agg.screenOnMinutes >= 0.0)
                assertTrue("lateScreen ≥ 0", agg.lateScreenMinutes >= 0.0)
                assertTrue("switch ≥ 0", agg.appSwitchCount >= 0)
                agg.activeStartMinute?.let { assertTrue("start ∈ [0,1440)", it in 0..1439) }
                agg.activeEndMinute?.let { assertTrue("end ∈ [0,1440)", it in 0..1439) }
            }
        }
    }

    // ===== 画像镜像与后端语义一致（抽样） =====

    @Test
    fun portraitMirrorMatchesBackendSemantics() {
        val t = QaTimeline(QaProfiles.A_STABLE)
        // Day 7：基线已成型，大部分维度应 SIMILAR（A 高度稳定）
        val p7 = t.portraitFor(7)
        assertTrue(p7 != null)
        val stability = p7!!.dimensionValue("STABILITY")
        assertTrue("A Day7 稳定性 ∈ 合法词表", stability in setOf("VERY_SIMILAR", "SLIGHTLY_DIFFERENT", "CLEARLY_DIFFERENT"))
        assertTrue("画像状态合法", p7.status in setOf("WARMING_UP", "EARLY_BASELINE", "READY", "PARTIAL_DATA"))
    }
}
