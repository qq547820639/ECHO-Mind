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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.MemoryRepository
import com.yunjue.echo.mind.data.PortraitRepository
import com.yunjue.echo.mind.data.MessageRepository
import com.yunjue.echo.mind.data.SkillRepository
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.EvidenceItem
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.intelligence.ReasoningTaskId
import com.yunjue.echo.mind.memory.CORRECTION_REASONS
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.model.PORTRAIT_COPY_BASELINE_UNLOCKED
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
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_ACTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_WHY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SENSING_DISABLED
import com.yunjue.echo.mind.model.PortraitFactDto
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.baselineProgressText
import com.yunjue.echo.mind.model.todayCoveragePercent
import com.yunjue.echo.mind.model.todayPortraitStateText
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_BODY
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_TITLE
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.echoMaturity
import com.yunjue.echo.mind.presence.presenceSeedRuntimeText
import com.yunjue.echo.mind.sensing.SensingRuntimeStatus
import com.yunjue.echo.mind.sensing.sensingRuntimeStatusText
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import com.yunjue.echo.mind.ui.echo.assembleEchoSceneUiState
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * v2 §4/§15：ECHO Scene —— 应用主空间（含义：现在）。
 *
 * 布局：ECHO 生命场（60%+ 主体）→ 一句话（Layer 1）→ 为什么？（Layer 2 证据展开，
 * Layer 3 去 Journey）→ 想做点什么（Scene 内行动 + 订阅能力分区）→ 问 ECHO →
 * 用户反馈（→ Correction Memory）。
 *
 * 状态装配：画像九态 + Presence + 感知六态 + 叙事结果 → [EchoSceneUiState]
 * （[assembleEchoSceneUiState] 纯函数）；UI 只负责渲染，不在 Composable 内拼 repository。
 * 九态文案仍来自 [todayPortraitStateText]（单测锚点）。
 */
