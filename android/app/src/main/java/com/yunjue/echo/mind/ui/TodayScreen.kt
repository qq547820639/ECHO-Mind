package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.PortraitRepository
import com.yunjue.echo.mind.data.MessageRepository
import com.yunjue.echo.mind.data.SkillRepository
import com.yunjue.echo.mind.data.SyncStateRepository
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.data.mapSyncState
import com.yunjue.echo.mind.data.syncStateText
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_COPY_DIMENSIONS_TITLE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_NOT_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_QUESTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_SAVED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_GO_SKILLS
import com.yunjue.echo.mind.model.PORTRAIT_COPY_GO_TREND
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOAD_FAILED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOCAL_BANNER
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
import com.yunjue.echo.mind.model.SyncState
import com.yunjue.echo.mind.model.baselineProgressText
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.dimensionValueText
import com.yunjue.echo.mind.model.todayPortraitStateText
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
    portraitRepository: PortraitRepository,
    syncStateRepository: SyncStateRepository,
    skillRepository: SkillRepository,
    messageRepository: MessageRepository,
    coordinator: SkillSessionCoordinator,
    onGoToSkills: () -> Unit,
    onGoToTrend: () -> Unit,
    onEmergency: () -> Unit,
    onReEnableSensing: () -> Unit
) {
    val context = LocalContext.current
    val state by portraitRepository.observeTodayPortrait().collectAsStateWithLifecycle()
    val message by messageRepository.message.collectAsStateWithLifecycle()
    var retryKey by remember { mutableStateOf(0) }

    // 缓存优先 → 后台刷新 → 平滑替换；每次进入 Today tab 触发一次（画像 + 周小结）
    LaunchedEffect(retryKey) {
        portraitRepository.refreshTodayPortrait(networkAvailable = isNetworkAvailable(context))
        messageRepository.refresh()
    }

    // 同步状态 chip（沿用既有行为，PRD 契约点 9）
    val pending by syncStateRepository.observePendingCount().collectAsStateWithLifecycle(initialValue = 0)
    val syncState = mapSyncState(
        pendingCount = pending,
        networkAvailable = isNetworkAvailable(context),
        deadLetterCount = syncStateRepository.deadLetterCount(),
        authBlocked = syncStateRepository.isAuthBlocked(),
        consentBlocked = syncStateRepository.lastSyncErrorClass() == "consent",
        retrying = syncStateRepository.lastSyncErrorClass() == "retryable"
    )
    val syncLabel = syncStateText(syncState, pending)

    // 日期标题：今天 + M月d日（设备本地时区）
    val todayMd = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日")) }

    // 「想做点什么？」展开状态（Skill 降级：点开才显示卡片列表）
    var actionExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("今天 · $todayMd", style = MaterialTheme.typography.headlineMedium) }
        item {
            // 同步状态文案 + 计数（已同步时不打扰）
            if (pending > 0 || syncState != SyncState.SYNCED) {
                AssistChip(onClick = { SyncWorker.enqueue(context) }, label = { Text(syncLabel) })
            }
        }

        // 端侧本地生成横幅（演示模式/离线回退：画像由本机数据计算，非服务端缓存）
        if (state.localComputed) {
            item { Text(PORTRAIT_COPY_LOCAL_BANNER, Modifier.padding(top = 8.dp)) }
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
                Text(todayPortraitStateText(PortraitStatus.WARMING_UP), Modifier.padding(top = 20.dp))
                BaselineProgress(state.portrait?.baselineDays ?: 0)
            }

            // EARLY_BASELINE / LOW_CONFIDENCE：显示当天事实（服务端 summary 即「数据不够完整」文案）
            PortraitStatus.EARLY_BASELINE, PortraitStatus.LOW_CONFIDENCE -> item {
                PortraitSummaryOnly(state)
                BaselineProgress(state.portrait?.baselineDays ?: 0)
            }

            PortraitStatus.READY -> item {
                PortraitFullBody(state)
            }

            PortraitStatus.PARTIAL_DATA -> {
                item { Text(PORTRAIT_COPY_PARTIAL_BANNER, Modifier.padding(top = 12.dp)) }
                item { PortraitFullBody(state) }
            }

            PortraitStatus.OFFLINE_CACHED -> {
                if (state.offline) {
                    item { Text(PORTRAIT_COPY_OFFLINE_BANNER, Modifier.padding(top = 12.dp)) }
                }
                item { PortraitFullBody(state) }
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

        // 底部「想做点什么？」：Skill 降级（默认只显示跳转按钮，点开才渲染卡片列表）
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PORTRAIT_COPY_SECTION_ACTION, style = MaterialTheme.typography.titleMedium)
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
            item { PortraitFeedbackRow(portraitRepository, state) }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** EARLY_BASELINE / LOW_CONFIDENCE：仅渲染服务端 summary（当天事实/数据不够完整文案）。 */
@Composable
private fun PortraitSummaryOnly(state: PortraitUiState) {
    val portrait = state.portrait
    if (portrait == null) return
    Text(portrait.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 20.dp))
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

/**
 * 完整画像主体：
 * 「今天的你」headline chips → summary →「和你的平常相比」dimensions 对照表 →
 * 「为什么这么说？」可展开区（facts）。
 */
@Composable
private fun PortraitFullBody(state: PortraitUiState) {
    val portrait = state.portrait ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. headline 标签（Phase 6.5：非交互 semantic 组件——不用 AssistChip(onClick={}) 假交互；
        //    无 onClick/focusable，不进入焦点顺序；contentDescription 即标签文本供 TalkBack 朗读）
        if (portrait.headline.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                portrait.headline.forEach { headline ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = headline
                        }
                    ) {
                        Text(
                            headline,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
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
 * 用户反馈（Milestone F）：「这个描述像今天的你吗？」[挺像] [不太像]。
 * 点击后仅本地记录（portrait_id=date + 是否像），不新增网络请求；
 * 后续版本按 date 上报 /v1/portraits/{date}/feedback（后端当前无此端点）。
 */
@Composable
private fun PortraitFeedbackRow(portraitRepository: PortraitRepository, state: PortraitUiState) {
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
