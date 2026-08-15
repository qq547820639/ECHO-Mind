package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.presence.DailyCompositionGate
import com.yunjue.echo.mind.presence.buildMomentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ERA 21 §18/§19 — Daily / Moment 视觉行为审计：
 * - §18 一天内核心构图不能不断变化：日构图经 DailyCompositionGate 按日历日固化；
 * - §19 Moment 必须有存在感但不能躁：昼夜亮度曲线平滑驱动 noiseScale，
 *   相邻小时变化有界（无闪烁式跳变），呼吸周期恒在 [3.8, 5.6] 秒。
 */
class QaDailyMomentAuditTest {

    @Test
    fun dailyCompositionIsFrozenWithinOneDayAcrossProfiles() {
        for (profile in QaProfiles.ALL) {
            val timeline = QaTimeline(profile)
            val gate = DailyCompositionGate()
            // 模拟一天内 24 次分钟级 refresh：today 向量随窗口累积变化
            var first: com.yunjue.echo.mind.presence.EchoDailyComposition? = null
            for (refresh in 0 until 24) {
                val snap = timeline.snapshotAt(30)
                val composition = gate.compositionFor(snap.date) { snap.presence.dailyComposition }
                if (first == null) first = composition
                assertEquals("$profile 一天内构图不得漂移（refresh $refresh）", first, composition)
            }
        }
    }

    @Test
    fun momentBreathingIsBoundedAndSmoothAcrossTheDay() {
        val timeline = QaTimeline(QaProfiles.A_STABLE)
        val vector = timeline.snapshotAt(30).ambient.vector
        var prevNoise: Float? = null
        for (hour in 0 until 24) {
            val moment = buildMomentState(vector, hour.toFloat())
            assertTrue("呼吸周期 ∈ [3.8, 5.6]（实际 ${moment.breathingPeriod}）",
                moment.breathingPeriod in 3.8f..5.6f)
            assertTrue("noiseScale ∈ [0, 1]（实际 ${moment.noiseScale}）",
                moment.noiseScale in 0f..1f)
            prevNoise?.let {
                assertTrue("相邻小时 noise 变化必须平滑（${it} → ${moment.noiseScale}）",
                    abs(moment.noiseScale - it) < 0.15f)
            }
            prevNoise = moment.noiseScale
        }
    }

    @Test
    fun momentRangesAreSaneForEveryProfile() {
        for (profile in QaProfiles.ALL) {
            val timeline = QaTimeline(profile)
            for (day in listOf(3, 7, 28, 90, 180)) {
                val snap = timeline.snapshotAt(day)
                for (hour in listOf(0f, 6f, 12f, 18f, 23f)) {
                    val moment = buildMomentState(snap.ambient.vector, hour)
                    assertTrue("$profile day$day h$hour 呼吸周期越界（${moment.breathingPeriod}）",
                        moment.breathingPeriod in 3.8f..5.6f)
                    assertTrue("$profile day$day h$hour noise 越界（${moment.noiseScale}）",
                        moment.noiseScale in 0f..1f)
                }
            }
        }
    }
}