@Composable
fun EchoSceneScreen(
    container: AppContainer,
    onGoToJourney: () -> Unit,
    onGoToMe: () -> Unit,
    onEmergency: () -> Unit,
    onReEnableSensing: () -> Unit
) {
    val portraitRepository = container.portraitRepository
    val preferences = container.preferences
    val runtimeCoordinator = container.echoRuntimeCoordinator
    val memoryRepository = container.memoryRepository
    val aiNarrativeService = container.aiNarrativeService
    val skillRepository = container.skillRepository
    val messageRepository = container.messageRepository
    val coordinator = container.skillSessionCoordinator

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by portraitRepository.observeTodayPortrait().collectAsStateWithLifecycle()
    val presence by runtimeCoordinator.presence.collectAsStateWithLifecycle()
    val sensing by runtimeCoordinator.sensing.collectAsStateWithLifecycle()
    val message by messageRepository.message.collectAsStateWithLifecycle()
    var retryKey by remember { mutableStateOf(0) }

    // ERA 5：AI 今日一句话（fallback 链：AI 叙事 → 确定性叙事 → 观察事实）
    var aiLine by remember { mutableStateOf<AiNarrativeService.NarrativeResult?>(null) }

    // ERA 6：纠错记忆缓存（反馈原因写入 Correction Memory 前读取一次）
    var cachedMemories by remember { mutableStateOf<List<EchoMemory>>(emptyList()) }

    // ERA 7：Ask ECHO 对话层（会话内状态；Memory ≠ 聊天记录，不做跨会话历史）
    var askExpanded by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var exchanges by remember { mutableStateOf(listOf<AskExchange>()) }
    var askBusy by remember { mutableStateOf(false) }

    // 缓存优先 → 后台刷新 → 平滑替换；每次进入 ECHO 世界触发一次
    // （画像 + 周小结 + 运行时协调器全量刷新：权限真值 → 六态 + Presence 组装）
    LaunchedEffect(retryKey) {
        portraitRepository.refreshTodayPortrait(networkAvailable = isNetworkAvailable(context))
        messageRepository.refresh()
        runtimeCoordinator.refreshAll()
    }

    // 画像就绪后：读取记忆 + 尝试 AI 一句话（v2 §42：证据由 Context Retriever 按任务策略真实检索）
    LaunchedEffect(state.portrait, state.status) {
        cachedMemories = memoryRepository.topMemories(10)
        if (state.status == PortraitStatus.READY || state.status == PortraitStatus.PARTIAL_DATA ||
            state.status == PortraitStatus.OFFLINE_CACHED || state.status == PortraitStatus.EARLY_BASELINE ||
            state.status == PortraitStatus.LOW_CONFIDENCE
        ) {
            val evidence = container.contextRetriever.retrieve(ReasoningTaskId.GENERATE_NOW_INTERPRETATION)
            val headline = state.portrait?.headline?.joinToString(" · ").orEmpty()
            val summary = state.portrait?.summary.orEmpty()
            val facts = state.portrait?.facts?.firstOrNull()?.let {
                listOfNotNull(it.todayText, it.baselineText).joinToString("；")
            }.orEmpty()
            aiLine = aiNarrativeService.nowNarrative(
                evidence = evidence,
                deterministicText = headline.ifBlank { summary },
                factsText = facts.ifBlank { "ECHO 还在了解今天。" },
            )
        }
    }

    // v2 §16：单一 UI 状态（纯函数装配；UI 层只消费）
    val sceneState = assembleEchoSceneUiState(
        portraitState = state,
        presence = presence,
        sensing = sensing,
        narrative = aiLine,
        intelligenceAvailable = container.aiProviderManager.hasProvider(),
        suggestionsEnabled = preferences.presenceSuggestionsEnabled,
    )

    // 日期标题：今天 + M月d日（设备本地时区）
    val todayMd = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日")) }

    // 「更多能力（订阅）」展开状态（订阅 Skill 卡片列表，默认收起）
    var actionExpanded by remember { mutableStateOf(false) }

    // ERA 9：Scene 内行动（呼吸 / 暂停；覆盖层执行，结束回 Ambient Scene）
    var actionMode by remember { mutableStateOf<EchoActionMode?>(null) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ERA 2：ECHO Scene 生命场（Portrait-first：先看到 ECHO，再看到文字）
        item {
            val motionLevel = when (preferences.presenceMotionLevel) {
                "QUIET" -> PresenceMotionLevel.QUIET
                "LIVELY" -> PresenceMotionLevel.LIVELY
                else -> PresenceMotionLevel.DEFAULT
            }
            val surface = if (preferences.presenceReduceMotion) SurfaceMode.REDUCED_MOTION else SurfaceMode.APP
            EchoLifeField(
                presence = presence,
                modifier = Modifier.fillMaxWidth().height(260.dp),
                surface = surface,
                motionLevel = motionLevel,
                nightMode = preferences.presenceNightMode,
            )
        }

        item { Text("今天 · $todayMd", style = MaterialTheme.typography.headlineMedium) }

        // v2 §35：初次 AI 非阻塞提示（未配置 Provider + 未关闭 → 轻量卡片；不阻塞主界面）
        if (!sceneState.intelligenceAvailable && !preferences.aiPromptDismissed) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("连接一个 AI，让 ECHO 更深入地理解你的变化。", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onGoToMe) { Text("连接 AI") }
                            OutlinedButton(onClick = { preferences.aiPromptDismissed = true }) { Text("以后再说") }
                        }
                    }
                }
            }
        }

        // v2 §12：感知六态是唯一真相——仅非 ACTIVE 需要被看见（信任透明，非工程噪音）
        when (sensing) {
            SensingRuntimeStatus.STARTING -> item {
                Text(sensingRuntimeStatusText(SensingRuntimeStatus.STARTING), style = MaterialTheme.typography.bodySmall)
            }
            SensingRuntimeStatus.SYSTEM_PAUSED -> item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sensingRuntimeStatusText(SensingRuntimeStatus.SYSTEM_PAUSED), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onEmergency) { Text("查看原因") }
                }
            }
            else -> Unit
        }

        // 分析消息（周小结：订阅模式来自服务端 / 本地模式由端侧引擎生成）
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

        // ===== 九态渲染（spec：禁止统一显示「暂无数据」） =====
        when (state.status) {
            PortraitStatus.LOADING -> item {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            }

            PortraitStatus.WARMING_UP -> item {
                val maturity = echoMaturity(state.portrait?.baselineDays ?: 0)
                if (maturity == EchoMaturity.SEED) {
                    // ERA 1：Day-0 SEED ECHO——陪伴从第一秒开始，但不伪造个性判断。
                    SeedPortraitBlock(preferences, state)
                } else {
                    Text(todayPortraitStateText(PortraitStatus.WARMING_UP), Modifier.padding(top = 20.dp))
                    BaselineProgress(state.portrait?.baselineDays ?: 0)
                    // v0.7.4 UX：第 1 天的事实句来自本地画像 summary（端侧引擎已拼入）
                    val factsOnly = state.portrait?.summary
                        ?.substringAfter("\n\n", missingDelimiterValue = "")
                        ?.takeIf { it.isNotBlank() }
                    if (factsOnly != null) {
                        Text(factsOnly, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                    CoverageRow(state.portrait?.coverage)
                }
            }

            // EARLY_BASELINE / LOW_CONFIDENCE：显示当天事实（服务端 summary 即「数据不够完整」文案）
            PortraitStatus.EARLY_BASELINE, PortraitStatus.LOW_CONFIDENCE -> item {
                PortraitSummaryOnly(state)
                BaselineProgress(state.portrait?.baselineDays ?: 0)
                CoverageRow(state.portrait?.coverage)
            }

            PortraitStatus.READY -> item {
                UnlockBanner(portraitRepository, state)
                PortraitFullBody(sceneState, onGoToJourney)
            }

            PortraitStatus.PARTIAL_DATA -> {
                item { Text(PORTRAIT_COPY_PARTIAL_BANNER, Modifier.padding(top = 12.dp)) }
                item { PortraitFullBody(sceneState, onGoToJourney) }
            }

            PortraitStatus.OFFLINE_CACHED -> {
                if (state.offline) {
                    item { Text(PORTRAIT_COPY_OFFLINE_BANNER, Modifier.padding(top = 12.dp)) }
                }
                item { PortraitFullBody(sceneState, onGoToJourney) }
            }

            PortraitStatus.SENSING_DISABLED -> item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(PORTRAIT_COPY_SENSING_DISABLED)
                    Button(
                        onClick = {
                            onReEnableSensing()
                            retryKey++ // 重新开启后立即刷新画像（不再停留 SENSING_DISABLED）
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
                    Button(onClick = { retryKey++ }) { Text(PORTRAIT_COPY_RETRY) }
                }
            }
        }

        // Journey 入口（Layer 3：完整证据/趋势去 Journey 世界）
        item {
            OutlinedButton(onClick = onGoToJourney, modifier = Modifier.fillMaxWidth()) {
                Text("Journey · 我的时间 →")
            }
        }

        // ===== ERA 9：想做点什么（基础行动免费，Scene 内执行；订阅能力在下方分区） =====
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PORTRAIT_COPY_SECTION_ACTION, style = MaterialTheme.typography.titleMedium)
                // Intervention Policy L2：打开时建议（由 EchoSceneUiState 统一装配；L0/L1 不打扰）
                if (sceneState.suggestedAction) {
                    Text(
                        "从今天的数据看，让自己慢一点可能有帮助。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actionMode = EchoActionMode.BREATHING },
                        modifier = Modifier.weight(1f)
                    ) { Text("1 分钟呼吸") }
                    OutlinedButton(
                        onClick = { actionMode = EchoActionMode.PAUSE },
                        modifier = Modifier.weight(1f)
                    ) { Text("短暂离开屏幕") }
                }
                // 「什么也不做」永远是合法选项（产品宪法：不需要喂 ECHO）
                TextButton(
                    onClick = { actionExpanded = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("什么也不做") }
                HorizontalDivider()
                // v2：订阅能力不再占一级 Tab——在此分区展开，或在 Me 访问
                TextButton(
                    onClick = { actionExpanded = !actionExpanded },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(if (actionExpanded) "收起更多能力（订阅）" else "更多能力（订阅）")
                }
            }
        }
        if (actionExpanded) {
            item {
                SkillListSection(skillRepository, coordinator)
            }
        }

        // 底部紧急支持快捷入口（危机入口在 SUPPORT tab 常驻）
        item {
            OutlinedButton(onClick = onEmergency, modifier = Modifier.fillMaxWidth()) { Text("紧急支持") }
        }

        // 用户反馈（仅 READY / PARTIAL_DATA 显示底部）：本地记录，后续版本上报
        if (state.status == PortraitStatus.READY || state.status == PortraitStatus.PARTIAL_DATA) {
            item { PortraitFeedbackRow(portraitRepository, memoryRepository, state) }
        }

        // ===== ERA 7：Ask ECHO（对话层从 Scene 展开；不跳转独立 chat screen） =====
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { askExpanded = !askExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (askExpanded) "收起对话" else "问 ECHO") }
                if (askExpanded) {
                    AskEchoPanel(
                        question = question,
                        onQuestionChange = { question = it },
                        exchanges = exchanges,
                        busy = askBusy,
                        memoryRepository = memoryRepository,
                        onSend = {
                            scope.launch {
                                if (question.isBlank()) return@launch
                                val q = question.trim()
                                question = ""
                                askBusy = true
                                // v2 §42：证据按 ANSWER_PERSONAL_QUESTION 策略真实检索（画像/基线/记忆）
                                val evidence = container.contextRetriever.retrieve(ReasoningTaskId.ANSWER_PERSONAL_QUESTION)
                                val history = exchanges.takeLast(4).flatMap { e ->
                                    listOf(
                                        EvidenceItem(DataSourceCategory.CONVERSATION_HISTORY, "我们的对话", "问：${e.question}"),
                                        EvidenceItem(DataSourceCategory.CONVERSATION_HISTORY, "我们的对话", "答：${e.answer}"),
                                    )
                                }
                                val result = aiNarrativeService.answerQuestion(q, evidence, history)
                                exchanges = exchanges + AskExchange(
                                    question = q,
                                    answer = result.text,
                                    sources = result.usedSources,
                                )
                                askBusy = false
                            }
                        },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }

    // ERA 9：Scene 内行动覆盖层（呼吸/暂停：ECHO 自己执行，结束回 Ambient Scene）
    actionMode?.let { mode ->
        EchoActionOverlay(
            presence = presence,
            mode = mode,
            onDone = { actionMode = null },
        )
    }
    }
}

