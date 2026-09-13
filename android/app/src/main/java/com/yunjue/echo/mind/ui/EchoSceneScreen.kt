package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.actions.EchoActionKind
import com.yunjue.echo.mind.intelligence.ConversationPhase
import com.yunjue.echo.mind.intelligence.ConversationTurn
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.MessageDisplay
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import com.yunjue.echo.mind.ui.echo.EchoSceneViewModel
import com.yunjue.echo.mind.ui.echo.actions.EchoActionLayer
import com.yunjue.echo.mind.ui.echo.components.EchoVisualSurface
import com.yunjue.echo.mind.ui.echo.components.InternalQualityFeedback
import com.yunjue.echo.mind.ui.echo.components.echoVisualSurfaceConfig

/**
 * v3 §10/§AH — EchoSceneScreen（ECHO 世界主路径，最终形态）。
 *
 * Screen 只负责**组合**：VM 六流收集 + 容器依赖 slot 化 + 回调装配；
 * 纯渲染拆分到兄弟文件（§AH）：
 * - [EchoHomeContent] — home 视觉 + narrative + Why/Ask/Action 入口
 * - [EchoInlineEvidence] — WHY 内联证据 + 纠正（§AD/§AE）
 * - [EchoAskScreen] — Ask 全屏目的地（§AF；home organism 不与之共存）
 * - [EchoActionEntry] — 降级行动入口（§AG）
 * - [EchoTransientStatus] — wallpaper pill + day-key 日期（§AC/§AI）
 * 所有业务逻辑在 [EchoSceneViewModel] + Coordinator/Controller/Service 层。
 */
