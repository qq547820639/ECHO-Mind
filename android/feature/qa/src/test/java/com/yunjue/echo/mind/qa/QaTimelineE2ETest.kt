package com.yunjue.echo.mind.qa
import com.yunjue.echo.mind.model.EchoMaturity

import com.yunjue.echo.mind.presence.deriveIdentityGenome
import com.yunjue.echo.mind.presence.identityDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 19 §3/§4 + FINAL ACCEPTANCE — Day 0/3/7/28/90/180 产品验收门。
 *
 * 不是 crash 测试：每一条断言对应一条用户体验验收：
 * - Day 0：ECHO 立即苏醒（非空白首帧、SEED 文案锚点、身份已定型）；
 * - Day 3/7：成熟度随日历推进（EMERGING → KNOWN），基线完成有真实跃迁；
 * - Day 28：MATURE（视觉稳定成熟——ERA 21 修复后可达）；
 * - Day 90/180：Life Season 真正体现长期变化（出差/冲刺/慢漂移看得见）；
 * - 全程：同 profile 身份连续（Day 0 与 Day 180 是同一个 ECHO）。
 */
class QaTimelineE2ETest {

    private val dayAnchors = QaProfiles.SNAPSHOT_DAYS

    private fun timelineFor(profile: QaProfileSpec): QaTimeline = QaTimeline(profile)

    // ===== FINAL ACCEPTANCE — DAY 0：它开始了 =====

    @Test
    fun day0AwakensWithIdentityAndNonBlankFrame() {
        for (profile in QaProfiles.ALL) {
            val snap = timelineFor(profile).snapshotAt(0)
            assertEquals("$profile Day0 maturity", EchoMaturity.SEED, snap.presence.maturity)
            // 身份第一天即定型（installation seed + 偏好）
            val expected = deriveIdentityGenome(
                seed = profile.identitySeed,
                baselineStability = snap.ambient.vector.regularity,
                motionPreference = profile.motionPreference,
            )
            assertEquals("$profile Day0 身份确定性", expected, snap.identity)
            // 非空白首帧（粒子 > 0，ECHO 可见）
            val frame = QaTimeline.computeFrame(snap)
            assertTrue("$profile Day0 帧必须有可见粒子", frame.particles.isNotEmpty())
            // Day0 不伪造观察：画像要么不存在，要么只有 STABILITY（无基线不可比较任何维度）
            val p = snap.portrait
            assertTrue(
                "$profile Day0 不伪造画像维度（实际 ${p?.dimensions?.keys}）",
                p == null || p.dimensions.keys.all { it == "STABILITY" },
            )
            // Day0 基线为空（validDays=0，无伪造统计）
            assertEquals("$profile Day0 基线有效日必须为 0", 0, snap.baseline?.validDays ?: 0)
        }
    }

    // ===== Day 3 / Day 7 / Day 28：成熟度跃迁 =====

    @Test
    fun stableProfileMaturityAdvancesOnCalendar() {
        val t = timelineFor(QaProfiles.A_STABLE)
        assertEquals(EchoMaturity.SEED, t.snapshotAt(0).presence.maturity)
        assertEquals(EchoMaturity.EMERGING, t.snapshotAt(3).presence.maturity)
        assertEquals(EchoMaturity.KNOWN, t.snapshotAt(7).presence.maturity)
        assertEquals(EchoMaturity.MATURE, t.snapshotAt(28).presence.maturity)
        assertEquals(EchoMaturity.MATURE, t.snapshotAt(90).presence.maturity)
        assertEquals(EchoMaturity.MATURE, t.snapshotAt(180).presence.maturity)
    }

    @Test
    fun allProfilesReachMatureByDay90() {
        for (profile in QaProfiles.ALL) {
            val snap = timelineFor(profile).snapshotAt(90)
            assertEquals("$profile Day90 应为 MATURE", EchoMaturity.MATURE, snap.presence.maturity)
            // raw 层：日历 90 天 → phaseIndex 3
            assertEquals("$profile Day90 raw phaseIndex = 3（90+ 天）", 3, snap.seasonRaw.phaseIndex)
            // tracker 层：phase 变化需 3 天持久确认（§16 慢变化），Day 95 前必已生效
            val settled = timelineFor(profile).snapshotAt(95).season
            assertEquals("$profile Day95 生效 phaseIndex = 3", 3, settled.phaseIndex)
        }
    }

    @Test
    fun day7HasRealBaselineNotPlaceholder() {
        val snap = timelineFor(QaProfiles.A_STABLE).snapshotAt(7)
        assertNotNull("Day7 应有画像", snap.portrait)
        assertTrue("Day7 基线有效日 ≥ 3", snap.baseline?.validDays ?: 0 >= 3)
        assertTrue("Day7 画像维度非空", snap.portrait!!.dimensions.isNotEmpty())
        assertEquals("Day7 基线状态", "EARLY_BASELINE", snap.portrait!!.status)
    }

    // ===== FINAL ACCEPTANCE — DAY 90/180：长期变化看得见 =====

