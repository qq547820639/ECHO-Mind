package com.yunjue.echo.mind.ui
import com.yunjue.echo.mind.model.EchoMaturity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOAD_FAILED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_OFFLINE_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_PARTIAL_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REENABLE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REGENERATE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_RETRY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SENSING_DISABLED
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.todayPortraitStateText
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import com.yunjue.echo.mind.ui.echo.EchoSceneViewModel
import com.yunjue.echo.mind.ui.echo.actions.EchoActionLayer
import com.yunjue.echo.mind.ui.echo.components.BaselineProgress
import com.yunjue.echo.mind.ui.echo.components.CoverageRow
import com.yunjue.echo.mind.ui.echo.components.EchoStatusOverlay
import com.yunjue.echo.mind.ui.echo.components.EchoVisualSurface
import com.yunjue.echo.mind.ui.echo.components.echoVisualSurfaceConfig
import com.yunjue.echo.mind.ui.echo.components.PortraitSummaryOnly
import com.yunjue.echo.mind.ui.echo.components.SeedPortraitBlock
import com.yunjue.echo.mind.ui.echo.components.UnlockBanner
import com.yunjue.echo.mind.ui.echo.conversation.EchoConversationLayer
import com.yunjue.echo.mind.ui.echo.why.EchoWhyLayer
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

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val portrait by viewModel.portrait.collectAsStateWithLifecycle()
    val turns by viewModel.conversation.turns.collectAsStateWithLifecycle()
    val phase by viewModel.conversation.phase.collectAsStateWithLifecycle()
    val runningAction by viewModel.actionRuntime.running.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    EchoSceneContent(
        state = EchoSceneContentState(
            uiState = uiState,
            portrait = portrait,
            turns = turns,
            phase = phase,
            runningAction = runningAction,
            message = message,
            aiPromptDismissed = container.preferences.aiPromptDismissed,
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
            onConversationFeedback = viewModel::recordConversationFeedback,
        ),
        feedbackActions = EchoSceneFeedbackActions(
            onDismissAiPrompt = viewModel::dismissAiPrompt,
            onPortraitLike = { viewModel.recordPortraitFeedback(it, true) },
            onPortraitNotLike = { viewModel.recordPortraitFeedback(it, false) },
            onPortraitCorrection = viewModel::recordPortraitCorrection,
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
    val onPortraitLike: (String) -> Unit,
    val onPortraitNotLike: (String) -> Unit,
    val onPortraitCorrection: (String, String, String?) -> Unit,
    val onRebuildTodayPortrait: () -> Unit,
    val portraitFeedbackFor: (String) -> Boolean?,
)

/**
 * ERA 36 — EchoScene 纯状态内容（state-in / event-out + 视觉·行动·覆盖层三槽位）。
 * 只消费 [EchoSceneContentState]；无 ViewModel / Repository / AppPreferences 持有。
 * 行动覆盖层为槽位：真实 EchoActionOverlay 含无限帧动画（Robolectric 不友好），
 * 由调用侧注入；本层只负责 runningAction != null 的条件渲染。
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
    qualityFeedback: @Composable () -> Unit = {},
) {
    val uiState = state.uiState
    val portrait = state.portrait

    val todayMd = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日")) }
    var askExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. ECHO 视觉主体（第一视觉永远是 ECHO——§9）
            item { visualSurface() }
            // 1.5 日期只是安静的时间锚（ERA 31 R12：headlineMedium → labelMedium——
            // 大字号日期会与 ECHO 抢第一视觉，属 Dashboard 式元数据噪音；§10 信息量压缩）
            item { Text("今天 · $todayMd", style = MaterialTheme.typography.labelMedium) }

            // 2. 状态透明（非 ACTIVE 才可见）+ 初次 AI 提示
            item {
                EchoStatusOverlay(
                    sensing = uiState.sensing,
                    intelligenceAvailable = uiState.intelligenceAvailable,
                    aiPromptDismissed = state.aiPromptDismissed,
                    onGoToMe = navigation.onGoToMe,
                    onDismissAiPrompt = feedbackActions.onDismissAiPrompt,
                )
            }

            // 3. 周小结消息（订阅/本地镜像；ERA 20 §8 去卡化——无边框直排）
            state.message?.let { msg ->
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(msg.title, style = MaterialTheme.typography.titleSmall)
                        Text(msg.body, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 4. 画像九态（状态机渲染；组件只读状态）
            when (portrait.status) {
                PortraitStatus.LOADING -> item {
                    Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                }
                PortraitStatus.WARMING_UP -> item {
                    if (uiState.maturity == EchoMaturity.SEED) {
                        SeedPortraitBlock(awakenedAtEpochMs = state.awakenedAtEpochMs, state = portrait)
                    } else {
                        Text(todayPortraitStateText(PortraitStatus.WARMING_UP), Modifier.padding(top = 20.dp))
                        BaselineProgress(uiState.baselineDays)
                        val factsOnly = portrait.portrait?.summary
                            ?.substringAfter("\n\n", missingDelimiterValue = "")
                            ?.takeIf { it.isNotBlank() }
                        if (factsOnly != null) {
                            Text(factsOnly, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        }
                        CoverageRow(portrait.portrait?.coverage)
                    }
                }
                PortraitStatus.EARLY_BASELINE, PortraitStatus.LOW_CONFIDENCE -> item {
                    PortraitSummaryOnly(portrait)
                    BaselineProgress(uiState.baselineDays)
                    CoverageRow(portrait.portrait?.coverage)
                }
                PortraitStatus.READY -> item {
                    UnlockBanner(consumeUnlocked = coreActions.onConsumeUnlocked, state = portrait)
                    EchoWhyLayer(uiState = uiState, onGoToJourney = navigation.onGoToJourney)
                }
                PortraitStatus.PARTIAL_DATA -> {
                    item { Text(PORTRAIT_COPY_PARTIAL_BANNER, Modifier.padding(top = 12.dp)) }
                    item { EchoWhyLayer(uiState = uiState, onGoToJourney = navigation.onGoToJourney) }
                }
                PortraitStatus.OFFLINE_CACHED -> {
                    if (portrait.offline) {
                        item { Text(PORTRAIT_COPY_OFFLINE_BANNER, Modifier.padding(top = 12.dp)) }
                    }
                    item { EchoWhyLayer(uiState = uiState, onGoToJourney = navigation.onGoToJourney) }
                }
                PortraitStatus.SENSING_DISABLED -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(PORTRAIT_COPY_SENSING_DISABLED)
                        Button(
                            onClick = coreActions.onReEnableSensing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(PORTRAIT_COPY_REENABLE)
                        }
                    }
                }
                PortraitStatus.ERROR -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(PORTRAIT_COPY_LOAD_FAILED)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = coreActions.onRetryPortrait) { Text(PORTRAIT_COPY_RETRY) }
                    }
                }
            }

            // 4.5 ERA 29 §64：内部质量反馈（仅 DEBUG 构建渲染，正式用户不可见）
            item { qualityFeedback() }

            // 5. Journey 入口（Layer 3 证据/长期趋势）—— TextButton：安静入口，不做按钮墙
            item {
                TextButton(onClick = navigation.onGoToJourney, modifier = Modifier.fillMaxWidth()) {
                    Text("Journey · 我的时间 →")
                }
            }

            // 6. 行动（EchoActionRuntime 统一裁决；Scene 内执行）
            item { actionLayer() }

            // 7. 紧急入口（安全资源常驻可达）
            item {
                OutlinedButton(onClick = navigation.onEmergency, modifier = Modifier.fillMaxWidth()) { Text("紧急支持") }
            }

            // 8. 画像反馈（Correction Memory 由 Service 写入）
            if (portrait.status == PortraitStatus.READY || portrait.status == PortraitStatus.PARTIAL_DATA) {
                item {
                    PortraitFeedbackContent(
                        state = portrait,
                        feedbackLookup = feedbackActions.portraitFeedbackFor,
                        onLike = feedbackActions.onPortraitLike,
                        onNotLike = feedbackActions.onPortraitNotLike,
                        onCorrection = feedbackActions.onPortraitCorrection,
                        onRebuild = feedbackActions.onRebuildTodayPortrait,
                    )
                }
            }

            // 9. Ask ECHO 对话层（Controller 状态机驱动）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { askExpanded = !askExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (askExpanded) "收起对话" else "问 ECHO") }
                    if (askExpanded) {
                        EchoConversationLayer(
                            turns = state.turns,
                            phase = state.phase,
                            onAsk = coreActions.onAsk,
                            onFeedback = coreActions.onConversationFeedback,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(96.dp)) }
        }

        // 10. Scene 内行动覆盖层（运行时 running 状态驱动；结束回 Ambient Scene）
        state.runningAction?.let { actionOverlay() }
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