/** v2 §51/§52：一次问答（问题 + 回答 + 依据来源；回答可反馈 → Correction Memory）。 */
internal data class AskExchange(
    val question: String,
    val answer: String,
    val sources: List<DataSourceCategory>,
)

/**
 * ERA 7 — Ask ECHO 对话层（Master Prompt PART 41/42/48）：
 * 从 ECHO Scene 展开，ECHO 保持可见；价值来自「它知道我的时间上下文」，
 * 不是通用聊天框。会话仅存于内存（Memory ≠ 聊天记录）。
 *
 * v2 §51：每条回答附「依据」——参考了什么 + 没有使用什么（隐私透明度）；
 * v2 §52：每条回答可反馈（像我/不太像 + 原因 → Correction Memory）。
 */
@Composable
private fun AskEchoPanel(
    question: String,
    onQuestionChange: (String) -> Unit,
    exchanges: List<AskExchange>,
    busy: Boolean,
    onSend: () -> Unit,
    memoryRepository: MemoryRepository,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("问 ECHO 关于你的事", style = MaterialTheme.typography.titleSmall)
            Text(
                "适合问：「最近我是不是越来越晚？」「为什么今天 ECHO 看起来不一样？」ECHO 的回答基于你的节律数据，会说明参考了什么。",
                style = MaterialTheme.typography.bodySmall
            )
            exchanges.forEach { exchange ->
                AskExchangeRow(
                    exchange = exchange,
                    memoryRepository = memoryRepository,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = onQuestionChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("问 ECHO…") },
                    singleLine = true,
                    enabled = !busy,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSend, enabled = !busy && question.isNotBlank()) {
                    Text(if (busy) "…" else "发送")
                }
            }
        }
    }
}

