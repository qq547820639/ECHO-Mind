package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.TrendNoDataReason
import com.yunjue.echo.mind.journey.TrendUiState
import com.yunjue.echo.mind.journey.trendNoDataReasonText
import com.yunjue.echo.mind.presence.computeEchoSceneFrame
import com.yunjue.echo.mind.presence.drawEchoFrame
import com.yunjue.echo.mind.ui.Page
import com.yunjue.echo.mind.ui.TREND_DISCLAIMER
import com.yunjue.echo.mind.ui.appSettingsIntent
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
        // 时间尺度选择器（Day/Week/Month/Season/Year）
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            JourneyScale.entries.forEach { s ->
                FilterChip(
                    selected = state.selectedScale == s,
                    onClick = { onEvent(JourneyEvent.SelectScale(s)) },
                    label = { Text(scaleLabel(s)) }
                )
            }
        }

        when (state.trendState) {
            TrendUiState.LOADING -> {
                CircularProgressIndicator()
                Text("旅程加载中…")
            }
            TrendUiState.PERMISSION_DISABLED -> {
                Text("被动感知已关闭或权限被撤，无法获取新的旅程数据。")
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(appSettingsIntent(context)) }
                }) { Text("前往系统设置修复权限") }
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
        seed = state.journeySeed,
        feedback = feedback,
        onSelectDay = { date -> onEvent(JourneyEvent.SelectDay(date)) },
    )

    // 2. 长期叙事（变化发生在叙事里，图表只是依据）
    val narrative = state.narrative
    if (narrative != null) {
        val portraits = state.timeline.portraits
        Text(
            if (narrative.result.text.isBlank()) com.yunjue.echo.mind.model.portraitStabilitySummary(portraits)
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

    // 3. ERA 16 §84-§87 — Journey 长期记忆（河流 / 年视图 / 阶段解释 / 历史重建）
    VisualMemoryRiverRow(segments = state.riverSegments, seed = state.journeySeed)
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

/** 视觉记忆河流：DAY=逐日 7 帧；WEEK=按周聚合；MONTH/SEASON/YEAR=按 30 天聚合（§85 语义保留）。 */
@Composable
private fun VisualMemoryRiver(
    scale: JourneyScale,
    days: List<JourneyDay>,
    periods: List<JourneyPeriod>,
    seed: Long,
    feedback: (String) -> Boolean?,
    onSelectDay: (String) -> Unit,
) {
    when (scale) {
        JourneyScale.DAY -> {
            val lastDays = (0 until 7).map { LocalDate.now().minusDays((7 - 1 - it).toLong()) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                lastDays.forEach { day ->
                    val journeyDay = days.firstOrNull { it.date == day.toString() }
                    JourneyThumbCell(
                        journeyDay = journeyDay,
                        seed = seed,
                        label = "${day.monthValue}/${day.dayOfMonth}",
                        mark = feedback(day.toString())?.let { if (it) "✓" else "✗" } ?: " ",
                        onClick = { onSelectDay(day.toString()) },
                    )
                }
            }
            Text("✓ 你觉得像 · ✗ 你觉得不太像 · 点按任意一天 = 那一天的回声", style = MaterialTheme.typography.labelSmall)
        }
        else -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                periods.forEachIndexed { index, period ->
                    val span = period.days.firstOrNull()?.date?.take(7) ?: ""
                    val last = period.days.lastOrNull()?.date?.take(7) ?: ""
                    JourneyAggregateCell(
                        period = period,
                        seed = seed,
                        label = when (scale) {
                            JourneyScale.WEEK -> "第 ${index + 1} 周"
                            else -> if (span.isNotBlank()) "${span}…${last}" else "第 ${index + 1} 段"
                        },
                        large = scale == JourneyScale.MONTH && periods.size == 1,
                    )
                }
            }
        }
    }
}

/** 单日视觉记忆单元（CANONICAL_SNAPSHOT；无数据日 = 弥散占位，不编造）。 */
@Composable
private fun JourneyThumbCell(
    journeyDay: JourneyDay?,
    seed: Long,
    label: String,
    mark: String,
    onClick: () -> Unit,
) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Canvas(Modifier.size(52.dp)) {
            val frame = journeyDay?.visualParams?.let {
                computeEchoSceneFrame(it, seed, JOURNEY_CANONICAL_TIME_SECONDS, this.size.width, this.size.height)
            }
            if (frame != null) {
                drawEchoFrame(frame)
            } else {
                // 无数据日：低亮度弥散占位（Journey 的「没有记录」也是视觉记忆）
                drawCircle(
                    color = placeholderColor,
                    radius = this.size.minDimension * 0.2f,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(mark, style = MaterialTheme.typography.labelSmall)
    }
}

/** 周/月聚合帧（视觉逐渐聚合，不是折线图；参数由 JourneyPeriod 预装配）。 */
@Composable
private fun JourneyAggregateCell(
    period: JourneyPeriod,
    seed: Long,
    label: String,
    large: Boolean = false,
) {
    val cellSize = if (large) 140.dp else 72.dp
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(cellSize)) {
            val frame = period.aggregateParams?.let {
                computeEchoSceneFrame(it, seed, JOURNEY_CANONICAL_TIME_SECONDS, this.size.width, this.size.height)
            }
            if (frame != null) {
                drawEchoFrame(frame)
            } else {
                drawCircle(
                    color = placeholderColor,
                    radius = this.size.minDimension * 0.2f,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
