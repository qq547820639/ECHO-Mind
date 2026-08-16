package com.yunjue.echo.mind.ui
import com.yunjue.echo.mind.model.EchoMaturity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.actions.EchoActionKind
import com.yunjue.echo.mind.intelligence.ConversationPhase
import com.yunjue.echo.mind.intelligence.ConversationTurn
import com.yunjue.echo.mind.model.MessageDisplay
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_NOT_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_QUESTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_SAVED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOADING_QUIET
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOAD_FAILED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_OFFLINE_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_PARTIAL_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REENABLE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REGENERATE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_RETRY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_ACTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_WHY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SENSING_DISABLED
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import com.yunjue.echo.mind.ui.echo.EchoSceneViewModel
import com.yunjue.echo.mind.ui.echo.actions.EchoActionLayer
import com.yunjue.echo.mind.ui.echo.conversation.EchoConversationLayer
import com.yunjue.echo.mind.ui.echo.components.EchoStatusOverlay
import com.yunjue.echo.mind.ui.echo.components.EchoVisualSurface
import com.yunjue.echo.mind.ui.echo.components.echoVisualSurfaceConfig
import com.yunjue.echo.mind.ui.echo.components.UnlockBanner

import com.yunjue.echo.mind.ui.echo.conversation.EchoConversationLayer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * v3 §10 — EchoSceneScreen（ECHO 世界主路径，最终形态）。
 *
 * Screen 只负责**组合**：Visual / Headline(Why) / Conversation / Action。
 * 所有业务逻辑（AI 请求/记忆写入/证据检索/行动运行）在 [EchoSceneViewModel] +
 * Coordinator/Controller/Service 层，Screen 不直接操作 Repository。
 *
 * ERA 36 状态提升：Screen 只做六流收集 + 容器依赖 slot 化 + 回调装配；
 * 纯渲染在 [EchoSceneContent]（state-in / event-out，Compose smoke test 无需 AppContainer）。
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

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val portrait by viewModel.portrait.collectAsStateWithLifecycle()
    val turns by viewModel.conversation.turns.collectAsStateWithLifecycle()
    val phase by viewModel.conversation.phase.collectAsStateWithLifecycle()
    val runningAction by viewModel.actionRuntime.running.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val aiPromptDismissed by viewModel.aiPromptDismissed.collectAsStateWithLifecycle()
    val wallpaperPromptDismissed by viewModel.wallpaperPromptDismissed.collectAsStateWithLifecycle()
    // §45：Correction 成功不只 Toast——触发 organism 900ms 视觉脉冲（halo -8% + phase pause）
    var correctionPulseTrigger by remember { mutableStateOf(0) }

    EchoSceneContent(
        state = EchoSceneContentState(
            uiState = uiState,
            portrait = portrait,
            turns = turns,
            phase = phase,
            runningAction = runningAction,
            message = message,
            aiPromptDismissed = aiPromptDismissed,
            wallpaperPromptDismissed = wallpaperPromptDismissed,
            awakenedAtEpochMs = container.preferences.awakenedAtEpochMs,
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
            onDismissAiPrompt = viewModel::dismissAiPrompt,
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
                config = echoVisualSurfaceConfig(
                    motionLevelPref = container.preferences.presenceMotionLevel,
                    reduceMotion = container.preferences.presenceReduceMotion,
                    nightMode = container.preferences.presenceNightMode,
                ),
                correctionPulseTrigger = correctionPulseTrigger,
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
            com.yunjue.echo.mind.ui.echo.components.InternalQualityFeedback(
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
    val aiPromptDismissed: Boolean,
    val wallpaperPromptDismissed: Boolean = true,
    val awakenedAtEpochMs: Long = 0L,
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

/** ERA 36 — EchoSceneContent 反馈/提示回调（Correction Memory 与 AI 提示）。 */
data class EchoSceneFeedbackActions(
    val onDismissAiPrompt: () -> Unit,
    val onDismissWallpaperPrompt: () -> Unit = {},
    val onSelectWallpaper: () -> Unit = {},
    val onPortraitLike: (String) -> Unit,
    val onPortraitNotLike: (String) -> Unit,
    val onPortraitCorrection: (String, String, String?) -> Unit,
    val onRebuildTodayPortrait: () -> Unit,
    val portraitFeedbackFor: (String) -> Boolean?,
)

/**
 * V3 §37–§43 — ambient ECHO Scene（Scene 不是 Feed）。
 *
 * 结构：Box ├ Echo visual（61.5% 首 viewport；fontScale≥1.3 → 52%）
 *           ├ ambient transient prompt（wallpaper/AI 一颗安静 pill）
 *           ├ narrative gradient + observation（headline 22/29 ≤2 行 + secondary 15/21 ≤2 行）
 *           ├ Why（48dp）/ Ask（52dp quiet surface）/ Action（sheet）
 *           ├ action overlay └ active bottom sheet。
 * 移除 Feed 项：portrait feedback → WHY sheet；action list → Action sheet；
 * conversation → Ask sheet；weekly message → Journey quiet indicator（slice 12）；
 * wallpaper/AI card → AmbientPromptPill；Journey 重复入口 → WHY 内「查看更多」；
 * 重复 emergency card → 壳层常驻 FAB（不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoSceneContent(
    state: EchoSceneContentState,
    navigation: EchoSceneNavigation,
    coreActions: EchoSceneCoreActions,
    feedbackActions: EchoSceneFeedbackActions,
    visualSurface: @Composable () -> Unit,
    actionLayer: @Composable () -> Unit,
    actionOverlay: @Composable () -> Unit,
    qualityFeedback: @Composable () -> Unit = {},
) {
    val uiState = state.uiState
    val portrait = state.portrait

    val todayMd = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日")) }
    var sheet by remember { mutableStateOf(SceneSheet.NONE) }
    // §47：选择行动后关闭 sheet → 现有 Action Runtime → action overlay
    LaunchedEffect(state.runningAction) {
        if (state.runningAction != null) sheet = SceneSheet.NONE
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sceneHeight = maxHeight
        // §79：fontScale ≥1.3 时视觉占比 .615 → .52，narrative 允许滚动由下方 Column 承担
        val visualFraction = visualFractionFor(LocalConfiguration.current.fontScale)

        Column(Modifier.fillMaxSize()) {
            // 1. 视觉区（第一眼是 ECHO；testTag: echo_scene_visual）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(sceneHeight * visualFraction)
                    .testTag("echo_scene_visual"),
            ) {
                visualSurface()
                // narrative gradient（视觉底部渐隐，保证文字可读性）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x00040814),
                                1f to Color(0xC0040814),
                            ),
                        ),
                )
                // ambient transient prompt（wallpaper/AI 一颗 pill，一次只一颗；安静可关闭）
                AmbientPromptPill(
                    state = state,
                    uiState = uiState,
                    feedbackActions = feedbackActions,
                    navigation = navigation,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            // 2. narrative（左右 24dp；testTag: echo_scene_narrative）
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .testTag("echo_scene_narrative"),
            ) {
                Text(
                    "今天 · $todayMd",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                )
                Spacer(Modifier.height(6.dp))
                val narrative = resolveSceneNarrative(uiState, portrait)
                Text(
                    narrative.headline,
                    fontSize = 22.sp,
                    lineHeight = 29.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                narrative.secondary?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    )
                }
                // SENSING_DISABLED / ERROR：真实动作（re-enable / retry secondary）
                when (portrait.status) {
                    PortraitStatus.SENSING_DISABLED -> TextButton(
                        onClick = coreActions.onReEnableSensing,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(PORTRAIT_COPY_REENABLE) }
                    PortraitStatus.ERROR -> TextButton(
                        onClick = coreActions.onRetryPortrait,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(PORTRAIT_COPY_RETRY) }
                    PortraitStatus.READY -> UnlockBanner(
                        consumeUnlocked = coreActions.onConsumeUnlocked,
                        state = portrait,
                    )
                    else -> Unit
                }
                // 感知状态透明（非 ACTIVE 才可见；安静文案，无卡片）
                EchoStatusOverlay(
                    sensing = uiState.sensing,
                    intelligenceAvailable = uiState.intelligenceAvailable,
                    aiPromptDismissed = true, // AI 提示已迁入 AmbientPromptPill（唯一 transient）
                    onGoToMe = navigation.onGoToMe,
                    onDismissAiPrompt = feedbackActions.onDismissAiPrompt,
                )
            }

            Spacer(Modifier.height(10.dp))

            // 3. Why（48dp touch target；testTag: echo_scene_why）
            TextButton(
                onClick = { sheet = SceneSheet.WHY },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(min = 48.dp)
                    .testTag("echo_scene_why"),
            ) { Text(PORTRAIT_COPY_SECTION_WHY) }

            // 4. Ask（52dp quiet surface；testTag: echo_scene_ask）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .clickable { sheet = SceneSheet.ASK }
                    .padding(horizontal = 20.dp)
                    .testTag("echo_scene_ask"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "问 ECHO",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }

            // 5. Action 安静入口（真实 available actions 在 sheet 内；§47）
            TextButton(
                onClick = { sheet = SceneSheet.ACTIONS },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(min = 48.dp),
            ) { Text(PORTRAIT_COPY_SECTION_ACTION) }

            // ERA 29 §64：内部质量反馈（仅 DEBUG 构建渲染）
            qualityFeedback()
        }

        // 6. Scene 内行动覆盖层（running 状态驱动；结束回 Ambient Scene）
        state.runningAction?.let { actionOverlay() }

        // 7. Progressive surfaces（active bottom sheet；§37 同一 Box 结构）
        when (sheet) {
            SceneSheet.WHY -> SceneBottomSheet(onClose = { sheet = SceneSheet.NONE }) { fraction ->
                EchoWhySheetContent(
                    uiState = uiState,
                    portrait = portrait,
                    feedbackActions = feedbackActions,
                    onGoToJourney = {
                        sheet = SceneSheet.NONE
                        navigation.onGoToJourney()
                    },
                    onFraction = { fraction.value = it },
                )
            }
            SceneSheet.ASK -> SceneBottomSheet(onClose = { sheet = SceneSheet.NONE }, initialFraction = 0.82f) {
                EchoAskSheetContent(
                    state = state,
                    coreActions = coreActions,
                    visualSurface = visualSurface,
                )
            }
            SceneSheet.ACTIONS -> SceneBottomSheet(onClose = { sheet = SceneSheet.NONE }) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) { actionLayer() }
            }
            SceneSheet.NONE -> Unit
        }
    }
}

/**
 * Scene active bottom sheet（§37 同一 Box 结构内成员；非系统 ModalBottomSheet——
 * PEEK/EXPANDED 需要精确 42%/82% 高度控制，且 Robolectric 可测）。
 */
@Composable
private fun SceneBottomSheet(
    onClose: () -> Unit,
    initialFraction: Float = 0.42f,
    content: @Composable (androidx.compose.runtime.MutableState<Float>) -> Unit,
) {
    val fraction = remember { mutableStateOf(initialFraction) }
    Box(Modifier.fillMaxSize()) {
        // scrim（点击关闭；只覆盖 sheet 上方区域，不与 sheet 内容重叠）
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(1f - fraction.value)
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fraction.value)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()),
        ) {
            content(fraction)
        }
    }
}