/** 单条问答：问题 + 回答 + 依据（参考了/没有使用）+ 反馈（像我/不太像 → Correction Memory）。 */
@Composable
private fun AskExchangeRow(
    exchange: AskExchange,
    memoryRepository: MemoryRepository,
) {
    val scope = rememberCoroutineScope()
    var basisExpanded by remember { mutableStateOf(false) }
    var feedback by remember(exchange) { mutableStateOf<Boolean?>(null) }
    var reasonPicked by remember(exchange) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("你：${exchange.question}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text("ECHO：${exchange.answer}", style = MaterialTheme.typography.bodyMedium)
        // 依据（v2 §51：参考了 / 没有使用 双清单）
        TextButton(onClick = { basisExpanded = !basisExpanded }) {
            Text(if (basisExpanded) "收起依据" else "依据")
        }
        if (basisExpanded) {
            Text(
                "参考了：" + if (exchange.sources.isEmpty()) "（无可追溯来源——请谨慎看待）"
                else exchange.sources.map { dataSourceLabel(it) }.joinToString("、"),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "没有使用：麦克风、通知正文、精确位置",
                style = MaterialTheme.typography.bodySmall
            )
        }
        // 反馈（v2 §52：写入 Correction Memory，不只 analytics）
        if (feedback == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    feedback = true
                    scope.launch {
                        memoryRepository.record(
                            type = MemoryType.CORRECTION,
                            content = "问答反馈：像我（问：${exchange.question.take(40)}）",
                            source = "user-feedback",
                            provenance = "conversation-feedback:v1",
                            confidence = 1f,
                            importance = 60,
                        )
                    }
                }) { Text("像我") }
                TextButton(onClick = {
                    feedback = false
                    reasonPicked = false
                }) { Text("不太像") }
            }
        } else if (feedback == true) {
            Text("已记录，感谢反馈。", style = MaterialTheme.typography.bodySmall)
        } else if (!reasonPicked) {
            Text("哪里不太对？", style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CORRECTION_REASONS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { reason ->
                            androidx.compose.material3.AssistChip(
                                onClick = {
                                    reasonPicked = true
                                    scope.launch {
                                        memoryRepository.recordCorrection(
                                            date = LocalDate.now().toString(),
                                            reason = reason,
                                            originalStatement = exchange.answer,
                                        )
                                    }
                                },
                                label = { Text(reason) }
                            )
                        }
                    }
                }
            }
        } else {
            Text("知道了，我会少一点依赖这种判断。", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

/** EARLY_BASELINE / LOW_CONFIDENCE：仅渲染服务端 summary（当天事实/数据不够完整文案）。 */
@Composable
private fun PortraitSummaryOnly(state: PortraitUiState) {
    val portrait = state.portrait
    if (portrait == null) return
    Text(portrait.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 20.dp))
}

/**
 * ERA 1：Day-0 SEED ECHO（Master Prompt PART 65）。
 *
 * 首日画报 = 存在与陪伴的表达（初见 / 已观察 N 分钟 / 当天事实），
 * 不是画像输出——绝不伪造个性判断，也不与 baseline 比较。
 * 「已观察 N 分钟」以 [AppPreferences.awakenedAtEpochMs] 为锚点；
 * 无锚点（老用户/abstain 路径）时不显示该行，也不显示进度条。
 */
@Composable
private fun SeedPortraitBlock(preferences: AppPreferences, state: PortraitUiState) {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(PRESENCE_COPY_SEED_TITLE, style = MaterialTheme.typography.headlineMedium)
        Text(PRESENCE_COPY_SEED_BODY, style = MaterialTheme.typography.bodyLarge)
        // 第 0 天的真实事实句（端侧引擎已拼入 summary；无则省略）
        val factsOnly = state.portrait?.summary
            ?.substringAfter("\n\n", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        if (factsOnly != null) {
            Text(factsOnly, style = MaterialTheme.typography.bodyMedium)
        }
        val observedMinutes = preferences.awakenedAtEpochMs
            .takeIf { it > 0L }
            ?.let { (System.currentTimeMillis() - it) / 60_000L }
        if (observedMinutes != null) {
            Text(
                presenceSeedRuntimeText(observedMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 基线积累进度（v0.7 UX：X/7 天 + 进度条；天数 clamp 0..7）。 */
@Composable
private fun BaselineProgress(baselineDays: Int) {
    val progress = baselineDays.coerceIn(0, 7) / 7f
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(baselineProgressText(baselineDays), style = MaterialTheme.typography.bodySmall)
    }
}

/** 今日数据覆盖率（v0.7.4 UX：让"被动感知"可感知；无数据/非法 → 不渲染）。 */
@Composable
private fun CoverageRow(coverage: Map<String, Any>?) {
    val pct = todayCoveragePercent(coverage) ?: return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("今日已学习", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(
            progress = { pct / 100f },
            modifier = Modifier.weight(1f)
        )
        Text("$pct%", style = MaterialTheme.typography.bodySmall)
    }
}

/** 基线解锁仪式（v0.7.4：首次 READY 只出现一次）。 */
@Composable
private fun UnlockBanner(portraitRepository: PortraitRepository, state: PortraitUiState) {
    if ((state.portrait?.baselineDays ?: 0) < 7) return
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(state.status) {
        if (state.status == PortraitStatus.READY && portraitRepository.consumeBaselineUnlocked()) {
            show = true
        }
    }
    if (show) {
        Card(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                PORTRAIT_COPY_BASELINE_UNLOCKED,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * v2 §46/§47：完整画像主体 —— Progressive Explanation 三层。
 *
 * Layer 1：一句话（由 [EchoSceneUiState.headline] 统一装配：AI 叙事 → 确定性 → 观察事实）；
 * Layer 2：点「为什么？」→ Scene 内展开人类可读 facts（今天/平常/变化对照）；
 * Layer 3：「查看更多」→ Journey（完整趋势与定量证据；不在此暴露 z_score/coverage 等工程值）。
 */
@Composable
private fun PortraitFullBody(sceneState: EchoSceneUiState, onGoToJourney: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Layer 1：一句话（装配结果；AI 层附依据行）
        Text(
            sceneState.headline,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        if (sceneState.headlineLevel == NarrativeFallbackLevel.AI_NARRATIVE &&
            sceneState.headlineSources.isNotEmpty()
        ) {
            Text(
                "依据：${sceneState.headlineSources.map { dataSourceLabel(it) }.joinToString("、")}（点「为什么？」看事实）",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!sceneState.intelligenceAvailable) {
            Text(
                "连接 AI 后可获得更深入的解释。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Layer 2：为什么？（Scene 内展开，不切详情页）
        FactsSection(sceneState.facts)

        // Layer 3：查看更多 → Journey（定量证据与长期趋势）
        OutlinedButton(onClick = onGoToJourney, modifier = Modifier.fillMaxWidth()) {
            Text("查看更多 → Journey")
        }
    }
}

/** 「为什么？」可展开区：facts 列表（label + 今天/平常/变化对照；Layer 2）。 */
@Composable
private fun FactsSection(facts: List<PortraitFactDto>) {
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(PORTRAIT_COPY_SECTION_WHY, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "展开")
        }
    }
    if (expanded) {
        if (facts.isEmpty()) {
            Text("暂无更多细节。", style = MaterialTheme.typography.bodySmall)
        }
        facts.forEach { fact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (fact.label.isNotBlank()) {
                        Text(fact.label, style = MaterialTheme.typography.titleSmall)
                    }
                    if (fact.todayText.isNotBlank()) {
                        Text("今天：${fact.todayText}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (fact.baselineText.isNotBlank()) {
                        Text("平常：${fact.baselineText}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (fact.deltaText.isNotBlank()) {
                        Text("变化：${fact.deltaText}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * ERA 6 — 用户反馈：「这个描述像今天的你吗？」[挺像] [不太像]。
 * 「不太像」→ 快速原因（工作/旅行/假期/…）→ 写入 Correction Memory
 * （prediction ≠ user feedback + 原因；之后推理可检索——Personalization Feedback Loop）。
 * 反馈本身仍走既有 Outbox 上报（/v1/me/portraits/feedback）。
 */
@Composable
private fun PortraitFeedbackRow(
    portraitRepository: PortraitRepository,
    memoryRepository: MemoryRepository,
    state: PortraitUiState
) {
    val portrait = state.portrait ?: return
    val date = portrait.date
    val scope = rememberCoroutineScope()
    var feedback by remember(date) { mutableStateOf(portraitRepository.portraitFeedback(date)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(PORTRAIT_COPY_FEEDBACK_QUESTION, style = MaterialTheme.typography.bodyMedium)
        if (feedback == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    // Phase 6.6：反馈本地记录 + 入 Outbox（可靠同步），UI 即时反馈
                    scope.launch { portraitRepository.recordPortraitFeedback(date, helpful = true) }
                    feedback = true
                }) { Text(PORTRAIT_COPY_FEEDBACK_LIKE) }
                OutlinedButton(onClick = {
                    scope.launch { portraitRepository.recordPortraitFeedback(date, helpful = false) }
                    feedback = false
                }) { Text(PORTRAIT_COPY_FEEDBACK_NOT_LIKE) }
            }
        } else {
            Text(PORTRAIT_COPY_FEEDBACK_SAVED, style = MaterialTheme.typography.bodySmall)
            // v0.7 反馈闭环：用户点了「不太像」→ 提供「重新生成」入口
            // （订阅走 /me/portraits/rebuild；本地模式端侧重算）
            if (feedback == false) {
                // ERA 6：快速原因 → Correction Memory（Personalization Feedback Loop）
                var reasonPicked by remember { mutableStateOf(false) }
                if (!reasonPicked) {
                    Text("今天有什么不一样？", style = MaterialTheme.typography.bodySmall)
                    // 快速原因 chips（两行流式排布）
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CORRECTION_REASONS.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { reason ->
                                    androidx.compose.material3.AssistChip(
                                        onClick = {
                                            reasonPicked = true
                                            scope.launch {
                                                memoryRepository.recordCorrection(
                                                    date = date,
                                                    reason = reason,
                                                    originalStatement = portrait.summary,
                                                )
                                            }
                                        },
                                        label = { Text(reason) }
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { scope.launch { portraitRepository.rebuildTodayPortrait() } },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(PORTRAIT_COPY_REGENERATE)
                }
            }
        }
    }
}

/** 数据源类别 → 用户可读标签（「依据」展示，不泄露内部名）。 */
private fun dataSourceLabel(category: DataSourceCategory): String = when (category) {
    DataSourceCategory.TODAY_AGGREGATE -> "今天的活动节律"
    DataSourceCategory.BASELINE -> "个人基线"
    DataSourceCategory.PORTRAIT_HISTORY -> "历史画像"
    DataSourceCategory.CONTEXT_EXCEPTIONS -> "你告诉我的特殊日期"
    DataSourceCategory.USER_CORRECTIONS -> "你纠正过我的"
    DataSourceCategory.PREFERENCES -> "你的偏好"
    DataSourceCategory.CONVERSATION_HISTORY -> "我们的对话"
    else -> "其他"
}

/**
 * 「想做点什么？」Skill 卡片区（降级：仅在用户点开「展开能力卡片」后渲染）。
 * 复用 [rememberSkillList] 三态（加载中 / 加载失败 / 空态冷启动 / 列表），
 * 每个「开始」按钮走 [SkillCardHost] 真实执行行为。
 */
@Composable
private fun SkillListSection(skillRepository: SkillRepository, coordinator: SkillSessionCoordinator) {
    val (skillState, retry) = rememberSkillList(skillRepository)
    when {
        skillState.loadFailed -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.cold_start_load_failed))
            Spacer(Modifier.height(12.dp))
            Button(onClick = retry) { Text(stringResource(R.string.cold_start_retry)) }
        }
        skillState.skills == null -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
        skillState.skills.isEmpty() -> {
            val stage = skillState.coldStartHint ?: "stage_0"
            val resId = coldStartHint(stage, skillState.observationDays)
            Text(
                if (stage == "stage_1_3") stringResource(resId, skillState.observationDays)
                else stringResource(resId)
            )
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            skillState.skills.forEach { skill ->
                SkillCardHost(skill, coordinator)
            }
        }
    }
}
