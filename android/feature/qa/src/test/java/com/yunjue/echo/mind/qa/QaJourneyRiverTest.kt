package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.journey.RiverSegmentKind
import com.yunjue.echo.mind.journey.buildJourneyDays
import com.yunjue.echo.mind.journey.buildVisualMemoryRiver
import com.yunjue.echo.mind.journey.buildCanonicalDay
import com.yunjue.echo.mind.journey.JourneyCanonicalCodec
import com.yunjue.echo.mind.journey.reconstructJourneyFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 24（Batch 4）— Visual Memory River 质量 + 历史重建质量：
 * - 河段呈现「时间的形状」：冲刺期产生 SPECIAL/TRANSITION 段，稳定期 STABLE；
 * - 历史重建与当日渲染同帧（canonical 编解码往返确定性，fixture 驱动）。
 */
class QaJourneyRiverTest {

    @Test
    fun crunchTimelineProducesSpecialOrTransitionSegments() {
        val days = buildJourneyDays(QaTimeline(QaProfiles.E_PROJECT_CRUNCH).allPortraitsUpTo(120))
        val river = buildVisualMemoryRiver(days, emptyMap())
        println("E river: " + river.map { "${it.kind}(${it.startDate}..${it.endDate})" })
        assertTrue("E 冲刺期必须有非平稳河段", river.any { it.kind != RiverSegmentKind.STABLE })
        assertTrue("河段标签为中性词表", river.all { it.label.isNotBlank() })
        // 河段数量远小于天数（时间形状不是 120 个点）
        assertTrue("河段数 < 天数（实际 ${river.size}/120）", river.size < days.size / 2)
    }

    @Test
    fun stableTimelineIsMostlyStableSegments() {
        val days = buildJourneyDays(QaTimeline(QaProfiles.A_STABLE).allPortraitsUpTo(180))
        val river = buildVisualMemoryRiver(days, emptyMap())
        val stable = river.filter { it.kind == RiverSegmentKind.STABLE }
        assertTrue("A 稳定期以 STABLE 为主（实际 ${stable.size}/${river.size} 段）",
            stable.size * 2 >= river.size)
    }

    @Test
    fun canonicalRoundtripReconstructsSameFrameAcrossProfiles() {
        for (profile in QaProfiles.ALL) {
            val timeline = QaTimeline(profile)
            val snap = timeline.snapshotAt(90)
            val day = buildCanonicalDay(
                date = snap.date.toString(),
                state = snap.presence,
                keyEvidenceIds = listOf("evt_a", "evt_b"),
                createdAtEpochMs = 1L,
            )
            val decoded = JourneyCanonicalCodec.decode(JourneyCanonicalCodec.encode(day))
            requireNotNull(decoded)
            val frame = reconstructJourneyFrame(decoded, fallbackPortrait = null, fallbackSeed = 0L, 1080f, 2340f)
            requireNotNull(frame)
            val direct = QaTimeline.computeFrame(snap)
            assertEquals("${profile.id} 历史重建与当日渲染同帧", direct, frame)
        }
    }
}
