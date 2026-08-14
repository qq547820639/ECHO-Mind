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
import com.yunjue.echo.mind.data.PresenceRepository
import com.yunjue.echo.mind.data.SkillRepository
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.actions.InterventionInputs
import com.yunjue.echo.mind.actions.InterventionLevel
import com.yunjue.echo.mind.actions.InterventionPolicy
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.EvidenceAssembler
import com.yunjue.echo.mind.intelligence.EvidenceItem
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.memory.CORRECTION_REASONS
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_COPY_DIMENSIONS_TITLE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_BASELINE_UNLOCKED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_NOT_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_QUESTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_SAVED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_GO_SKILLS
import com.yunjue.echo.mind.model.PORTRAIT_COPY_GO_TREND
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOAD_FAILED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_OFFLINE_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_PARTIAL_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REENABLE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REGENERATE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_RETRY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_ACTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_WHY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SENSING_DISABLED
import com.yunjue.echo.mind.model.PORTRAIT_DIMENSIONS
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.model.baselineProgressText
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.todayCoveragePercent
import com.yunjue.echo.mind.model.dimensionValueText
import com.yunjue.echo.mind.model.todayPortraitStateText
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_BODY
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_TITLE
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.echoMaturity
import com.yunjue.echo.mind.presence.presenceSeedRuntimeText
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 「今天」主界面（Milestone F：Portrait first）。
 *
 * 布局（spec）：日期标题（今天 + M月d日）→「今天的你」headline chips → summary 段落 →
 * 「和你的平常相比」dimensions 对照表 →「为什么这么说？」可展开区（facts）→
 * 「过去 7 天 →」入口（切 Trend tab）→ 底部「想做点什么？」（Skill 降级：默认只显示
 * 跳转「能力」Tab 按钮，点开才渲染 Skill 卡片列表）→ 底部用户反馈（仅 READY/PARTIAL_DATA）。
 *
 * 九态状态机（[PortraitStatus]）：文案统一来自 [todayPortraitStateText]（单测锚点），
 * 禁止在本文件另行硬编码状态文案；禁止统一显示「暂无数据」。
 *
 * Skill 不再占据主体（降级为「想做点什么？」可展开区），「开始」按钮仍走
 * [SkillCardHost] + [SkillSessionCoordinator]（真实执行行为保留）。
 */
