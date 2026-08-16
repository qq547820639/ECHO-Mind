package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyPeriod
import com.yunjue.echo.mind.journey.JourneyRiverSegment
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.TrendNoDataReason
import com.yunjue.echo.mind.journey.TrendUiState
import com.yunjue.echo.mind.journey.journeySegmentKindLabel
import com.yunjue.echo.mind.journey.trendNoDataReasonText
import com.yunjue.echo.mind.presencevisual.drawOrganism
import com.yunjue.echo.mind.ui.Page
import com.yunjue.echo.mind.ui.TREND_DISCLAIMER
import com.yunjue.echo.mind.ui.batteryOptimizationSettingsIntent
import com.yunjue.echo.mind.ui.formatTimestamp
import java.time.LocalDate

/**
 * ERA 13 §27 — JourneyScreen 最终职责：Scale selector / Visual Memory River /
 * Selected period / Narrative / Evidence。
 *
 * 无 Repository / AI / Preferences / ContextRetriever 直接持有；无 LaunchedEffect 业务编排；
 * 全部经 JourneyViewModel（uiState + onEvent）。
 *
 * ERA 32：状态提升 —— JourneyScreen 只做 collect + 路由 ViewModel；
 * 纯渲染在 JourneyScreenContent（state-in / event-out），Compose smoke test 直接注入状态。
 */
@Composable
fun JourneyScreen(
    viewModel: JourneyViewModel,
    onGoToSupport: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    JourneyScreenContent(
        state = state,
        onEvent = viewModel::onEvent,
        feedback = viewModel::feedback,
        onGoToSupport = onGoToSupport,
    )
}

/**
 * ERA 32 — JourneyScreen 纯状态内容（state-in / event-out）。
 * 只消费 [JourneyUiState]，交互以 [JourneyEvent] 与回调输出；
 * 不持有 ViewModel / Repository / Context 业务编排（Context 仅用于系统设置深链按钮）。
 */
