package com.yunjue.echo.mind.ui
import com.yunjue.echo.mind.model.echoMaturity

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.yunjue.echo.mind.actions.EchoActionKind
import com.yunjue.echo.mind.intelligence.ConversationPhase
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.MessageDisplay
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_QUESTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOADING_QUIET
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOAD_FAILED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_OFFLINE_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_PARTIAL_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REENABLE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_RETRY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_ACTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_WHY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SENSING_DISABLED
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V3 §37–§43/§84 — ambient ECHO Scene smoke test（Scene 不是 Feed）。
 *
 * 首 viewport 契约：organism + observation + Why + Ask；0 大卡片 / 0 图表 / 0 数字 KPI；
 * portrait feedback / action list / conversation / Journey 重复入口 全部不在首屏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp")
class EchoSceneContentSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun portraitState(
        status: PortraitStatus = PortraitStatus.READY,
        baselineDays: Int = 7,
        summary: String = "今天和平时很接近。",
        offline: Boolean = false,
        date: String = "2026-08-15",
    ) = PortraitUiState(
        status = status,
        offline = offline,
        portrait = DailyPortraitDto(
            date = date,
            status = "READY",
            confidence = "HIGH",
            baselineDays = baselineDays,
            headline = listOf("接近"),
            summary = summary,
            dimensions = emptyMap(),
        ),
    )

    private fun uiState(
        headline: String = "今天和平时很接近。",
        baselineDays: Int = 7,
        intelligenceAvailable: Boolean = true,
    ) = EchoSceneUiState(
        presence = null,
        sensing = SensingRuntimeStatus.ACTIVE,
        headline = headline,
        headlineLevel = com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
        aiLayer = null,
        headlineSources = emptyList(),
        facts = emptyList(),
        portraitStatus = PortraitStatus.READY,
        baselineDays = baselineDays,
        maturity = echoMaturity(baselineDays),
        intelligenceAvailable = intelligenceAvailable,
        suggestedAction = false,
    )

    private class Recorder {
        val journey = mutableListOf<Boolean>()
        val me = mutableListOf<Boolean>()
        val reEnabled = mutableListOf<Boolean>()
        val retried = mutableListOf<Boolean>()
        val likes = mutableListOf<String>()
        val wallpaperDismissed = mutableListOf<Boolean>()
        val wallpaperSelected = mutableListOf<Boolean>()

        fun navigation() = EchoSceneNavigation(
            onGoToJourney = { journey += true },
            onGoToMe = { me += true },
            onEmergency = { },
        )

        fun core() = EchoSceneCoreActions(
            onConsumeUnlocked = { true },
            onReEnableSensing = { reEnabled += true },
            onRetryPortrait = { retried += true },
            onStartAction = { },
            onStopAction = { },
            onAsk = { },
            onConversationFeedback = { _, _, _, _ -> },
        )

        fun feedback(feedbackFor: (String) -> Boolean? = { null }) = EchoSceneFeedbackActions(
            onDismissAiPrompt = {},
            onDismissWallpaperPrompt = { wallpaperDismissed += true },
            onSelectWallpaper = { wallpaperSelected += true },
            onPortraitLike = { likes += it },
            onPortraitNotLike = { },
            onPortraitCorrection = { _, _, _ -> },
            onRebuildTodayPortrait = { },
            portraitFeedbackFor = feedbackFor,
        )
    }

    private fun setContent(
        portrait: PortraitUiState = portraitState(),
        uiState: EchoSceneUiState = uiState(),
        message: MessageDisplay? = null,
        aiPromptDismissed: Boolean = true,
        wallpaperPromptDismissed: Boolean = true,
        runningAction: EchoActionKind? = null,
        recorder: Recorder = Recorder(),
        visualSurface: @Composable () -> Unit = { Text("slot-visual") },
        actionLayer: @Composable () -> Unit = { Text("slot-actions") },
        actionOverlay: @Composable () -> Unit = { Text("slot-overlay") },
    ) {
        compose.setContent {
            MaterialTheme {
                EchoSceneContent(
                    state = EchoSceneContentState(
                        uiState = uiState,
                        portrait = portrait,
                        turns = emptyList(),
                        phase = ConversationPhase.IDLE,
                        runningAction = runningAction,
                        message = message,
                        aiPromptDismissed = aiPromptDismissed,
                        wallpaperPromptDismissed = wallpaperPromptDismissed,
                    ),
                    navigation = recorder.navigation(),
                    coreActions = recorder.core(),
                    feedbackActions = recorder.feedback(),
                    visualSurface = visualSurface,
                    actionLayer = actionLayer,
                    actionOverlay = actionOverlay,
                )
            }
        }
    }

    /** 点击含指定文案的可点节点（TextButton 自身 / quiet surface 的后代文本两种形态）。
     *  Robolectric 假字体量度下坐标注入不可靠 → 用语义 OnClick 动作（逻辑级；
     *  真实坐标触摸由真机/设备测试覆盖）。 */
    private fun clickText(text: String) {
        compose.onNode(
            hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text))),
        ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    // ===== 首 viewport 契约（§84） =====

    @Test
    fun firstViewportHasOrganismNarrativeWhyAsk() {
        setContent()
        compose.onNodeWithText("slot-visual").assertExists()
        compose.onNodeWithText("今天 · ", substring = true).assertExists()
        compose.onNodeWithText("今天和平时很接近。").assertExists()
        compose.onNodeWithText(PORTRAIT_COPY_SECTION_WHY).assertExists()
        compose.onNodeWithText("问 ECHO").assertExists()
        // testTags（§80：只给 major surfaces）
        compose.onNodeWithTag("echo_scene_visual").assertExists()
        compose.onNodeWithTag("echo_scene_narrative").assertExists()
        compose.onNodeWithTag("echo_scene_why").assertExists()
        compose.onNodeWithTag("echo_scene_ask").assertExists()
    }

    @Test
    fun firstViewportHasNoFeedItems() {
        setContent()
        // portrait feedback / action list / conversation history / Journey 重复入口 不在首屏
        compose.onNodeWithText(PORTRAIT_COPY_FEEDBACK_QUESTION).assertDoesNotExist()
        compose.onNodeWithText("slot-actions").assertDoesNotExist()
        compose.onNodeWithText("问 ECHO 关于你的事").assertDoesNotExist()
        compose.onNodeWithText("Journey · 我的时间 →").assertDoesNotExist()
    }

    // ===== 状态机 =====

    @Test
    fun loadingShowsQuietEchoCopyWithoutSpinner() {
        setContent(portrait = portraitState(status = PortraitStatus.LOADING))
        compose.onNodeWithText(PORTRAIT_COPY_LOADING_QUIET).assertExists()
        compose.onNodeWithText(PORTRAIT_COPY_LOAD_FAILED).assertDoesNotExist()
        compose.onNodeWithText("slot-visual").assertExists() // quiet ECHO 继续显示
    }

    @Test
    fun warmingUpShowsHeadlineAndFactsNotDashboard() {
        setContent(
            portrait = portraitState(
                status = PortraitStatus.WARMING_UP,
                baselineDays = 3,
                summary = "ECHO 正在慢慢了解你的日常节奏。\n\n今天累计屏幕互动 126 分钟。",
            ),
            uiState = uiState(headline = "我开始看到一些属于你的节奏。", baselineDays = 3),
        )
        compose.onNodeWithText("我开始看到一些属于你的节奏。").assertExists()
        compose.onNodeWithText("今天累计屏幕互动 126 分钟。").assertExists()
    }

    @Test
    fun partialShowsQuietBanner() {
        setContent(portrait = portraitState(status = PortraitStatus.PARTIAL_DATA))
        compose.onNodeWithText(PORTRAIT_COPY_PARTIAL_BANNER).assertExists()
    }

    @Test
    fun offlineCachedShowsBanner() {
        setContent(portrait = portraitState(status = PortraitStatus.OFFLINE_CACHED, offline = true))
        compose.onNodeWithText(PORTRAIT_COPY_OFFLINE_BANNER).assertExists()
    }

    @Test
    fun sensingDisabledKeepsIdentityAndOffersReEnable() {
        val recorder = Recorder()
        setContent(
            portrait = portraitState(status = PortraitStatus.SENSING_DISABLED),
            recorder = recorder,
        )
        compose.onNodeWithText("slot-visual").assertExists() // identity 保留
        compose.onNodeWithText(PORTRAIT_COPY_SENSING_DISABLED).assertExists()
        clickText(PORTRAIT_COPY_REENABLE)
        assertTrue(recorder.reEnabled.isNotEmpty())
    }

    @Test
    fun errorKeepsIdentityWithSecondaryRetry() {
        val recorder = Recorder()
        setContent(portrait = portraitState(status = PortraitStatus.ERROR), recorder = recorder)
        compose.onNodeWithText("slot-visual").assertExists()
        compose.onNodeWithText(PORTRAIT_COPY_LOAD_FAILED).assertExists()
        clickText(PORTRAIT_COPY_RETRY)
        assertTrue(recorder.retried.isNotEmpty())
    }

    // ===== Ambient transient prompt =====

    @Test
    fun wallpaperPromptPillShowsAndDismisses() {
        val recorder = Recorder()
        setContent(wallpaperPromptDismissed = false, recorder = recorder)
        compose.onNodeWithText("让 ECHO 留在桌面：设置动态壁纸 →").assertExists()
        compose.onNode(hasClickAction() and hasText("以后再说")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(recorder.wallpaperDismissed.isNotEmpty())
    }

    @Test
    fun wallpaperPromptPillSelectFires() {
        val recorder = Recorder()
        setContent(wallpaperPromptDismissed = false, recorder = recorder)
        clickText("让 ECHO 留在桌面：设置动态壁纸 →")
        assertTrue(recorder.wallpaperSelected.isNotEmpty())
    }

    @Test
    fun aiPromptPillNavigatesToMe() {
        val recorder = Recorder()
        setContent(
            uiState = uiState(intelligenceAvailable = false),
            aiPromptDismissed = false,
            recorder = recorder,
        )
        clickText("连接一个 AI，让 ECHO 更深入地理解你 →")
        assertTrue(recorder.me.isNotEmpty())
    }

    // ===== Progressive surfaces（sheet） =====

    @Test
    fun askSheetRetainsLiveEchoAndConversation() {
        setContent()
        clickText("问 ECHO")
        // 顶部 mini live ECHO + conversation（§46）
        compose.onAllNodesWithText("slot-visual").assertCountEquals(2)
        compose.onNodeWithText("问 ECHO 关于你的事").assertExists()
    }

    @Test
    fun whySheetShowsEvidenceThenFeedbackAfterExpand() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        clickText(PORTRAIT_COPY_SECTION_WHY)
        compose.onNodeWithText("今天的依据").assertExists()
        // 默认无 feedback（展开才出现）
        compose.onNodeWithText(PORTRAIT_COPY_FEEDBACK_QUESTION).assertDoesNotExist()
        clickText("更多依据与反馈")
        compose.onNodeWithText(PORTRAIT_COPY_FEEDBACK_QUESTION).assertExists()
        clickText(PORTRAIT_COPY_FEEDBACK_LIKE)
        assertTrue(recorder.likes.isNotEmpty())
    }

    @Test
    fun journeyLinkLivesInWhySheet() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        clickText(PORTRAIT_COPY_SECTION_WHY)
        clickText("更多依据与反馈")
        clickText("查看更多 → Journey")
        assertTrue(recorder.journey.isNotEmpty())
    }

    @Test
    fun actionSheetHostsAvailableActions() {
        setContent()
        clickText(PORTRAIT_COPY_SECTION_ACTION)
        compose.onNodeWithText("slot-actions").assertExists()
    }

    @Test
    fun runningActionOverlayRendered() {
        setContent(runningAction = EchoActionKind.BREATHING)
        compose.onNodeWithText("slot-overlay").assertExists()
    }

    @Test
    fun overlayHiddenWhenNoRunningAction() {
        setContent()
        compose.onNodeWithText("slot-overlay").assertDoesNotExist()
    }

    // ===== 视觉占比（§38/§79） =====

    @Test
    fun visualRegionRatioWithinSpec() {
        // §38：fontScale 1.0 → 61.5%（允许 56–64%，下限 52%）
        assertTrue(visualFractionFor(1.0f) in 0.56f..0.64f)
        // §79：fontScale ≥1.3 → ~52%
        assertEquals(0.52f, visualFractionFor(1.3f), 1e-4f)
        assertEquals(0.52f, visualFractionFor(1.5f), 1e-4f)
    }
}