@Composable
fun EchoSceneScreen(
    container: AppContainer,
    onGoToJourney: () -> Unit,
    onGoToMe: () -> Unit,
    onEmergency: () -> Unit,
) {
    val viewModel: EchoSceneViewModel = viewModel(factory = EchoSceneViewModel.factory(container))
    val context = LocalContext.current
    // §35：三 slot 共享同一 config，读一次 SharedPreferences 避免每次重组重复 IO
    val visualConfig = remember(container.preferences.presenceMotionLevel, container.preferences.presenceReduceMotion, container.preferences.presenceNightMode) {
        echoVisualSurfaceConfig(
            motionLevelPref = container.preferences.presenceMotionLevel,
            reduceMotion = container.preferences.presenceReduceMotion,
            nightMode = container.preferences.presenceNightMode,
        )
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val portrait by viewModel.portrait.collectAsStateWithLifecycle()
    val turns by viewModel.conversation.turns.collectAsStateWithLifecycle()
    val phase by viewModel.conversation.phase.collectAsStateWithLifecycle()
    val runningAction by viewModel.actionRuntime.running.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val wallpaperPromptDismissed by viewModel.wallpaperPromptDismissed.collectAsStateWithLifecycle()
    // §45：Correction 成功不只 Toast——触发 organism 900ms 视觉脉冲（halo -8% + phase pause）
    var correctionPulseTrigger by remember { mutableIntStateOf(0) }

    EchoSceneContent(
        state = EchoSceneContentState(
            uiState = uiState,
            portrait = portrait,
            turns = turns,
            phase = phase,
            runningAction = runningAction,
            message = message,
            wallpaperPromptDismissed = wallpaperPromptDismissed,
        ),
        navigation = EchoSceneNavigation(
            onGoToJourney = onGoToJourney,
            onGoToMe = onGoToMe,
            onEmergency = onEmergency,
        ),
        coreActions = EchoSceneCoreActions(
            onConsumeUnlocked = viewModel::consumeBaselineUnlocked,
            onReEnableSensing = { viewModel.reEnableSensing(); viewModel.refresh() },
            onRetryPortrait = viewModel::retryPortrait,
            onStartAction = viewModel::startAction,
            onStopAction = viewModel::stopAction,
            onAsk = viewModel::ask,
            onConversationFeedback = { q, a, like, reason ->
                viewModel.recordConversationFeedback(q, a, like, reason)
                correctionPulseTrigger++
            },
        ),
        feedbackActions = EchoSceneFeedbackActions(
            onDismissWallpaperPrompt = viewModel::dismissWallpaperPrompt,
            onSelectWallpaper = {
                // ERA 31 R34：跳系统动态壁纸选择器（组件钉定 ECHO 壁纸）；
                // 打开过即视为已引导（不再重复出现）。
                viewModel.dismissWallpaperPrompt()
                selectEchoWallpaper(context)
            },
            onPortraitLike = {
                viewModel.recordPortraitFeedback(it, true)
                correctionPulseTrigger++
            },
            onPortraitNotLike = { viewModel.recordPortraitFeedback(it, false) },
            onPortraitCorrection = { d, r, s2 ->
                viewModel.recordPortraitCorrection(d, r, s2)
                correctionPulseTrigger++
            },
            onRebuildTodayPortrait = viewModel::rebuildTodayPortrait,
            portraitFeedbackFor = viewModel::portraitFeedback,
        ),
        visualSurface = {
            EchoVisualSurface(
                presence = uiState.presence,
                config = visualConfig,
                correctionPulseTrigger = correctionPulseTrigger,
            )
        },
        askMiniEcho = {
            EchoAskMiniOrganism(
                presence = uiState.presence,
                config = visualConfig,
            )
        },
        actionLayer = {
            val presenceNow = uiState.presence
            EchoActionLayer(
                availability = viewModel.actionRuntime.availability(
                    confidence = presenceNow?.confidence ?: 0f,
                    ambientKnown = presenceNow != null && presenceNow.maturity != EchoMaturity.SEED,
                    suggestionsEnabled = container.preferences.presenceSuggestionsEnabled,
                ),
                skillRepository = container.skillRepository,
                coordinator = container.skillSessionCoordinator,
                onStartAction = viewModel::startAction,
            )
        },
        actionOverlay = {
            EchoActionOverlay(
                presence = uiState.presence,
                mode = if (runningAction == EchoActionKind.BREATHING)
                    EchoActionMode.BREATHING else EchoActionMode.PAUSE,
                config = echoVisualSurfaceConfig(
                    motionLevelPref = container.preferences.presenceMotionLevel,
                    reduceMotion = container.preferences.presenceReduceMotion,
                    nightMode = container.preferences.presenceNightMode,
                ),
                onDone = viewModel::stopAction,
            )
        },
        qualityFeedback = {
            InternalQualityFeedback(
                preferences = container.preferences,
                headline = uiState.headline,
            )
        },
    )
}

/** ERA 36 — EchoSceneContent 状态输入（六流聚合 + 偏好派生输入）。 */
data class EchoSceneContentState(
    val uiState: EchoSceneUiState,
    val portrait: PortraitUiState,
    val turns: List<ConversationTurn>,
    val phase: ConversationPhase,
    val runningAction: EchoActionKind?,
    val message: MessageDisplay?,
    val wallpaperPromptDismissed: Boolean = true,
)

/** ERA 36 — EchoSceneContent 导航回调（世界间跳转）。 */
data class EchoSceneNavigation(
    val onGoToJourney: () -> Unit,
    val onGoToMe: () -> Unit,
    val onEmergency: () -> Unit,
)

/** ERA 36 — EchoSceneContent 核心业务回调（运行时/画像/对话/行动）。 */
data class EchoSceneCoreActions(
    val onConsumeUnlocked: () -> Boolean,
    val onReEnableSensing: () -> Unit,
    val onRetryPortrait: () -> Unit,
    val onStartAction: (EchoActionKind) -> Unit,
    val onStopAction: () -> Unit,
    val onAsk: (String) -> Unit,
    val onConversationFeedback: (String, String, Boolean, String?) -> Unit,
)

/** ERA 36 — EchoSceneContent 反馈/提示回调（Correction Memory 与一次性引导）。 */
data class EchoSceneFeedbackActions(
    val onDismissWallpaperPrompt: () -> Unit = {},
    val onSelectWallpaper: () -> Unit = {},
    val onPortraitLike: (String) -> Unit,
    val onPortraitNotLike: (String) -> Unit,
    val onPortraitCorrection: (String, String, String?) -> Unit,
    val onRebuildTodayPortrait: () -> Unit,
    val portraitFeedbackFor: (String) -> Boolean?,
)

/**
 * §80 major-surface testTags（screen 契约锚点；由 [EchoHomeContent] 消费）。
 * 保持字符串字面量常驻本文件（结构回归门扫描锚点）。
 */
internal const val ECHO_SCENE_TAG_VISUAL = "echo_scene_visual"
internal const val ECHO_SCENE_TAG_NARRATIVE = "echo_scene_narrative"
internal const val ECHO_SCENE_TAG_WHY = "echo_scene_why"
internal const val ECHO_SCENE_TAG_ASK = "echo_scene_ask"

/**
 * V3 §AB/§AF — ambient ECHO Scene 装配：home（visual/narrative/Why/Ask/Action）
 * 与 Ask 全屏目的地互斥组合——**唯一高成本渲染会话**（Ask 打开时 home
 * organism 不参与组合）；行动覆盖层由 runningAction 驱动在最上层。
 * 渐进 sheet 机制已删除（§AD WHY 内联 / §AF Ask 屏幕 / §AG 行动内联）。
 */
@Composable
fun EchoSceneContent(
    state: EchoSceneContentState,
    navigation: EchoSceneNavigation,
    coreActions: EchoSceneCoreActions,
    feedbackActions: EchoSceneFeedbackActions,
    visualSurface: @Composable () -> Unit,
    actionLayer: @Composable () -> Unit,
    actionOverlay: @Composable () -> Unit,
    askMiniEcho: @Composable () -> Unit = {},
    qualityFeedback: @Composable () -> Unit = {},
) {
    // §AF：Ask 全屏目的地状态（ECHO tab 内 overlay；标准 Back 返回 home）
    var askOpen by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (askOpen) {
            EchoAskScreen(
                state = state,
                coreActions = coreActions,
                onBack = { askOpen = false },
                miniEcho = askMiniEcho,
            )
        } else {
            EchoHomeContent(
                state = state,
                navigation = navigation,
                coreActions = coreActions,
                feedbackActions = feedbackActions,
                sceneHeight = maxHeight,
                visualSurface = visualSurface,
                actionLayer = actionLayer,
                qualityFeedback = qualityFeedback,
                onOpenAsk = { askOpen = true },
            )
        }

        // Scene 内行动覆盖层（running 状态驱动；结束回 Ambient Scene）
        state.runningAction?.let { actionOverlay() }
    }
}

/** §38/§79：视觉占比纯函数（1.0 → 61.5% 目标；fontScale ≥1.3 → 52%）。 */
internal fun visualFractionFor(fontScale: Float): Float = if (fontScale >= 1.3f) 0.52f else 0.615f

/**
 * ERA 31 R34：跳系统动态壁纸选择器（组件钉定 ECHO 壁纸）。
 * 与 Me → PresenceSettingsSection 的入口同一 intent；失败静默（无障碍环境无壁纸选择器）。
 */
private fun selectEchoWallpaper(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                android.content.ComponentName(
                    context,
                    com.yunjue.echo.mind.EchoWallpaperService::class.java
                )
            )
        )
    }
}
