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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.yunjue.echo.mind.actions.EchoActionKind
import com.yunjue.echo.mind.intelligence.ConversationPhase
import com.yunjue.echo.mind.memory.CORRECTION_REASONS
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
import com.yunjue.echo.mind.model.PortraitFactDto
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * V3 §AB–§AI/§BK — ambient ECHO Scene smoke test（结果导向重写）。
 *
 * 首 viewport 契约：organism + observation + Why + Ask（§84）；无 feed 项；
 * WHY 1-tap 内联证据 + 纠正 ≤2 taps（§AD/§AE）；Ask 1-tap 标准全屏 +
 * 唯一渲染会话（§AF）；home 无 AI provider 提示（§AC）；行动降级入口（§AG）。
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
        facts: List<PortraitFactDto> = listOf(
            PortraitFactDto(
                label = "屏幕使用",
                todayText = "126 分钟",
                baselineText = "98 分钟",
                deltaText = "多 28 分钟",
            )
        ),
    ) = EchoSceneUiState(
        presence = null,
        sensing = SensingRuntimeStatus.ACTIVE,
        headline = headline,
        headlineLevel = com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
        aiLayer = null,
        headlineSources = emptyList(),
        facts = facts,
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
        val notLikes = mutableListOf<String>()
        val corrections = mutableListOf<Triple<String, String, String?>>()
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
            onDismissWallpaperPrompt = { wallpaperDismissed += true },
            onSelectWallpaper = { wallpaperSelected += true },
            onPortraitLike = { likes += it },
            onPortraitNotLike = { notLikes += it },
            onPortraitCorrection = { d, r, s -> corrections += Triple(d, r, s) },
            onRebuildTodayPortrait = { },
            portraitFeedbackFor = feedbackFor,
        )
    }

    private fun setContent(
        portrait: PortraitUiState = portraitState(),
        uiState: EchoSceneUiState = uiState(),
        message: MessageDisplay? = null,
        wallpaperPromptDismissed: Boolean = true,
        runningAction: EchoActionKind? = null,
        recorder: Recorder = Recorder(),
        visualSurface: @Composable () -> Unit = { Text("slot-visual") },
        askMiniEcho: @Composable () -> Unit = { Text("slot-visual") },
        actionLayer: @Composable () -> Unit = { Text("slot-actions") },
        actionOverlay: @Composable () -> Unit = { Text("slot-overlay") },
        content: @Composable () -> Unit = {
            EchoSceneContent(
                state = EchoSceneContentState(
                    uiState = uiState,
                    portrait = portrait,
                    turns = emptyList(),
                    phase = ConversationPhase.IDLE,
                    runningAction = runningAction,
                    message = message,
                    wallpaperPromptDismissed = wallpaperPromptDismissed,
                ),
                navigation = recorder.navigation(),
                coreActions = recorder.core(),
                feedbackActions = recorder.feedback(),
                visualSurface = visualSurface,
                actionLayer = actionLayer,
                actionOverlay = actionOverlay,
                askMiniEcho = askMiniEcho,
            )
        },
    ) {
        compose.setContent {
            MaterialTheme { content() }
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
        // portrait feedback（WHY 内联默认折叠）/ conversation / Journey 重复入口 不在首屏
        compose.onNodeWithText(PORTRAIT_COPY_FEEDBACK_QUESTION).assertDoesNotExist()
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

    // ===== Ambient transient prompt（§AC：wallpaper 唯一 transient） =====

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
    fun homeHasNoAiProviderPromoWhenUnconfigured() {
        // §AC：AI provider 提示分支已删除——未配置 AI 也不在 home 出现
        setContent(uiState = uiState(intelligenceAvailable = false))
        compose.onAllNodesWithText("连接一个 AI", substring = true).assertCountEquals(0)
    }

    @Test
    fun homeHasNoAiProviderPromoWhenConfigured() {
        setContent(uiState = uiState(intelligenceAvailable = true))
        compose.onAllNodesWithText("连接一个 AI", substring = true).assertCountEquals(0)
    }

    // ===== WHY 内联证据（§AD/§AE） =====

    @Test
    fun whyInlineOneTapShowsEvidenceAndCorrection() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        clickText(PORTRAIT_COPY_SECTION_WHY)
        compose.onNodeWithText("今天的依据").assertExists()
        // Today observed / Personal usual（基线）/ Delta 行（真实 facts）
        compose.onNodeWithText("今天：126 分钟").assertExists()
        compose.onNodeWithText("平常：98 分钟").assertExists()
        compose.onNodeWithText("变化：多 28 分钟").assertExists()
        // 纠正入口立即可见（无需任何二级展开）
        compose.onNodeWithText(PORTRAIT_COPY_FEEDBACK_QUESTION).assertExists()
        // 旧渐进 sheet 文案不复存在
        compose.onNodeWithText("更多依据与反馈").assertDoesNotExist()
    }

    @Test
    fun correctionWithinTwoTaps() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        // tap 1：WHY 展开；tap 2：「这不符合实际？」→ chips 立即可见可点
        clickText(PORTRAIT_COPY_SECTION_WHY)
        clickText(PORTRAIT_COPY_FEEDBACK_QUESTION)
        compose.onNodeWithText("今天有什么不一样？").assertExists()
        val firstReason = CORRECTION_REASONS.first()
        // 语义级 OnClick（Robolectric 假字体坐标不可靠；chips 可能位于滚动折叠区下方）
        compose.onNode(hasClickAction() and hasText(firstReason)).assertExists()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue(recorder.notLikes.isNotEmpty())
        assertTrue(recorder.corrections.isNotEmpty())
        assertEquals("2026-08-15", recorder.corrections.first().first)
        assertEquals(firstReason, recorder.corrections.first().second)
        // 选择后可重新生成
        clickText("重新看看今天")
    }

    @Test
    fun portraitLikeStillRecorded() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        clickText(PORTRAIT_COPY_SECTION_WHY)
        clickText(PORTRAIT_COPY_FEEDBACK_LIKE)
        assertTrue(recorder.likes.isNotEmpty())
    }

    @Test
    fun whyMoreTechDetailShowsWindowCoverageSourceNotUsed() {
        setContent(
            portrait = PortraitUiState(
                status = PortraitStatus.READY,
                portrait = DailyPortraitDto(
                    date = "2026-08-15", status = "READY", confidence = "HIGH",
                    baselineDays = 7, headline = listOf("接近"),
                    summary = "今天和平时很接近。", dimensions = emptyMap(),
                    timezoneUsed = "Asia/Shanghai",
                    coverage = mapOf("movement" to 0.86),
                ),
            ),
        )
        clickText(PORTRAIT_COPY_SECTION_WHY)
        clickText("更多技术详情")
        // §44 EXPANDED：时间窗口 / coverage / 未使用什么（经新内联路径）
        compose.onNodeWithText("时间窗口：2026-08-15 · 基线 7 天 · 时区 Asia/Shanghai").assertExists()
        compose.onNodeWithText("数据覆盖：movement 0.86", substring = true).assertExists()
        compose.onNodeWithText("没有使用：原始音频、通知正文、精确位置").assertExists()
    }

    @Test
    fun journeyLinkLivesInWhyInline() {
        val recorder = Recorder()
        setContent(recorder = recorder)
        clickText(PORTRAIT_COPY_SECTION_WHY)
        clickText("更多技术详情")
        clickText("查看更多 → Journey")
        assertTrue(recorder.journey.isNotEmpty())
    }

    // ===== Ask 标准全屏目的地（§AF） =====

    @Test
    fun askOneTapStandardScreenOneRenderer() {
        setContent()
        compose.onAllNodesWithText("slot-visual").assertCountEquals(1) // home organism
        compose.onNodeWithTag("echo_scene_ask").performClick()
        compose.waitForIdle()
        // 标准屏：返回 affordance + 会话 + 输入 + mini ECHO
        compose.onNodeWithTag("echo_ask_screen").assertExists()
        compose.onNodeWithContentDescription("返回").assertExists()
        compose.onNodeWithText("问 ECHO 关于你的事").assertExists()
        compose.onNodeWithText("问 ECHO…").assertExists()
        // 唯一高成本会话：home organism 不参与组合，仅剩 Ask mini（slot-visual 计 1）
        compose.onAllNodesWithText("slot-visual").assertCountEquals(1)
        compose.onNodeWithText(PORTRAIT_COPY_SECTION_WHY).assertDoesNotExist()
        // 标准返回 → home 恢复（Ask 卸载）
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(PORTRAIT_COPY_SECTION_WHY).assertExists()
        compose.onNodeWithText("问 ECHO 关于你的事").assertDoesNotExist()
        compose.onAllNodesWithText("slot-visual").assertCountEquals(1)
    }

    // ===== 行动降级入口（§AG） =====

    @Test
    fun demotedActionEntryHostsAvailableActionsInline() {
        setContent()
        // 不再是 sheet：quiet 展开区直接位于 Ask 之下，无点击即承载可用行动
        compose.onNodeWithText("slot-actions").assertExists()
        compose.onNodeWithTag("echo_scene_action_entry").assertExists()
    }

    @Test
    fun emptyActionEntryRendersNothing() {
        setContent(actionLayer = {})
        compose.onNodeWithText("slot-actions").assertDoesNotExist()
        compose.onNodeWithText(PORTRAIT_COPY_SECTION_ACTION).assertDoesNotExist()
    }

    // ===== 行动覆盖层 =====

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

    // ===== 结构回归（§BK：源码锚点） =====

    @Test
    fun sceneSourcesContainNoRemovedPromoOrLegacyStrings() {
        val files = listOf(
            "src/main/java/com/yunjue/echo/mind/ui/EchoSceneScreen.kt",
            "src/main/java/com/yunjue/echo/mind/ui/EchoHomeContent.kt",
        ).map { File(it).readText() }
        files.forEach { source ->
            assertFalse("旧渐进 sheet 文案不得回归", source.contains("更多依据与反馈"))
            assertFalse("AI provider 提示不得回归（§AC）", source.contains("连接一个 AI"))
            assertFalse("纠正 chips 不得回到 chunked 行布局（§AJ）", source.contains("chunked(4)"))
        }
    }
}