    @Test
    fun nightOwlShowsLaterDriftByDay180() {
        val t = timelineFor(QaProfiles.B_NIGHT_OWL)
        val early = t.snapshotAt(90).season
        val late = t.snapshotAt(180).season
        // 半年慢漂移：绝对活跃起点后移必须进入 Life Season（ERA 21 §16 修复点）
        assertTrue(
            "B 半年节律后移应可见（day90=${early.rhythmShift}/drift=${"%.2f".format(early.drift)}, day180=${late.rhythmShift}/drift=${"%.2f".format(late.drift)}）",
            late.rhythmShift == "later" || late.drift > 0.05f,
        )
    }

    @Test
    fun projectCrunchIsVisibleAsSeasonShiftThenRecovers() {
        val t = timelineFor(QaProfiles.E_PROJECT_CRUNCH)
        // Day 90：冲刺期（Day 60-95）→ 节律后移必须可见
        val crunch = t.snapshotAt(90).season
        assertTrue("E Day90 冲刺期应后移（实际 ${crunch.rhythmShift}）", crunch.rhythmShift == "later")
        // Day 180：冲刺结束 85 天 → 恢复稳定
        val recovered = t.snapshotAt(180).season
        assertTrue("E Day180 应恢复（实际 ${recovered.rhythmShift}）", recovered.rhythmShift == "stable")
    }

    @Test
    fun travelShowsEarlyShiftDuringTripThenSettles() {
        val t = timelineFor(QaProfiles.D_TRAVEL)
        // Day 28：出差窗口（Day 20-27）→ 更早
        assertEquals("D Day28 出差期应更早", "earlier", t.snapshotAt(28).season.rhythmShift)
        // Day 90：无出差窗口 → 稳定
        assertEquals("D Day90 无出差应稳定", "stable", t.snapshotAt(90).season.rhythmShift)
    }

    @Test
    fun lowDataProfileStaysHonest() {
        val t = timelineFor(QaProfiles.F_LOW_DATA)
        for (day in dayAnchors) {
            val snap = t.snapshotAt(day)
            assertTrue("F Day$day 置信度必须低（实际 ${"%.2f".format(snap.presence.confidence)}）",
                snap.presence.confidence <= 0.5f)
        }
        // 无数据日不伪造画像
        val day2 = t.snapshotAt(2)
        assertTrue("F 低数据日画像可为空（诚实），但不能 crash", day2.portrait == null || day2.portrait!!.status == "PARTIAL_DATA")
    }

    // ===== 全程身份连续（FINAL ACCEPTANCE — DAY 180） =====

    @Test
    fun identityIsContinuousFromDay0ToDay180() {
        for (profile in QaProfiles.ALL) {
            val t = timelineFor(profile)
            val day0 = t.snapshotAt(0).identity
            val day180 = t.snapshotAt(180).identity
            assertTrue(
                "$profile Day0→Day180 身份距离 < 0.05（实际 ${"%.4f".format(identityDistance(day0, day180))}）",
                identityDistance(day0, day180) < 0.05f,
            )
            assertEquals("$profile 颜色族不漂移", day0.colorFamily, day180.colorFamily)
            assertEquals("$profile 纹理族不漂移", day0.textureFamily, day180.textureFamily)
            assertEquals("$profile 核心拓扑不漂移", day0.coreTopology, day180.coreTopology, 1e-6f)
        }
    }

    // ===== 学习期文案锚点（ERA 20 §10） =====

    @Test
    fun learningPhaseCopyAnchors() {
        // SEED：初见（非「数据不足」）
        val seed = timelineFor(QaProfiles.A_STABLE).snapshotAt(0)
        assertEquals(EchoMaturity.SEED, seed.presence.maturity)
        // DISCOVERING/KNOWN 阶段不弹庆祝：maturity 只影响视觉开放度，不产生状态重置
        val known = timelineFor(QaProfiles.A_STABLE).snapshotAt(7)
        assertFalse("KNOWN 阶段不得改变身份种子", known.identity.seed != QaProfiles.A_STABLE.identitySeed)
    }

    // ===== Wallpaper lock-safe（ERA 21 §22） =====

    @Test
    fun lockSafeVisualIsCalmerAndIdentityConsistent() {
        for (profile in QaProfiles.ALL) {
            val snap = timelineFor(profile).snapshotAt(180)
            val app = snap.appVisual
            val lock = snap.lockVisual
            assertTrue("$profile LOCK_SAFE flow ≤ APP flow", lock.flowSpeed <= app.flowSpeed + 1e-6f)
            // lock-safe 无 narrative 字段（视觉参数不含文字）
            assertFalse("$profile 锁屏参数不含叙事", lock.toString().contains("narrative"))
        }
    }

    // ===== 视觉多样性 gate：不同 profile 的 ECHO 视觉可区分 =====

    @Test
    fun differentProfilesHaveDifferentEchos() {
        val frames = QaProfiles.ALL.map { QaTimeline.computeFrame(timelineFor(it).snapshotAt(90)) }
        assertEquals("7 个 profile 主色互异", 7, frames.map { it.ambientField.centerColor }.toSet().size)
    }
}
