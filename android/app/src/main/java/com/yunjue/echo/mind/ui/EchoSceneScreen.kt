package com.yunjue.echo.mind.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.presence.echoMaturity
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import com.yunjue.echo.mind.ui.echo.EchoSceneViewModel
import com.yunjue.echo.mind.ui.echo.actions.EchoActionLayer
import com.yunjue.echo.mind.ui.echo.components.BaselineProgress
import com.yunjue.echo.mind.ui.echo.components.CoverageRow
import com.yunjue.echo.mind.ui.echo.components.EchoStatusOverlay
import com.yunjue.echo.mind.ui.echo.components.EchoVisualSurface
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

    val todayMd = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日")) }
    var askExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. ECHO 视觉主体
            item {
                EchoVisualSurface(
                    presence = uiState.presence,
                    preferences = container.preferences,
                )
            }
            item { Text("今天 · $todayMd", style = MaterialTheme.typography.headlineMedium) }

            // 2. 状态透明（非 ACTIVE 才可见）+ 初次 AI 提示
            item {
                EchoStatusOverlay(
                    sensing = uiState.sensing,
                    intelligenceAvailable = uiState.intelligenceAvailable,
                    aiPromptDismissed = container.preferences.aiPromptDismissed,
                    onGoToMe = onGoToMe,
                    onDismissAiPrompt = { viewModel.dismissAiPrompt() },
                )
            }

            // 3. 周小结消息（订阅/本地镜像）
            message?.let { msg ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(msg.title, style = MaterialTheme.typography.titleSmall)
                            Text(msg.body, style = MaterialTheme.typography.bodySmall)
                        }
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
                    if (echoMaturity(uiState.baselineDays) == EchoMaturity.SEED) {
                        SeedPortraitBlock(container.preferences, portrait)
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
                    UnlockBanner(consumeUnlocked = { viewModel.consumeBaselineUnlocked() }, state = portrait)
                    EchoWhyLayer(uiState = uiState, onGoToJourney = onGoToJourney)
                }
                PortraitStatus.PARTIAL_DATA -> {
                    item { Text(PORTRAIT_COPY_PARTIAL_BANNER, Modifier.padding(top = 12.dp)) }
                    item { EchoWhyLayer(uiState = uiState, onGoToJourney = onGoToJourney) }
                }
                PortraitStatus.OFFLINE_CACHED -> {
                    if (portrait.offline) {
                        item { Text(PORTRAIT_COPY_OFFLINE_BANNER, Modifier.padding(top = 12.dp)) }
                    }
                    item { EchoWhyLayer(uiState = uiState, onGoToJourney = onGoToJourney) }
                }
                PortraitStatus.SENSING_DISABLED -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(PORTRAIT_COPY_SENSING_DISABLED)
                        Button(
                            onClick = {
                                viewModel.reEnableSensing()
                                viewModel.refresh()
                            },
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
                        Button(onClick = { viewModel.retryPortrait() }) { Text(PORTRAIT_COPY_RETRY) }
                    }
                }
            }

            // 5. Journey 入口（Layer 3 证据/长期趋势）
            item {
                OutlinedButton(onClick = onGoToJourney, modifier = Modifier.fillMaxWidth()) {
                    Text("Journey · 我的时间 →")
                }
            }

            // 6. 行动（EchoActionRuntime 统一裁决；Scene 内执行）
            item {
                val presenceNow = uiState.presence
                EchoActionLayer(
                    availability = viewModel.actionRuntime.availability(
                        confidence = presenceNow?.confidence ?: 0f,
                        ambientKnown = presenceNow != null && presenceNow.maturity != EchoMaturity.SEED,
                        suggestionsEnabled = container.preferences.presenceSuggestionsEnabled,
                    ),
                    skillRepository = container.skillRepository,
                    coordinator = container.skillSessionCoordinator,
                    onStartAction = { viewModel.startAction(it) },
                )
            }

            // 7. 紧急入口（安全资源常驻可达）
            item {
                OutlinedButton(onClick = onEmergency, modifier = Modifier.fillMaxWidth()) { Text("紧急支持") }
            }

            // 8. 画像反馈（Correction Memory 由 Service 写入）
            if (portrait.status == PortraitStatus.READY || portrait.status == PortraitStatus.PARTIAL_DATA) {
                item { PortraitFeedbackRow(viewModel, portrait) }
            }

            // 9. Ask ECHO 对话层（Controller 状态机驱动）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { askExpanded = !askExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (askExpanded) "收起对话" else "问 ECHO") }
                    if (askExpanded) {
                        EchoConversationLayer(
                            turns = turns,
                            phase = phase,
                            onAsk = { viewModel.ask(it) },
                            onFeedback = { q, a, like, reason ->
                                viewModel.recordConversationFeedback(q, a, like, reason)
                            },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(96.dp)) }
        }

        // 10. Scene 内行动覆盖层（运行时 running 状态驱动；结束回 Ambient Scene）
        runningAction?.let { kind ->
            EchoActionOverlay(
                presence = uiState.presence,
                mode = if (kind == com.yunjue.echo.mind.actions.EchoActionKind.BREATHING)
                    EchoActionMode.BREATHING else EchoActionMode.PAUSE,
                onDone = { viewModel.stopAction() },
            )
        }
    }
}

/** 用户反馈（「挺像/不太像」+ 原因 → EchoCorrectionService；不直接创建 MemoryEntity）。 */
@Composable
private fun PortraitFeedbackRow(viewModel: EchoSceneViewModel, state: PortraitUiState) {
    val portrait = state.portrait ?: return
    val date = portrait.date
    var feedback by remember(date) { mutableStateOf(viewModel.portraitFeedback(date)) }
    var reasonPicked by remember(date) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(PORTRAIT_COPY_FEEDBACK_QUESTION, style = MaterialTheme.typography.bodyMedium)
        if (feedback == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    viewModel.recordPortraitFeedback(date, helpful = true)
                    feedback = true
                }) { Text(PORTRAIT_COPY_FEEDBACK_LIKE) }
                OutlinedButton(onClick = {
                    viewModel.recordPortraitFeedback(date, helpful = false)
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
                                    androidx.compose.material3.AssistChip(
                                        onClick = {
                                            reasonPicked = true
                                            viewModel.recordPortraitCorrection(date, reason, portrait.summary)
                                        },
                                        label = { Text(reason) }
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { viewModel.rebuildTodayPortrait() },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(PORTRAIT_COPY_REGENERATE)
                }
            }
        }
    }
}
