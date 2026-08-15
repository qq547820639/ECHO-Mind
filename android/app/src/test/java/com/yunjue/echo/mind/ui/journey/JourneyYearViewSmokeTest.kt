package com.yunjue.echo.mind.ui.journey

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yunjue.echo.mind.journey.JourneyContextPeriod
import com.yunjue.echo.mind.journey.JourneyIdentityPoint
import com.yunjue.echo.mind.journey.JourneyMajorShift
import com.yunjue.echo.mind.journey.JourneySeasonSummary
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.JourneyYearView
import com.yunjue.echo.mind.journey.SEASON_SPRING
import com.yunjue.echo.mind.presence.EchoIdentityGenome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 70 — YearViewSection 渲染 smoke 锚定（ADR-063 第 2 轮）：
 * 季节行（标签 + 日期范围 + 天数）/ 转变点中性解释行（§87，非技术维度名）/
 * 上下文时期 / 身份演化逐点行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h1200dp")
class JourneyYearViewSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun yearViewState() = JourneyUiState(
        yearView = JourneyYearView(
            seasons = listOf(
                JourneySeasonSummary(
                    season = SEASON_SPRING,
                    startDate = "2026-03-01",
                    endDate = "2026-05-31",
                    dayCount = 92,
                    visualParams = null,
                    majorShifts = emptyList(),
                    contextPeriods = listOf(
                        JourneyContextPeriod(startDate = "2026-05-01", endDate = "2026-05-10", kind = "exam"),
                    ),
                    identitySnapshot = null,
                ),
            ),
            majorShifts = listOf(
                JourneyMajorShift(
                    date = "2026-02-01",
                    beforeDate = "2026-01-31",
                    before = null,
                    after = null,
                    distance = 0.9f,
                    changedAspects = emptyList(),
                ),
            ),
            contextPeriods = emptyList(),
            identityEvolution = listOf(
                JourneyIdentityPoint(date = "2026-03-15", identity = EchoIdentityGenome(seed = 42L)),
            ),
        ),
    )

    @Test
    fun yearViewSectionRendersSeasonShiftPeriodAndIdentity() {
        compose.setContent {
            MaterialTheme { YearViewSection(state = yearViewState(), seed = 42L) }
        }
        // 季节行：标签 + 日期范围 + 天数
        compose.onNodeWithText("春 · 2026-03-01 … 2026-05-31（92 天）").assertExists()
        // 上下文时期
        compose.onNodeWithText("特殊阶段：exam").assertExists()
        // 转变点：§87 中性解释兜底行（非技术维度名）
        compose.onNodeWithText("2026-02-01：整体视觉风格转变").assertExists()
        // 身份演化：标题 + 逐点日期行
        compose.onNodeWithText("身份演化（每月最近快照）").assertExists()
        compose.onNodeWithText("2026-03-15").assertExists()
    }
}
