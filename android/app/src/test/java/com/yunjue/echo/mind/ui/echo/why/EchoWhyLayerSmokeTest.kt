package com.yunjue.echo.mind.ui.echo.why

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.model.PortraitFactDto
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.sensing.SensingRuntimeStatus
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 35 — EchoWhyLayer smoke test（ECHO 世界 Progressive Explanation 三层）：
 * Layer 1 一句话 / AI 依据行 / AI 不可用提示 / Layer 2 展开·空事实 / Layer 3 Journey 入口回调。
 * 纯状态渲染（EchoSceneUiState 注入，无 ViewModel/Repository）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoWhyLayerSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(
        headline: String = "ECHO 还在了解今天。",
        level: NarrativeFallbackLevel? = NarrativeFallbackLevel.OBSERVATION_FACTS,
        sources: List<DataSourceCategory> = emptyList(),
        facts: List<PortraitFactDto> = emptyList(),
        intelligenceAvailable: Boolean = false,
    ) = EchoSceneUiState(
        presence = null,
        sensing = SensingRuntimeStatus.ACTIVE,
        headline = headline,
        headlineLevel = level,
        aiLayer = null,
        headlineSources = sources,
        facts = facts,
        portraitStatus = PortraitStatus.READY,
        baselineDays = 7,
        maturity = EchoMaturity.KNOWN,
        intelligenceAvailable = intelligenceAvailable,
        suggestedAction = false,
    )

    private fun setContent(uiState: EchoSceneUiState, onGoToJourney: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                EchoWhyLayer(uiState = uiState, onGoToJourney = onGoToJourney)
            }
        }
    }

    @Test
    fun layerOneHeadlineRendered() {
        setContent(state(headline = "你最近睡得更早了。"))
        compose.onNodeWithText("你最近睡得更早了。").assertExists()
    }

    @Test
    fun aiNarrativeShowsSourceLine() {
        setContent(
            state(
                headline = "这段时期你的作息更规律。",
                level = NarrativeFallbackLevel.AI_NARRATIVE,
                sources = listOf(DataSourceCategory.TODAY_AGGREGATE, DataSourceCategory.BASELINE),
            )
        )
        compose.onNodeWithText(
            "依据：今天的活动节律、个人基线（点「为什么？」看事实）"
        ).assertExists()
    }

    @Test
    fun deterministicNarrativeHidesSourceLine() {
        setContent(
            state(
                headline = "接近",
                level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                sources = listOf(DataSourceCategory.BASELINE),
            )
        )
        compose.onNodeWithText("依据：", substring = true).assertDoesNotExist()
    }

    @Test
    fun aiUnavailableShowsConnectHint() {
        setContent(state(intelligenceAvailable = false))
        compose.onNodeWithText("连接 AI 后可获得更深入的解释。").assertExists()
    }

    @Test
    fun layerTwoCollapsedByDefault() {
        setContent(
            state(
                facts = listOf(
                    PortraitFactDto(label = "作息", todayText = "23:10 入睡", baselineText = "00:40 入睡", deltaText = "更早")
                )
            )
        )
        compose.onNodeWithText("为什么？").assertExists()
        compose.onNodeWithText("展开").assertExists()
        compose.onNodeWithText("作息").assertDoesNotExist()
    }

    @Test
    fun layerTwoExpandShowsFactCards() {
        setContent(
            state(
                facts = listOf(
                    PortraitFactDto(label = "作息", todayText = "23:10 入睡", baselineText = "00:40 入睡", deltaText = "更早")
                )
            )
        )
        compose.onNode(hasClickAction() and hasText("展开")).performClick()
        compose.onNodeWithText("作息").assertExists()
        compose.onNodeWithText("今天：23:10 入睡").assertExists()
        compose.onNodeWithText("平常：00:40 入睡").assertExists()
        compose.onNodeWithText("变化：更早").assertExists()
        compose.onNode(hasClickAction() and hasText("收起")).performClick()
        compose.onNodeWithText("作息").assertDoesNotExist()
    }

    @Test
    fun layerTwoEmptyFactsShowsPlaceholder() {
        setContent(state(facts = emptyList()))
        compose.onNode(hasClickAction() and hasText("展开")).performClick()
        compose.onNodeWithText("暂无更多细节。").assertExists()
    }

    @Test
    fun layerThreeJourneyButtonInvokesCallback() {
        var clicked = false
        setContent(state(), onGoToJourney = { clicked = true })
        compose.onNode(hasClickAction() and hasText("查看更多 → Journey")).performClick()
        assertTrue(clicked)
    }
}
