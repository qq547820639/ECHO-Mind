package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.NarrativeEventDisplay
import com.yunjue.echo.mind.model.ProfileDisplay
import com.yunjue.echo.mind.ui.buildTrendValues
import com.yunjue.echo.mind.ui.moodHintToValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12.6 趋势视图数据源切换回归（纯函数，无需 Robolectric）。
 *
 * 验证 TrendScreen 不再依赖主动签到 mood，改用被动感知叙事 mood_hint：
 * - moodHintToValue 映射：平稳偏积极=4 / 平稳=3 / 偏低=2 / 未知=0
 * - buildTrendValues 由 NarrativeDisplay 列表驱动（非 CheckinEntity）
 * - 空数据返回空序列（趋势图不渲染）
 * - ProfileDisplay / NarrativeDisplay 承载 T12.4 约定的画像与叙事字段
 */
class TrendDataSourceTest {

    @Test
    fun moodHintMapsToExpectedNumericValues() {
        assertEquals(4, moodHintToValue("平稳偏积极"))
        assertEquals(3, moodHintToValue("平稳"))
        assertEquals(2, moodHintToValue("偏低"))
        // 未知 / 空串 / 其他均归一为 0（无数据）
        assertEquals(0, moodHintToValue("未知"))
        assertEquals(0, moodHintToValue(""))
    }

    @Test
    fun buildTrendValuesDerivesFromNarrativesNotCheckins() {
        // 数据源为 NarrativeDisplay（被动感知每日叙事），不再是主动签到 mood
        val narratives = listOf(
            NarrativeDisplay("2026-07-25", "偏低", emptyList()),
            NarrativeDisplay("2026-07-26", "平稳", emptyList()),
            NarrativeDisplay("2026-07-27", "平稳偏积极", emptyList())
        )
        val values = buildTrendValues(narratives)
        assertEquals(listOf(2f, 3f, 4f), values)
    }

    @Test
    fun buildTrendValuesEmptyWhenNoNarratives() {
        // null（加载中）/ 空列表（无叙事）均返回空序列，趋势图不渲染
        assertEquals(emptyList<Float>(), buildTrendValues(null))
        assertEquals(emptyList<Float>(), buildTrendValues(emptyList()))
    }

    @Test
    fun profileDisplayExposesExpectedTraits() {
        // ProfileDisplay 承载 observation_days / narrative_days_last_7 / recent_mood_hint
        val profile = ProfileDisplay(
            observationDays = 5,
            narrativeDaysLast7 = 3,
            recentMoodHint = "平稳",
            version = 2
        )
        assertEquals(5, profile.observationDays)
        assertEquals(3, profile.narrativeDaysLast7)
        assertEquals("平稳", profile.recentMoodHint)
    }

    @Test
    fun narrativeDisplayCarriesDateAndMoodHint() {
        val n = NarrativeDisplay(
            date = "2026-07-27",
            moodHint = "平稳偏积极",
            events = listOf(NarrativeEventDisplay("screen", "屏幕使用平稳", "平稳"))
        )
        assertEquals("2026-07-27", n.date)
        assertEquals("平稳偏积极", n.moodHint)
        assertTrue(n.events.size == 1)
        assertTrue(n.events[0].source == "screen")
    }
}