/** Scene 内 progressive surface 选择。 */
private enum class SceneSheet { NONE, WHY, ASK, ACTIONS }

/** §38/§79：视觉占比纯函数（1.0 → 61.5% 目标；fontScale ≥1.3 → 52%）。 */
internal fun visualFractionFor(fontScale: Float): Float = if (fontScale >= 1.3f) 0.52f else 0.615f

/** narrative 文案解析（状态机 → 一句 headline + 可选 secondary；全部 canonical copy）。 */
private data class SceneNarrative(val headline: String, val secondary: String?)

private fun resolveSceneNarrative(
    uiState: EchoSceneUiState,
    portrait: PortraitUiState,
): SceneNarrative = when (portrait.status) {
    // §40：LOADING 不用 spinner——quiet ECHO + canonical 语义「正在整理今天的观察…」
    PortraitStatus.LOADING -> SceneNarrative(PORTRAIT_COPY_LOADING_QUIET, null)
    PortraitStatus.WARMING_UP -> {
        val factsOnly = portrait.portrait?.summary
            ?.substringAfter("\n\n", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        SceneNarrative(uiState.headline, factsOnly)
    }
    PortraitStatus.EARLY_BASELINE, PortraitStatus.LOW_CONFIDENCE ->
        SceneNarrative(uiState.headline, portrait.portrait?.summary)
    PortraitStatus.READY -> SceneNarrative(uiState.headline, uiState.aiLayer)
    PortraitStatus.PARTIAL_DATA -> SceneNarrative(uiState.headline, PORTRAIT_COPY_PARTIAL_BANNER)
    PortraitStatus.OFFLINE_CACHED -> SceneNarrative(
        uiState.headline,
        if (portrait.offline) PORTRAIT_COPY_OFFLINE_BANNER else null,
    )
    // §42：Sensing Disabled 不是大型 error screen——identity 保留 + 真实 re-enable
    PortraitStatus.SENSING_DISABLED -> SceneNarrative(uiState.headline, PORTRAIT_COPY_SENSING_DISABLED)
    // §43：Error —— identity 继续存在，Retry secondary，无红色全屏
    PortraitStatus.ERROR -> SceneNarrative(uiState.headline, PORTRAIT_COPY_LOAD_FAILED)
}

/** Ambient transient prompt：wallpaper 引导 / AI 引导一次只一颗，安静可关闭。 */
@Composable
private fun AmbientPromptPill(
    state: EchoSceneContentState,
    uiState: EchoSceneUiState,
    feedbackActions: EchoSceneFeedbackActions,
    navigation: EchoSceneNavigation,
    modifier: Modifier = Modifier,
) {
    val prompt: Pair<String, () -> Unit>? = when {
        !state.wallpaperPromptDismissed ->
            "让 ECHO 留在桌面：设置动态壁纸 →" to feedbackActions.onSelectWallpaper
        !uiState.intelligenceAvailable && !state.aiPromptDismissed ->
            "连接一个 AI，让 ECHO 更深入地理解你 →" to navigation.onGoToMe
        else -> null
    }
    if (prompt != null) {
        Row(
            modifier
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = prompt.second) {
                Text(prompt.first, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = {
                    if (!state.wallpaperPromptDismissed) {
                        feedbackActions.onDismissWallpaperPrompt()
                    } else {
                        feedbackActions.onDismissAiPrompt()
                    }
                },
            ) { Text("以后再说", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** WHY sheet（§44 渐进证据：先说/观察到/通常/差异 → 展开再出反馈与来源）。 */
@Composable
private fun EchoWhySheetContent(
    uiState: EchoSceneUiState,
    portrait: PortraitUiState,
    feedbackActions: EchoSceneFeedbackActions,
    onGoToJourney: () -> Unit,
    onFraction: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { onFraction(if (expanded) 0.82f else 0.42f) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("今天的依据", style = MaterialTheme.typography.titleMedium)
        // 1. ECHO 说了什么
        Text(uiState.headline, style = MaterialTheme.typography.bodyLarge)
        uiState.aiLayer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        // 2–4. 今天观察到了什么 / 你的个人通常 / 两者差异（真实 facts）
        uiState.facts.forEach { fact ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (fact.label.isNotBlank()) Text(fact.label, style = MaterialTheme.typography.titleSmall)
                if (fact.todayText.isNotBlank()) Text("今天：${fact.todayText}", style = MaterialTheme.typography.bodySmall)
                if (fact.baselineText.isNotBlank()) Text("平常：${fact.baselineText}", style = MaterialTheme.typography.bodySmall)
                if (fact.deltaText.isNotBlank()) Text("变化：${fact.deltaText}", style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "收起依据" else "更多依据与反馈")
        }
        if (expanded) {
            // §44 EXPANDED：5. 时间窗口 6. coverage 7. source 8. 未使用什么（全部真实字段，不猜 provenance）
            portrait.portrait?.let { p ->
                Text(
                    "时间窗口：${p.date} · 基线 ${p.baselineDays} 天" +
                        (p.baselineVersion?.let { " · 基线版本 $it" } ?: "") +
                        (p.timezoneUsed?.let { " · 时区 $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                p.coverage?.takeIf { it.isNotEmpty() }?.let { cov ->
                    Text(
                        "数据覆盖：" + cov.entries.joinToString("、") { (k, v) -> "$k $v" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (uiState.headlineSources.isNotEmpty()) {
                Text(
                    "参考了：" + uiState.headlineSources.joinToString("、") { dataSourceLabelForSheet(it) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 精确词表与对话层一致（原始音频/通知正文/精确位置 永不进入）
            Text("没有使用：原始音频、通知正文、精确位置", style = MaterialTheme.typography.bodySmall)
            // 9. feedback / correction（从首页 Feed 迁入 WHY；§39）
            if (portrait.status == PortraitStatus.READY || portrait.status == PortraitStatus.PARTIAL_DATA) {
                PortraitFeedbackContent(
                    state = portrait,
                    feedbackLookup = feedbackActions.portraitFeedbackFor,
                    onLike = feedbackActions.onPortraitLike,
                    onNotLike = feedbackActions.onPortraitNotLike,
                    onCorrection = feedbackActions.onPortraitCorrection,
                    onRebuild = feedbackActions.onRebuildTodayPortrait,
                )
            }
            TextButton(onClick = onGoToJourney, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("查看更多 → Journey")
            }
        }
    }
}

/** Ask sheet（§46：顶部 28–34% 保留 mini live ECHO，下面才是 conversation）。 */
@Composable
private fun EchoAskSheetContent(
    state: EchoSceneContentState,
    coreActions: EchoSceneCoreActions,
    visualSurface: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(220.dp)) { visualSurface() }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            // §46：small identity glyph 用 identity palette primary（SAME ECHO）
            val identityColor = state.uiState.presence?.identityGenome?.seed?.let { seed ->
                val p = com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(seed).palette.primary
                Color(com.yunjue.echo.mind.visual.render.ColorSpace.lch(p.l, p.c, p.h))
            } ?: MaterialTheme.colorScheme.primary
            EchoConversationLayer(
                turns = state.turns,
                phase = state.phase,
                onAsk = coreActions.onAsk,
                onFeedback = coreActions.onConversationFeedback,
                identityColor = identityColor,
            )
        }
    }
}

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
                    com.yunjue.echo.mind.presence.EchoWallpaperService::class.java
                )
            )
        )
    }
}

/**
 * ERA 36 — 画像反馈纯内容（用户反馈「挺像/不太像」+ 原因 → 回调；
 * 不直接创建 MemoryEntity，不持有 ViewModel）。
 */
@Composable
fun PortraitFeedbackContent(
    state: PortraitUiState,
    feedbackLookup: (String) -> Boolean?,
    onLike: (String) -> Unit,
    onNotLike: (String) -> Unit,
    onCorrection: (String, String, String?) -> Unit,
    onRebuild: () -> Unit,
) {
    val portrait = state.portrait ?: return
    val date = portrait.date
    var feedback by remember(date) { mutableStateOf(feedbackLookup(date)) }
    var reasonPicked by remember(date) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(PORTRAIT_COPY_FEEDBACK_QUESTION, style = MaterialTheme.typography.bodyMedium)
        if (feedback == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    onLike(date)
                    feedback = true
                }) { Text(PORTRAIT_COPY_FEEDBACK_LIKE) }
                OutlinedButton(onClick = {
                    onNotLike(date)
                    feedback = false
                }) { Text(PORTRAIT_COPY_FEEDBACK_NOT_LIKE) }
            }
        } else {
            Text(PORTRAIT_COPY_FEEDBACK_SAVED, style = MaterialTheme.typography.bodySmall)
            if (feedback == false) {
                if (!reasonPicked) {
                    Text("今天有什么不一样？", style = MaterialTheme.typography.bodySmall)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.yunjue.echo.mind.memory.CORRECTION_REASONS.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { reason ->
                                    AssistChip(
                                        onClick = {
                                            reasonPicked = true
                                            onCorrection(date, reason, portrait.summary)
                                        },
                                        label = { Text(reason) }
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = onRebuild,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(PORTRAIT_COPY_REGENERATE)
                }
            }
        }
    }
}

/** 数据源类别 → 用户可读标签（WHY sheet 依据展示）。 */
private fun dataSourceLabelForSheet(category: com.yunjue.echo.mind.intelligence.DataSourceCategory): String =
    when (category) {
        com.yunjue.echo.mind.intelligence.DataSourceCategory.TODAY_AGGREGATE -> "今天的活动节律"
        com.yunjue.echo.mind.intelligence.DataSourceCategory.BASELINE -> "个人基线"
        com.yunjue.echo.mind.intelligence.DataSourceCategory.PORTRAIT_HISTORY -> "历史画像"
        com.yunjue.echo.mind.intelligence.DataSourceCategory.CONTEXT_EXCEPTIONS -> "你告诉我的特殊日期"
        com.yunjue.echo.mind.intelligence.DataSourceCategory.USER_CORRECTIONS -> "你纠正过我的"
        com.yunjue.echo.mind.intelligence.DataSourceCategory.PREFERENCES -> "你的偏好"
        com.yunjue.echo.mind.intelligence.DataSourceCategory.CONVERSATION_HISTORY -> "我们的对话"
        else -> "其他"
    }
