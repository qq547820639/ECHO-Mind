package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoLifeSeason

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * ERA 21 §16/§17 — Life Season 慢变化质量门：
 *
 * - §16 一天异常不得改变 Season（minimum persistence）；
 * - §16 提交需连续多日确认（hysteresis：回弹即重置）；
 * - §17 A → transition → B（drift 数天平滑，绝不瞬切）；
 * - 回退同样需要持久确认；
 * - 同日多次喂入不累计天数（分钟级 refresh 场景）；
 * - confidence 早期低、持续一致才高，且受数据充分度约束。
 */
class LifeSeasonTrackerTest {

    private fun season(
        rhythmShift: String = "stable",
        drift: Float = 0.1f,
        phaseIndex: Int = 2,
    ) = EchoLifeSeason(
        phaseIndex = phaseIndex,
        drift = drift,
        rhythmShift = rhythmShift,
        screenFragmentation = "stable",
        activityVariability = "stable",
        mobilityTrend = "stable",
        regularityTrend = "stable",
    )

    private val d0 = LocalDate.parse("2026-01-05")

    private fun date(offset: Long) = d0.plusDays(offset)

    // ===== 首个 Season 直接采纳 =====

    @Test
    fun firstSeasonCommitsImmediately() {
        val t = LifeSeasonTracker.start(season(rhythmShift = "later", drift = 0.4f), d0)
        assertEquals("later", t.target.rhythmShift)
        assertEquals(0.4f, t.effective.drift)
        assertFalse(t.isTransitioning)
    }

    // ===== §16 一天异常不改变 Season =====

    @Test
    fun oneDayAnomalyDoesNotChangeSeason() {
        val t0 = LifeSeasonTracker.start(season(rhythmShift = "stable"), date(0))
        // 连续 20 天稳定
        var t = t0
        for (i in 1..20) t = t.update(season(rhythmShift = "stable"), date(i.toLong()))
        assertFalse(t.isTransitioning)

        // 一天异常（later）
        val afterAnomaly = t.update(season(rhythmShift = "later", drift = 0.9f), date(21))
        assertEquals("一天异常不得提交", "stable", afterAnomaly.target.rhythmShift)
        assertFalse("一天异常不得进入 transition", afterAnomaly.isTransitioning)
        assertEquals("候选应被记录", "later", afterAnomaly.candidate?.rhythmShift)

        // 次日回弹 → 候选清零
        val rebound = afterAnomaly.update(season(rhythmShift = "stable"), date(22))
        assertEquals(null, rebound.candidate)
        assertEquals("stable", rebound.target.rhythmShift)
    }

    // ===== §16/§17 持续变化才提交，且带 transition =====

    @Test
    fun sustainedChangeCommitsWithTransition() {
        var t = LifeSeasonTracker.start(season(rhythmShift = "stable", drift = 0.1f), date(0))
        for (i in 1..10) t = t.update(season(rhythmShift = "stable", drift = 0.1f), date(i.toLong()))

        // 连续 3 个新日期（confirmationDays = 3）出现 later → 提交
        t = t.update(season(rhythmShift = "later", drift = 0.9f), date(11))
        assertFalse("第 1 天不提交", t.target.rhythmShift == "later")
        t = t.update(season(rhythmShift = "later", drift = 0.9f), date(12))
        assertFalse("第 2 天不提交", t.target.rhythmShift == "later")
        t = t.update(season(rhythmShift = "later", drift = 0.9f), date(13))
        assertEquals("第 3 天提交", "later", t.target.rhythmShift)
        assertTrue("提交后进入 transition", t.isTransitioning)
        assertEquals("transition 首日 drift 保持旧值", 0.1f, t.effective.drift)

        // drift 逐日平滑（TRANSITION_DAYS = 5）
        val drifts = mutableListOf<Float>()
        for (i in 14..18) {
            t = t.update(season(rhythmShift = "later", drift = 0.9f), date(i.toLong()))
            drifts += t.effective.drift
        }
        assertTrue("transition 结束（进度 1）", !t.isTransitioning)
        assertEquals("transition 完成到达目标 drift", 0.9f, t.effective.drift, 1e-6f)
        assertTrue("drift 逐日单调平滑", drifts.zipWithNext().all { (a, b) -> b > a })
    }

    // ===== §16 回退同样需要持久确认 =====

    @Test
    fun revertAlsoRequiresPersistence() {
        var t = LifeSeasonTracker.start(season(rhythmShift = "later"), date(0))
        for (i in 1..10) t = t.update(season(rhythmShift = "later"), date(i.toLong()))
        // 一天回 stable → 不提交
        t = t.update(season(rhythmShift = "stable"), date(11))
        assertEquals("later", t.target.rhythmShift)
        // 次日回 later → 候选清零
        t = t.update(season(rhythmShift = "later"), date(12))
        assertEquals(null, t.candidate)
        assertEquals("later", t.target.rhythmShift)
        // 连续 3 天 stable → 才回退
        t = t.update(season(rhythmShift = "stable"), date(13))
        t = t.update(season(rhythmShift = "stable"), date(14))
        t = t.update(season(rhythmShift = "stable"), date(15))
        assertEquals("stable", t.target.rhythmShift)
        assertTrue(t.isTransitioning)
    }

    // ===== 同日多次喂入不累计（分钟级 refresh 去重） =====

    @Test
    fun sameDateUpdatesDoNotAccumulateDays() {
        var t = LifeSeasonTracker.start(season(rhythmShift = "stable"), date(0))
        // 同一天喂 10 次 later（例如分钟级 refresh 反复计算）
        for (i in 1..10) t = t.update(season(rhythmShift = "later"), date(1))
        assertEquals("stable", t.target.rhythmShift)
        assertEquals("同日不累计 streak", 1, t.candidateStreak)
        // 新日期第 1 次 later → streak 2
        t = t.update(season(rhythmShift = "later"), date(2))
        assertEquals(2, t.candidateStreak)
        // 新日期第 2 次 later → streak 3 → 提交
        t = t.update(season(rhythmShift = "later"), date(3))
        assertEquals("later", t.target.rhythmShift)
        assertTrue(t.isTransitioning)
    }

    // ===== confidence：早期低、数据充分且一致才高 =====

    @Test
    fun confidenceIsLowEarlyAndRisesWithAgreement() {
        val t0 = LifeSeasonTracker.start(season(rhythmShift = "stable"), date(0))
        assertTrue("Day 0 置信低", t0.confidence < 0.1f)

        var t = t0
        for (i in 1..30) t = t.update(season(rhythmShift = "stable"), date(i.toLong()))
        assertTrue("30 天持续一致应接近 1", t.confidence > 0.9f)

        // 提交后置信重置（新现实重新积累）
        val committed = t.update(season(rhythmShift = "later"), date(31))
            .update(season(rhythmShift = "later"), date(32))
            .update(season(rhythmShift = "later"), date(33))
        assertEquals("later", committed.target.rhythmShift)
        assertTrue("刚提交置信低", committed.confidence < 0.3f)
    }

    // ===== 签名不含 drift：drift 波动不触发候选 =====

    @Test
    fun driftOnlyChangeDoesNotStartCandidate() {
        var t = LifeSeasonTracker.start(season(rhythmShift = "stable", drift = 0.2f), date(0))
        for (i in 1..5) t = t.update(season(rhythmShift = "stable", drift = 0.85f), date(i.toLong()))
        assertEquals(null, t.candidate)
        assertFalse(t.isTransitioning)
        // drift 属于连续量：直接在 effective 上平滑跟进（逐日趋近）
        assertTrue(t.effective.drift > 0.2f)
    }
}
