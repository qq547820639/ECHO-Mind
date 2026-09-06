package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设计稿 19 — ECHO 成长页渲染测试（Robolectric + Compose，纯状态注入）：
 * - 弃权路径：null 值四卡渲染 "—"、无真实节点时时间线整体隐藏；
 * - 真实数据路径：数值/时间线/设计稿文案渲染；
 * - 主 CTA 回调触发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class EchoGrowthPageTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        remembered: Int? = null,
        understandingDays: Int? = null,
        timeline: List<GrowthTimelinePoint> = emptyList(),
        onContinueClick: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                EchoGrowthPage(
                    rememberedFragmentsCount = remembered,
                    understandingDays = understandingDays,
                    behaviorTrendText = null,
                    behaviorTrendLabel = null,
                    accompanimentHours = null,
                    timelinePoints = timeline,
                    onContinueClick = onContinueClick,
                )
            }
        }
    }

    @Test
    fun abstainRendersFourDashesAndHidesTimeline() {
        setContent()
        compose.onAllNodesWithText("—").assertCountEquals(4)
        compose.onNodeWithTag("growth_timeline").assertDoesNotExist()
        compose.onNodeWithTag("growth_page_title").assertExists()
        compose.onNodeWithText("继续探索 ECHO").assertExists()
    }

    @Test
    fun rendersRealValuesAndDesignCopy() {
        setContent(
            remembered = 3,
            understandingDays = 12,
            timeline = listOf(
                GrowthTimelinePoint(label = "初次相遇", date = "8月1日"),
                GrowthTimelinePoint(label = "越来越懂你", date = "9月6日", isToday = true),
            ),
        )
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithTag("growth_timeline").assertExists()
        compose.onNodeWithText("初次相遇").assertExists()
        compose.onNodeWithText("越来越懂你").assertExists()
        // 设计稿 19 文案
        compose.onNodeWithText("它正越来越懂你的节律").assertExists()
        compose.onNodeWithText("持续进化中").assertExists()
        compose.onNodeWithText("你的每一次表达，都让 ECHO 变得更完整").assertExists()
    }

    @Test
    fun singleTimelinePointStaysHidden() {
        setContent(timeline = listOf(GrowthTimelinePoint(label = "越来越懂你", date = "9月6日", isToday = true)))
        compose.onNodeWithTag("growth_timeline").assertDoesNotExist()
    }

    @Test
    fun continueButtonFiresCallback() {
        var clicked = false
        setContent(onContinueClick = { clicked = true })
        // CTA 位于长页底部（中央生命体+卡片之后），需滚动至可见再点击
        compose.onNodeWithTag("continue_explore_button").performScrollTo().performClick()
        assertTrue(clicked)
    }
}