@Composable
fun TodayScreen(
    container: AppContainer,
    onGoToSkills: () -> Unit,
    onGoToTrend: () -> Unit,
    onEmergency: () -> Unit,
    onReEnableSensing: () -> Unit
) {
    val portraitRepository = container.portraitRepository
    val preferences = container.preferences
    val presenceRepository = container.presenceRepository
    val memoryRepository = container.memoryRepository
    val aiNarrativeService = container.aiNarrativeService
    val skillRepository = container.skillRepository
    val messageRepository = container.messageRepository
    val coordinator = container.skillSessionCoordinator

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by portraitRepository.observeTodayPortrait().collectAsStateWithLifecycle()
    val presence by presenceRepository.state.collectAsStateWithLifecycle()
    val message by messageRepository.message.collectAsStateWithLifecycle()
    var retryKey by remember { mutableStateOf(0) }

    // ERA 5：AI 今日一句话（fallback 链：AI 叙事 → 确定性叙事 → 观察事实）
    var aiLine by remember { mutableStateOf<AiNarrativeService.NarrativeResult?>(null) }

    // ERA 6：纠错记忆缓存（反馈原因写入 Correction Memory 前读取一次）
    var cachedMemories by remember { mutableStateOf<List<EchoMemory>>(emptyList()) }

    // ERA 7：Ask ECHO 对话层（会话内状态；Memory ≠ 聊天记录，不做跨会话历史）
    var askExpanded by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var exchanges by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var askBusy by remember { mutableStateOf(false) }

    // 缓存优先 → 后台刷新 → 平滑替换；每次进入 Today tab 触发一次
    // （画像 + 周小结 + Presence 状态组装；Presence 是分钟级低频，与渲染帧率无关）
    LaunchedEffect(retryKey) {
        portraitRepository.refreshTodayPortrait(networkAvailable = isNetworkAvailable(context))
        messageRepository.refresh()
        presenceRepository.refresh()
    }

    // 画像就绪后：读取记忆 + 尝试 AI 一句话（失败自动降级，见 AiNarrativeService）
    LaunchedEffect(state.portrait, state.status) {
        cachedMemories = memoryRepository.topMemories(10)
        if (state.status == PortraitStatus.READY || state.status == PortraitStatus.PARTIAL_DATA ||
            state.status == PortraitStatus.OFFLINE_CACHED || state.status == PortraitStatus.EARLY_BASELINE ||
            state.status == PortraitStatus.LOW_CONFIDENCE
        ) {
            val evidence = EvidenceAssembler.fromPortrait(state.portrait) +
                EvidenceAssembler.fromMemories(cachedMemories)
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

    // 日期标题：今天 + M月d日（设备本地时区）
    val todayMd = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日")) }

    // 「想做点什么？」展开状态（Skill 降级：点开才显示卡片列表）
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

        // ERA 1：同步 chip / 本地生成横幅等工程噪音已移出主视觉（收敛至「支持 → 数据与感知」）。

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
                PortraitFullBody(state, aiLine)
            }

            PortraitStatus.PARTIAL_DATA -> {
                item { Text(PORTRAIT_COPY_PARTIAL_BANNER, Modifier.padding(top = 12.dp)) }
                item { PortraitFullBody(state, aiLine) }
            }

            PortraitStatus.OFFLINE_CACHED -> {
                if (state.offline) {
                    item { Text(PORTRAIT_COPY_OFFLINE_BANNER, Modifier.padding(top = 12.dp)) }
                }
                item { PortraitFullBody(state, aiLine) }
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

        // 「过去 7 天 →」入口：切 Trend tab
        item {
            OutlinedButton(onClick = onGoToTrend, modifier = Modifier.fillMaxWidth()) {
                Text(PORTRAIT_COPY_GO_TREND)
            }
        }

        // ===== ERA 9：想做点什么（基础行动免费，Scene 内执行；订阅能力在下方分区） =====
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PORTRAIT_COPY_SECTION_ACTION, style = MaterialTheme.typography.titleMedium)
                // Intervention Policy L2：打开时建议（opt-in + 高置信才出现；L0/L1 不打扰）
                val presenceNow = presence
                val intervention = InterventionPolicy.resolve(
                    InterventionInputs(
                        confidence = presenceNow?.confidence ?: 0f,
                        ambientKnown = presenceNow != null && presenceNow.maturity != EchoMaturity.SEED,
                        suggestionsEnabled = preferences.presenceSuggestionsEnabled,
                        nowMs = System.currentTimeMillis(),
                    )
                )
                if (intervention == InterventionLevel.L2_SUGGEST_WHEN_OPENED) {
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
                Text("更多能力（订阅）", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = onGoToSkills, modifier = Modifier.fillMaxWidth()) {
                    Text(PORTRAIT_COPY_GO_SKILLS)
                }
                TextButton(
                    onClick = { actionExpanded = !actionExpanded },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(if (actionExpanded) "收起能力卡片" else "展开能力卡片")
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
                        onSend = {
                            scope.launch {
                                if (question.isBlank()) return@launch
                                val q = question.trim()
                                question = ""
                                askBusy = true
                                val evidence = EvidenceAssembler.fromPortrait(state.portrait) +
                                    EvidenceAssembler.fromMemories(cachedMemories)
                                val history = exchanges.takeLast(4).flatMap { (qa, aa) ->
                                    listOf(
                                        EvidenceItem(DataSourceCategory.CONVERSATION_HISTORY, "我们的对话", "问：$qa"),
                                        EvidenceItem(DataSourceCategory.CONVERSATION_HISTORY, "我们的对话", "答：$aa"),
                                    )
                                }
                                val result = aiNarrativeService.answerQuestion(q, evidence, history)
                                exchanges = exchanges + (q to result.text)
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

/**
 * ERA 7 — Ask ECHO 对话层（Master Prompt PART 41/42）：
 * 从 ECHO Scene 展开，ECHO 保持可见；价值来自「它知道我的时间上下文」，
 * 不是通用聊天框。会话仅存于内存（Memory ≠ 聊天记录）。
 */
@Composable
private fun AskEchoPanel(
    question: String,
    onQuestionChange: (String) -> Unit,
    exchanges: List<Pair<String, String>>,
    busy: Boolean,
    onSend: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("问 ECHO 关于你的事", style = MaterialTheme.typography.titleSmall)
            Text(
                "适合问：「最近我是不是越来越晚？」「为什么今天 ECHO 看起来不一样？」ECHO 的回答基于你的节律数据，会说明参考了什么。",
                style = MaterialTheme.typography.bodySmall
            )
            exchanges.forEach { (q, a) ->
                Text("你：$q", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("ECHO：$a", style = MaterialTheme.typography.bodyMedium)
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
 * 完整画像主体：
 * 「今天的你」headline chips → summary →「和你的平常相比」dimensions 对照表 →
 * 「为什么这么说？」可展开区（facts）。
 */
@Composable
private fun PortraitFullBody(state: PortraitUiState, aiLine: AiNarrativeService.NarrativeResult?) {
    val portrait = state.portrait ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. 一句话：AI 叙事（过词表校验）优先，否则确定性 headline（fallback 链）
        val line = aiLine?.takeIf { it.level == NarrativeFallbackLevel.AI_NARRATIVE && it.text.isNotBlank() }?.text
        if (line != null) {
            Text(
                line,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            // 依据（Explainable Personal AI：AI 说法的来源可展开，见 FactsSection）
            if (!aiLine.usedSources.isNullOrEmpty()) {
                Text(
                    "依据：${aiLine.usedSources.map { dataSourceLabel(it) }.joinToString("、")}（详见「为什么这么说？」）",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else if (portrait.headline.isNotEmpty()) {
            Text(
                portrait.headline.joinToString(" · "),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // 2. summary 段落
        if (portrait.summary.isNotBlank()) {
            Text(portrait.summary, style = MaterialTheme.typography.bodyLarge)
        }
        // 3. dimensions 对照表（固定维度顺序 + 未知维度追加）
        if (portrait.dimensions.isNotEmpty()) {
            HorizontalDivider()
            Text(PORTRAIT_COPY_DIMENSIONS_TITLE, style = MaterialTheme.typography.titleMedium)
            val orderedKeys = PORTRAIT_DIMENSIONS.filter { it in portrait.dimensions } +
                portrait.dimensions.keys.filter { it !in PORTRAIT_DIMENSIONS }
            orderedKeys.forEach { key ->
                val dim = portrait.dimensions[key] ?: return@forEach
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(dimensionDisplayName(key), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        dimensionValueText(key, dim.value),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        // 4. 「为什么这么说？」可展开区（facts）
        FactsSection(portrait)
    }
}

/** 「为什么这么说？」可展开区：facts 列表（label + 今天/平常/变化对照）。 */
@Composable
private fun FactsSection(portrait: DailyPortraitDto) {
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
        if (portrait.facts.isEmpty()) {
            Text("暂无更多细节。", style = MaterialTheme.typography.bodySmall)
        }
        portrait.facts.forEach { fact ->
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
