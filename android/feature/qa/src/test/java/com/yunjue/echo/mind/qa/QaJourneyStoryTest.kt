package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.journey.JourneyLandmarkKind
import com.yunjue.echo.mind.journey.buildContextPeriods
import com.yunjue.echo.mind.journey.buildJourneyDaysWithOrganism
import com.yunjue.echo.mind.journey.buildLandmarks
import com.yunjue.echo.mind.journey.buildPeriodStory
import com.yunjue.echo.mind.journey.detectSignificantChanges
import com.yunjue.echo.mind.journey.storyRestraintCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 24（Batch 4）— Journey 情感价值 eval（fixture 驱动）：
 * §39 期间故事 ≤3 个真变化 / §40 显著变化 = 视觉距离+持续+置信 /
 * §41 时间地标 / §42 年故事 / §43 情感克制。
 */
class QaJourneyStoryTest {

    private fun journeyDays(profile: QaProfileSpec, dayIndex: Int) =
        buildJourneyDaysWithOrganism(QaTimeline(profile).portraitsUpTo(dayIndex), identitySeed = 0L)

    /** profile 的上下文时期（fixture 特殊窗口 → date→kind 映射）。 */
    private fun contextMap(profile: QaProfileSpec): Map<String, String> {
        val timeline = QaTimeline(profile)
        return profile.specialWindows.flatMap { w ->
            (w.fromDay..w.toDay).map { d -> timeline.dateOf(d).toString() to w.label }
        }.toMap()
    }

    @Test
    fun crunchPeriodStoryHighlightsOneMajorChangeNotDiary() {
        val days = journeyDays(QaProfiles.E_PROJECT_CRUNCH, 95)
        val periods = buildContextPeriods(contextMap(QaProfiles.E_PROJECT_CRUNCH))
        val story = buildPeriodStory(days, periods)
        println("E day95 story: $story")
        assertTrue("冲刺期故事必须出现明显变化（实际：$story）", story.contains("明显变化"))
        val changes = detectSignificantChanges(days, periods)
        assertTrue("显著变化数量 1..3（实际 ${changes.size}）", changes.size in 1..3)
        // 变化落在冲刺窗口内且标注上下文
        assertTrue("冲刺期变化必须标注上下文", changes.any { it.contextKind == "项目冲刺" })
        assertTrue("情感克制", storyRestraintCheck(story).isEmpty())
    }

    @Test
    fun stableProfileStoryIsQuiet() {
        val story = buildPeriodStory(journeyDays(QaProfiles.A_STABLE, 180))
        println("A day180 story: $story")
        assertTrue("稳定用户的故事应平稳（实际：$story）", story.contains("平稳"))
        assertTrue("稳定用户不得有显著变化", detectSignificantChanges(journeyDays(QaProfiles.A_STABLE, 180)).isEmpty())
    }

    @Test
    fun travelWindowsBecomeContextPeriodsNotRhythmAnomalies() {
        val profile = QaProfiles.D_TRAVEL
        val periods = buildContextPeriods(contextMap(profile))
        assertTrue("出差窗口形成上下文时期", periods.any { it.kind == "出差" })
        val days = journeyDays(profile, 180)
        val changes = detectSignificantChanges(days, periods)
        // 出差期内的候选变化必须全部标注上下文（不是节奏异常）
        assertTrue("出差期变化全部标注上下文（实际 ${changes.map { it.contextKind }}）",
            changes.filter { c -> periods.any { c.date in it.startDate..it.endDate } }
                .all { it.contextKind != null })
    }

    @Test
    fun landmarksIncludeBaselineMaturityAndContextPeriods() {
        // A：基线成熟地标
        val aLandmarks = buildLandmarks(journeyDays(QaProfiles.A_STABLE, 28))
        assertTrue("A 有基线成熟地标", aLandmarks.any { it.kind == JourneyLandmarkKind.BASELINE_MATURE })
        assertTrue("A 基线成熟地标文本为自然语言", aLandmarks.first { it.kind == JourneyLandmarkKind.BASELINE_MATURE }
            .text.contains("基线"))
        // D：上下文时期地标
        val dLandmarks = buildLandmarks(
            journeyDays(QaProfiles.D_TRAVEL, 180),
            buildContextPeriods(contextMap(QaProfiles.D_TRAVEL)),
        )
        assertTrue("D 有特殊时期地标", dLandmarks.any { it.kind == JourneyLandmarkKind.CONTEXT_PERIOD })
        // E：冲刺 + 明显变化地标
        val eLandmarks = buildLandmarks(
            journeyDays(QaProfiles.E_PROJECT_CRUNCH, 95),
            buildContextPeriods(contextMap(QaProfiles.E_PROJECT_CRUNCH)),
        )
        assertTrue("E 有明显变化地标", eLandmarks.any { it.kind == JourneyLandmarkKind.MAJOR_SHIFT })
    }

    @Test
    fun everyProfileStoryPassesEmotionalRestraint() {
        for (profile in QaProfiles.ALL) {
            val days = journeyDays(profile, 180)
            val periods = buildContextPeriods(contextMap(profile))
            val story = buildPeriodStory(days, periods)
            val banned = storyRestraintCheck(story)
            assertEquals("${profile.id} 故事必须无情绪判断词（命中：$banned）", emptyList<String>(), banned)
        }
    }

    @Test
    fun significantChangeRequiresConfidenceAndDuration() {
        // §40：窗口内有效画像不足 → 不产生变化判断（置信不足）
        val profile = QaProfiles.F_LOW_DATA
        val days = journeyDays(profile, 180)
        val changes = detectSignificantChanges(days, emptyList())
        for (c in changes) {
            assertTrue("低数据用户的变化置信必须 ≥ 门槛", c.confidence >= 5f / 14f)
        }
        // 每个变化的窗口长度必须 ≥ 14 天（不是单日异常）
        for (c in changes) {
            assertTrue("变化持续时间 ≥ 14 天（实际 ${c.durationDays}）", c.durationDays >= 14)
        }
    }
}