@Composable
fun JourneyScreenContent(
    state: JourneyUiState,
    onEvent: (JourneyEvent) -> Unit,
    feedback: (String) -> Boolean?,
    onGoToSupport: () -> Unit = {},
) {
    val context = LocalContext.current

    Page("旅程 · 我的时间") {
        // V3 §55：时间尺度选择器（日/周/月/季/年）——quiet text tab：
        // selected = 全 alpha + 2dp underline + 微光；不做 FilterChip container。
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()).testTag("journey_scale_selector"),
        ) {
            JourneyScale.entries.forEach { s ->
                val selected = state.selectedScale == s
                Column(
                    Modifier
                        .selectable(
                            selected = selected,
                            onClick = { onEvent(JourneyEvent.SelectScale(s)) },
                            role = androidx.compose.ui.semantics.Role.Tab,
                        )
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        scaleLabel(s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.52f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(2.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else androidx.compose.ui.graphics.Color.Transparent,
                            ),
                    )
                }
            }
        }

        when (state.trendState) {
            TrendUiState.LOADING -> {
                // §40：quiet loading（无 spinner 主视觉）
                Text(
                    "正在整理你的时间…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                )
            }
            TrendUiState.PERMISSION_DISABLED -> {
                Text("被动感知已关闭或权限被撤，无法获取新的旅程数据。")
                // ERA 32 R26：修复恢复路径——此前一律跳系统设置，但感知关闭/同意撤回
                // 在系统设置页无法解决；正确入口是支持页（数据与感知开关）。
                OutlinedButton(onClick = onGoToSupport) { Text("前往支持页重新开启") }
            }
            TrendUiState.ERROR -> {
                Text("旅程加载失败")
                Button(onClick = { onEvent(JourneyEvent.Refresh) }) { Text("重试") }
            }
            TrendUiState.NO_DATA -> {
                NoDataContent(state = state, onGoToSupport = onGoToSupport)
            }
            TrendUiState.OFFLINE_CACHED -> {
                Text("当前离线，以下为缓存的旅程。")
                JourneyContent(state = state, onEvent = onEvent, feedback = feedback)
            }
            TrendUiState.FRESH, TrendUiState.PARTIAL ->
                JourneyContent(state = state, onEvent = onEvent, feedback = feedback)
        }

        // 契约点 2 固定免责文案（单测锚点）——ERA 31 BATCH 4：移到页面底部安静呈现，
        // 第一视觉留给视觉记忆河流（§30「看见自己的时间」，不是先读法律文案）。
        HorizontalDivider()
        Text(TREND_DISCLAIMER, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NoDataContent(state: JourneyUiState, onGoToSupport: () -> Unit) {
    val context = LocalContext.current
    Text(trendNoDataReasonText(state.noDataReason))
    Text("最近成功采集：${formatTimestamp(state.syncStatus.lastCollectedAt)}")
    Text("最近成功同步：${formatTimestamp(state.syncStatus.lastSyncedAt)}")
    when (state.noDataReason) {
        TrendNoDataReason.CLOSED -> OutlinedButton(onClick = onGoToSupport) {
            Text("前往支持页重新开启")
        }
        TrendNoDataReason.SYSTEM_BACKGROUND -> OutlinedButton(onClick = {
            runCatching { context.startActivity(batteryOptimizationSettingsIntent(context)) }
        }) {
            Text("前往系统设置")
        }
        else -> Unit
    }
}

/**
 * v3 §26 — Journey 主体：视觉记忆河流（第一视觉）→ 长期叙事（AI → 确定性综述 fallback）→
 * 「查看依据」Evidence Layer（定量图表降级到第二层，不在第一视觉）。
 */
@Composable
private fun JourneyContent(
    state: JourneyUiState,
    onEvent: (JourneyEvent) -> Unit,
    feedback: (String) -> Boolean?,
) {
    // 1. 视觉记忆河流（第一视觉）
    VisualMemoryRiver(
        scale = state.selectedScale,
        days = state.visualDays,
        periods = state.visualPeriods,
        segments = state.riverSegments,
        seed = state.journeySeed,
        feedback = feedback,
        selectedDate = state.selectedDay?.date,
        onSelectDay = { date -> onEvent(JourneyEvent.SelectDay(date)) },
    )

    // 2. 长期叙事（变化发生在叙事里，图表只是依据）
    val narrative = state.narrative
    if (narrative != null) {
        val portraits = state.timeline.portraits
        Text(
            if (narrative.result.text.isBlank()) com.yunjue.echo.mind.journey.journeyNaturalSummary(portraits)
            else narrative.result.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp)
        )
        if (narrative.result.usedSources.isNotEmpty()) {
            Text(
                "依据：${narrative.result.usedSources.joinToString("、") { dataSourceLabelForJourney(it) }}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (narrative.contextExceptions.isNotEmpty()) {
            Text(
                "你告诉我的特殊日期：${narrative.contextExceptions.joinToString("、")}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    // 2b. §41 90 天测试（ERA 32 R06）：期间故事（什么时候变化最明显 + 大致经历了什么）
    //     与「现在 vs 一个月前」——安静的第二叙事层，不抢河流第一视觉。
    if (state.periodStory.isNotBlank()) {
        Text(
            state.periodStory,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    state.monthAgoLines.forEach { line ->
        Text(
            "现在和一个月前：$line",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 3. ERA 16 §84-§87 — Journey 长期记忆（年视图 / 阶段解释 / 历史重建）
    //    ERA 31 R27：§85 河段行并入主河流（聚合格直接标注「平稳时期/节律漂移/…」），
    //    一条河流同时是时间线与故事——第二条河流行移除（§10 信息压缩）。
    SeasonExplanationSection(lines = state.seasonExplanation)
    YearViewSection(state = state, seed = state.journeySeed)
    HistoricalReconstructionSection(
        day = state.selectedDay,
        canonical = state.selectedCanonical,
        fallbackSeed = state.journeySeed,
        explanation = state.selectedDayExplanation,
    )

    // 4. Evidence Layer：查看依据（定量证据，非第一视觉）
    HorizontalDivider()
    TextButton(onClick = { onEvent(JourneyEvent.ToggleEvidence) }) {
        Text(if (state.showEvidence) "收起依据" else "查看依据")
    }
    if (state.showEvidence) {
        JourneyEvidenceView(
            timeline = state.timeline,
            lastCollectionTs = state.syncStatus.lastCollectedAt,
            lastSyncTs = state.syncStatus.lastSyncedAt,
            feedback = feedback,
        )
    }
}

/** 依据数据源 → 用户可读标签（Journey 版；与 Today 的 dataSourceLabel 语义一致）。 */
private fun dataSourceLabelForJourney(category: DataSourceCategory): String = when (category) {
    DataSourceCategory.PORTRAIT_HISTORY -> "历史画像"
    DataSourceCategory.BASELINE -> "个人基线"
    DataSourceCategory.CONTEXT_EXCEPTIONS -> "你告诉我的特殊日期"
    DataSourceCategory.USER_CORRECTIONS -> "你纠正过我的"
    else -> "其他"
}

private fun scaleLabel(scale: JourneyScale): String = when (scale) {
    JourneyScale.DAY -> "天"
    JourneyScale.WEEK -> "周"
    JourneyScale.MONTH -> "月"
    JourneyScale.SEASON -> "季"
    JourneyScale.YEAR -> "年"
}

/**
 * V3 §56–§59 — 视觉记忆主体验：
 * DAY = Memory River（时间河滚动，非 Card list）；WEEK = 7 个确定性日肖像 gentle arc；
 * MONTH = 7 列日历星座；SEASON/YEAR = 既有年视图/季解释 section（representative canonical portrait）。
 * 全部经 production renderer（genome → OrganismFrameComputer）；无数据 = quiet ring（不 X/不 warning）。
 */
@Composable
private fun VisualMemoryRiver(
    scale: JourneyScale,
    days: List<JourneyDay>,
    periods: List<JourneyPeriod>,
    segments: List<JourneyRiverSegment>,
    seed: Long,
    feedback: (String) -> Boolean?,
    selectedDate: String?,
    onSelectDay: (String) -> Unit,
) {
    when (scale) {
        JourneyScale.DAY -> MemoryRiver(
            days = days,
            selectedDate = selectedDate,
            feedback = feedback,
            onSelectDay = onSelectDay,
        )
        JourneyScale.WEEK -> WeekArc(
            days = days,
            selectedDate = selectedDate,
            onSelectDay = onSelectDay,
        )
        JourneyScale.MONTH -> MonthConstellation(
            days = days,
            selectedDate = selectedDate,
            onSelectDay = onSelectDay,
        )
        else -> {
            // SEASON / YEAR：聚合格（3/12 月簇由 YearViewSection/SeasonExplanationSection 承载）
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                periods.forEachIndexed { index, period ->
                    val span = period.days.firstOrNull()?.date?.take(7) ?: ""
                    val last = period.days.lastOrNull()?.date?.take(7) ?: ""
                    val baseLabel = if (span.isNotBlank()) "$span…$last" else "第 ${index + 1} 段"
                    val kindLabel = period.days.firstOrNull()?.date
                        ?.let { journeySegmentKindLabel(it, segments) }
                    JourneyAggregateCell(
                        period = period,
                        seed = seed,
                        label = baseLabel + (kindLabel?.let { " · $it" } ?: ""),
                        large = false,
                    )
                }
            }
        }
    }
}

/** §56 Memory River：item 168dp；selected 148dp；邻近 92/68/52；alpha 1.00/.70/.48/.32。 */
@Composable
private fun MemoryRiver(
    days: List<JourneyDay>,
    selectedDate: String?,
    feedback: (String) -> Boolean?,
    onSelectDay: (String) -> Unit,
) {
    val sorted = days.sortedByDescending { it.date }
    val selectedIndex = sorted.indexOfFirst { it.date == selectedDate }
        .takeIf { it >= 0 } ?: 0
    LazyColumn(
        Modifier.fillMaxWidth().height(420.dp).testTag("journey_memory_visual"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(sorted.size) { index ->
            val day = sorted[index]
            val distance = kotlin.math.abs(index - selectedIndex)
            val portraitSize = when (distance) {
                0 -> 148.dp
                1 -> 92.dp
                2 -> 68.dp
                else -> 52.dp
            }
            val alpha = when (distance) {
                0 -> 1.00f
                1 -> 0.70f
                2 -> 0.48f
                else -> 0.32f
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .clickable { onSelectDay(day.date) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(148.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    JourneyPortrait(day = day, size = portraitSize, alpha = alpha)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        day.date,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f + 0.48f * alpha),
                    )
                    val line = day.headline.ifBlank { day.summary }.take(60)
                    if (line.isNotBlank()) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f + 0.50f * alpha),
                        )
                    }
                    feedback(day.date)?.let {
                        Text(
                            if (it) "你觉得像" else "你觉得不太像",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                        )
                    }
                }
            }
        }
    }
}

/** §57 Week：7 个确定性日肖像 gentle arc（选中放大；点击显示当日 narrative）。 */
@Composable
private fun WeekArc(
    days: List<JourneyDay>,
    selectedDate: String?,
    onSelectDay: (String) -> Unit,
) {
    val latest = days.maxOfOrNull { it.date }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()
    val weekDays = (0 until 7).map { latest.minusDays((7 - 1 - it).toLong()) }
    Row(
        Modifier.fillMaxWidth().testTag("journey_memory_visual"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weekDays.forEachIndexed { index, day ->
            val journeyDay = days.firstOrNull { it.date == day.toString() }
            val selected = day.toString() == selectedDate
            // gentle arc：中间略抬升（确定性几何，非折线图）
            val lift = 10.dp * kotlin.math.sin((index + 0.5f) / 7f * Math.PI).toFloat()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .offset(y = -lift)
                    .clickable { onSelectDay(day.toString()) },
            ) {
                JourneyPortrait(
                    day = journeyDay,
                    size = if (selected) 72.dp else 52.dp,
                    alpha = if (selected) 1f else 0.72f,
                )
                Text(
                    "${day.monthValue}/${day.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.52f),
                )
            }
        }
    }
}

/** §58 Month：7 列日历星座；mini portrait 36dp / selected 56dp；无数据 = quiet ring。 */
@Composable
private fun MonthConstellation(
    days: List<JourneyDay>,
    selectedDate: String?,
    onSelectDay: (String) -> Unit,
) {
    val latest = days.maxOfOrNull { it.date }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()
    val monthDays = (0 until 28).map { latest.minusDays((28 - 1 - it).toLong()) }
    Column(Modifier.fillMaxWidth().testTag("journey_memory_visual")) {
        monthDays.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                week.forEach { day ->
                    val journeyDay = days.firstOrNull { it.date == day.toString() }
                    val selected = day.toString() == selectedDate
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectDay(day.toString()) }
                            .padding(vertical = 4.dp),
                    ) {
                        JourneyPortrait(
                            day = journeyDay,
                            size = if (selected) 56.dp else 36.dp,
                            alpha = if (selected) 1f else 0.8f,
                        )
                        Text(
                            "${day.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (selected) 1f else 0.52f,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 单日确定性肖像（production renderer；无 genome = quiet ring 占位，不编造）。 */
@Composable
private fun JourneyPortrait(
    day: JourneyDay?,
    size: androidx.compose.ui.unit.Dp,
    alpha: Float,
) {
    val quietRing = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    Canvas(Modifier.size(size).alpha(alpha)) {
        val genome = day?.genome
        if (genome != null) {
            val frame = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                    genome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                    JOURNEY_CANONICAL_TIME_SECONDS,
                ),
                width = this.size.width, height = this.size.height,
            )
            drawOrganism(frame)
        } else {
            // §58：无数据 = quiet ring（不 X / 不 warning）
            drawCircle(
                color = quietRing,
                radius = this.size.minDimension * 0.30f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = this.size.minDimension * 0.03f),
            )
        }
    }
}

/** 周/月聚合帧（SEASON/YEAR 尺度保留；参数由 JourneyPeriod 预装配）。 */
@Composable
private fun JourneyAggregateCell(
    period: JourneyPeriod,
    seed: Long,
    label: String,
    large: Boolean = false,
) {
    val cellSize = if (large) 140.dp else 72.dp
    val quietRing = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(cellSize)) {
            val aggGenome = period.aggregateGenome
            if (aggGenome != null) {
                val frame = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                    spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                        aggGenome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                        JOURNEY_CANONICAL_TIME_SECONDS,
                    ),
                    width = this.size.width, height = this.size.height,
                )
                drawOrganism(frame)
            } else {
                drawCircle(
                    color = quietRing,
                    radius = this.size.minDimension * 0.30f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = this.size.minDimension * 0.03f),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
