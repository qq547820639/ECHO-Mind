package com.yunjue.echo.mind.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yunjue.echo.mind.actions.EchoActionKind
import com.yunjue.echo.mind.intelligence.ConversationPhase
import com.yunjue.echo.mind.memory.CORRECTION_REASONS
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.MessageDisplay
import com.yunjue.echo.mind.model.PORTRAIT_COPY_BASELINE_UNLOCKED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_NOT_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_SAVED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOAD_FAILED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_OFFLINE_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_PARTIAL_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REENABLE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_RETRY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SENSING_DISABLED
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_TITLE
import com.yunjue.echo.mind.sensing.SensingRuntimeStatus
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 36 — EchoSceneContent 纯状态内容 smoke test（九态矩阵 + 消息卡 + 槽位 + 行动覆盖层 + 反馈流）。
 * LazyColumn 使用超高窗口 qualifiers 让全部 item 组合（免滚动注入）；无 AppContainer/ViewModel。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp")
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
        headline: String = "接近",
        baselineDays: Int = 7,
        intelligenceAvailable: Boolean = true,
    ) = EchoSceneUiState(
        presence = null,
        sensing = SensingRuntimeStatus.ACTIVE,
        headline = headline,
        headlineLevel = com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
        headlineSources = emptyList(),
        facts = emptyList(),
        portraitStatus = PortraitStatus.READY,
        baselineDays = baselineDays,
        maturity = EchoMaturity.KNOWN,
        intelligenceAvailable = intelligenceAvailable,
        suggestedAction = false,
    )

    private class Recorder {
        val journey = mutableListOf<Boolean>()
        val me = mutableListOf<Boolean>()
        val emergency = mutableListOf<Boolean>()
        val reEnabled = mutableListOf<Boolean>()
        val retried = mutableListOf<Boolean>()
        val started = mutableListOf<EchoActionKind>()
        val stopped = mutableListOf<Boolean>()
        val asked = mutableListOf<String>()
        val likes = mutableListOf<String>()
        val notLikes = mutableListOf<String>()
        val corrections = mutableListOf<Triple<String, String, String?>>()
        val feedbackCalls = mutableListOf<String>()

        fun navigation() = EchoSceneNavigation(
            onGoToJourney = { journey += true },
            onGoToMe = { me += true },
            onEmergency = { emergency += true },
        )

        fun core() = EchoSceneCoreActions(
            onConsumeUnlocked = { true },
            onReEnableSensing = { reEnabled += true },
            onRetryPortrait = { retried += true },
            onStartAction = { started += it },
            onStopAction = { stopped += true },
            onAsk = { asked += it },
            onConversationFeedback = { _, _, _, _ -> },
        )

        fun feedback(feedbackFor: (String) -> Boolean? = { null }) = EchoSceneFeedbackActions(
            onDismissAiPrompt = {},
            onPortraitLike = { likes += it },
            onPortraitNotLike = { notLikes += it },
            onPortraitCorrection = { d, r, s -> corrections += Triple(d, r, s) },
            onRebuildTodayPortrait = {},
            portraitFeedbackFor = { feedbackCalls += it; feedbackFor(it) },
        )
    }

    private fun setContent(
        portrait: PortraitUiState = portraitState(),
        uiState: EchoSceneUiState = uiState(),
        message: MessageDisplay? = null,
        aiPromptDismissed: Boolean = true,
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

    @Test
    fun slotsAndTitleComposed() {
        setContent()
        compose.onNodeWithText("slot-visual").assertExists()
        compose.onNodeWithText("slot-actions").assertExists()
        compose.onNodeWithText("今天 · ", substring = true).assertExists()
    }

    @Test
    fun loadingShowsSpinnerWithoutPortraitBranch() {
        setContent(portrait = portraitState(status = PortraitStatus.LOADING))
        compose.onNodeWithText("slot-visual").assertExists()
        compose.onNodeWithText(PORTRAIT_COPY_LOAD_FAILED).assertDoesNotExist()
    }

    @Test
    fun warmingUpSeedShowsSeedBlock() {
        setContent(
            portrait = portraitState(status = PortraitStatus.WARMING_UP, baselineDays = 0),
            uiState = uiState(baselineDays = 0),
        )
        compose.onNodeWithText(PRESENCE_COPY_SEED_TITLE).assertExists()
    }

    @Test
    fun warmingUpWithDaysShowsBaselineProgress() {
        setContent(
            portrait = portraitState(status = PortraitStatus.WARMING_UP, baselineDays = 3),
            uiState = uiState(baselineDays = 3),
        )
        compose.onNodeWithText("已积累 3/7 天，基线即将成型").assertExists()
    }

    @Test
    fun earlyBaselineShowsSummaryOnly() {
        setContent(
            portrait = portraitState(status = PortraitStatus.EARLY_BASELINE, summary = "最近的作息更稳定。"),
            uiState = uiState(baselineDays = 5),
        )
        compose.onNodeWithText("最近的作息更稳定。").assertExists()
        compose.onNodeWithText("已积累 5/7 天，基线即将成型").assertExists()
    }

    @Test
    fun readyShowsWhyHeadlineAndUnlockBanner() {
        setContent(uiState = uiState(headline = "你最近睡得更早了。"))
        compose.onNodeWithText("你最近睡得更早了。").assertExists()
        compose.onNodeWithText(PORTRAIT_COPY_BASELINE_UNLOCKED).assertExists()
    }

    @Test
    fun partialShowsBannerAndWhy() {
        setContent(portrait = portraitState(status = PortraitStatus.PARTIAL_DATA))
        compose.onNodeWithText(PORTRAIT_COPY_PARTIAL_BANNER).assertExists()
        compose.onNodeWithText("接近").assertExists()
    }

    @Test
    fun offlineCachedShowsBanner() {
        setContent(portrait = portraitState(status = PortraitStatus.OFFLINE_CACHED, offline = true))
        compose.onNodeWithText(PORTRAIT_COPY_OFFLINE_BANNER).assertExists()
    }

    @Test
    fun sensingDisabledEmitsReEnable() {
        val recorder = Recorder()
        setContent(portrait = portraitState(status = PortraitStatus.SENSING_DISABLED), recorder = recorder)
        compose.onNodeWithText(PORTRAIT_COPY_SENSING_DISABLED).assertExists()
        compose.onNode(hasClickAction() and hasText(PORTRAIT_COPY_REENABLE)).performClick()
        assertEquals(listOf(true), recorder.reEnabled)
    }

    @Test
    fun errorEmitsRetry() {
        val recorder = Recorder()
        setContent(portrait = portraitState(status = PortraitStatus.ERROR), recorder = recorder)
        compose.onNodeWithText(PORTRAIT_COPY_LOAD_FAILED).assertExists()
        compose.onNode(hasClickAction() and hasText(PORTRAIT_COPY_RETRY)).performClick()
        assertEquals(listOf(true), recorder.retried)
    }

    @Test
    fun messageCardRendered() {
        setContent(
            message = MessageDisplay(id = "m1", title = "本周小结", body = "你的作息更规律了。"),
        )
        compose.onNodeWithText("本周小结").assertExists()
        compose.onNodeWithText("你的作息更规律了。").assertExists()
    }

    @Test
    fun journeyAndEmergencyCallbacksFire() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        compose.onNode(hasClickAction() and hasText("Journey · 我的时间 →")).performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasText("紧急支持")).performScrollTo().performClick()
        assertEquals(listOf(true), recorder.journey)
        assertEquals(listOf(true), recorder.emergency)
    }

    @Test
    fun askToggleExpandsConversationLayer() {
        setContent()
        compose.onNode(hasClickAction() and hasText("问 ECHO")).performScrollTo().performClick()
        compose.onNodeWithText("问 ECHO 关于你的事").performScrollTo().assertExists()
        compose.onNode(hasClickAction() and hasText("收起对话")).performScrollTo().performClick()
        compose.onNodeWithText("问 ECHO 关于你的事").assertDoesNotExist()
    }

    @Test
    fun runningActionOverlaySlotRenderedWhenRunning() {
        setContent(runningAction = EchoActionKind.BREATHING)
        compose.onNodeWithText("slot-overlay").assertExists()
    }

    @Test
    fun overlaySlotHiddenWhenNoRunningAction() {
        setContent(runningAction = null)
        compose.onNodeWithText("slot-overlay").assertDoesNotExist()
    }

    @Test
    fun feedbackLikeFlowEmitsAndShowsSaved() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        compose.onNode(hasClickAction() and hasText(PORTRAIT_COPY_FEEDBACK_LIKE)).performScrollTo().performClick()
        assertEquals(listOf("2026-08-15"), recorder.likes)
        compose.onNodeWithText(PORTRAIT_COPY_FEEDBACK_SAVED).assertExists()
    }

    @Test
    fun feedbackNotLikeFlowRequiresReasonAndEmitsCorrection() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        compose.onNode(hasClickAction() and hasText(PORTRAIT_COPY_FEEDBACK_NOT_LIKE)).performScrollTo().performClick()
        assertEquals(listOf("2026-08-15"), recorder.notLikes)
        compose.onNodeWithText("今天有什么不一样？").performScrollTo().assertExists()
        assertTrue(recorder.corrections.isEmpty())
        val firstReason = CORRECTION_REASONS.first()
        compose.onNode(hasClickAction() and hasText(firstReason)).performScrollTo().performClick()
        assertEquals(listOf(Triple("2026-08-15", firstReason, "今天和平时很接近。")), recorder.corrections)
    }

    @Test
    fun feedbackPreRecordedShowsSavedDirectly() {
        val recorder = Recorder()
        // 预置反馈：feedbackFor 返回 true → 直接展示已记录，无按钮
        compose.setContent {
            MaterialTheme {
                EchoSceneContent(
                    state = EchoSceneContentState(
                        uiState = uiState(),
                        portrait = portraitState(),
                        turns = emptyList(),
                        phase = ConversationPhase.IDLE,
                        runningAction = null,
                        message = null,
                        aiPromptDismissed = true,
                    ),
                    navigation = recorder.navigation(),
                    coreActions = recorder.core(),
                    feedbackActions = recorder.feedback(feedbackFor = { true }),
                    visualSurface = { Text("slot-visual") },
                    actionLayer = { Text("slot-actions") },
                    actionOverlay = { Text("slot-overlay") },
                )
            }
        }
        compose.onNodeWithText(PORTRAIT_COPY_FEEDBACK_SAVED).performScrollTo().assertExists()
        compose.onNodeWithText(PORTRAIT_COPY_FEEDBACK_LIKE).assertDoesNotExist()
        assertEquals(listOf("2026-08-15"), recorder.feedbackCalls)
    }

    @Test
    fun aiPromptRenderedWhenUnconfiguredAndNotDismissed() {
        setContent(uiState = uiState(intelligenceAvailable = false), aiPromptDismissed = false)
        compose.onNodeWithText("连接一个 AI，让 ECHO 更深入地理解你的变化。").assertExists()
    }

    @Test
    fun actionLayerSlotReceivesStartActionCallback() {
        val recorder = Recorder()
        setContent(
            recorder = recorder,
            actionLayer = {
                androidx.compose.material3.Button(onClick = { recorder.started += EchoActionKind.BREATHING }) {
                    Text("slot-start-breathing")
                }
            },
        )
        compose.onNode(hasClickAction() and hasText("slot-start-breathing")).performScrollTo().performClick()
        assertEquals(listOf(EchoActionKind.BREATHING), recorder.started)
    }
}
