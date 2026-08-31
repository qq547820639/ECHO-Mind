package com.yunjue.echo.mind.ui.journey

import com.yunjue.echo.mind.ui.echo.components.EchoGrowthPage
import com.yunjue.echo.mind.ui.echo.components.GrowthTimelinePoint

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.TrendNoDataReason
import com.yunjue.echo.mind.journey.TrendUiState
import com.yunjue.echo.mind.journey.trendNoDataReasonText
import com.yunjue.echo.mind.ui.TREND_DISCLAIMER
import com.yunjue.echo.mind.ui.batteryOptimizationSettingsIntent
import com.yunjue.echo.mind.ui.formatTimestamp
import java.time.LocalDate

/**
 * V3 §AK–§AO — Journey 熟悉时间导航（Organism Quality §32 按 cohesion 拆分后 root）：
 * - root 只负责 collect ViewModel + 趋势状态路由（state/navigation）；
 * - 各 period 的 presentation 在同包文件：JourneyScaleSelector / JourneyScaleContent /
 *   JourneyDayTimeline / JourneyWeekStrip / JourneyMonthCalendar / JourneySeasonYearMonths /
 *   JourneyDetailSections / JourneyMiniOrganism；日历纯函数在 JourneyCalendarMath。
 * - DAY = 时间线列表（§AL）/ WEEK = 7 天条（§AM）/ MONTH = 真实月历（§AN）/
 *   SEASON/YEAR = 自然月分组（§AO）；组合内部不做 LocalDate.now()（§AI/§AQ）。
 *
 * ERA 32：JourneyScreen 只 collect + 路由 ViewModel；纯渲染在 JourneyScreenContent
 * （state-in / event-out），Compose smoke test 直接注入状态。
 *
 * V3 §D5（主题分离）：顶部「趋势 | 成长」分段切换（rememberSaveable；默认趋势，
 * 免责声明契约不受影响）；成长页（EchoGrowthPage）只出现在成长段，
 * 不再追加在趋势内容尾部。
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
        today = LocalDate.now(),
    )
}

/**
 * ERA 32 — JourneyScreen 纯状态内容（state-in / event-out）。
 * 只消费 [JourneyUiState]，交互以 [JourneyEvent] 与回调输出；
 * 不持有 ViewModel / Repository / Context 业务编排（Context 仅用于系统设置深链按钮）。
 * [today] 由调用方传入（§AI：组合内部不取 LocalDate.now()）。
 */
@Composable
fun JourneyScreenContent(
    state: JourneyUiState,
    onEvent: (JourneyEvent) -> Unit,
    feedback: (String) -> Boolean?,
    onGoToSupport: () -> Unit = {},
    today: LocalDate,
) {
    // V3 §D5：分段 presentation state（进程重建恢复；默认趋势段）
    var showGrowth by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "旅程 · 我的时间",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        JourneySegmentSwitch(showGrowth = showGrowth, onSelect = { showGrowth = it })

        if (showGrowth) {
            // 成长段：现有 EchoGrowthPage 内容（不再追加在趋势尾部）
            EchoGrowthPage(
                rememberedFragmentsCount = null,
                understandingDays = state.availability.baselineDays.takeIf { it > 0 },
                behaviorTrendText = null,
                behaviorTrendLabel = null,
                accompanimentHours = null,
                timelinePoints = listOf(
                    GrowthTimelinePoint(
                        date = "7月1日",
                        label = "初次相遇",
                        isToday = false,
                    ),
                    GrowthTimelinePoint(
                        date = "7月12日",
                        label = "开始理解",
                        isToday = false,
                    ),
                    GrowthTimelinePoint(
                        date = "7月24日",
                        label = "建立节律",
                        isToday = false,
                    ),
                    GrowthTimelinePoint(
                        date = "今天",
                        label = "越来越懂你",
                        isToday = true,
                    ),
                ),
                onContinueClick = {},
                modifier = Modifier.weight(1f),
            )
        } else {
            JourneyScaleSelector(
                selectedScale = state.selectedScale,
                onSelect = { scale -> onEvent(JourneyEvent.SelectScale(scale)) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.trendState) {
                    TrendUiState.LOADING -> Column(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // §40：quiet loading（无 spinner 主视觉）
                        Text(
                            "正在整理你的时间…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                        )
                    }
                    TrendUiState.PERMISSION_DISABLED -> Column(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("被动感知已关闭或权限被撤，无法获取新的旅程数据。")
                        // ERA 32 R26：恢复入口是支持页（数据与感知开关），不是系统设置。
                        OutlinedButton(onClick = onGoToSupport) { Text("前往支持页重新开启") }
                    }
                    TrendUiState.ERROR -> Column(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("旅程加载失败")
                        Button(onClick = { onEvent(JourneyEvent.Refresh) }) { Text("重试") }
                    }
                    TrendUiState.NO_DATA -> Column(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        NoDataContent(state = state, onGoToSupport = onGoToSupport)
                    }
                    TrendUiState.OFFLINE_CACHED -> Column(Modifier.fillMaxSize()) {
                        Text(
                            "当前离线，以下为缓存的旅程。",
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                        )
                        Box(Modifier.weight(1f)) {
                            JourneyScaleContent(state = state, onEvent = onEvent, feedback = feedback, today = today)
                        }
                    }
                    TrendUiState.FRESH, TrendUiState.PARTIAL ->
                        JourneyScaleContent(state = state, onEvent = onEvent, feedback = feedback, today = today)
                }
            }
            // 契约点 2 固定免责文案（单测锚点）——固定页脚，安静呈现（随趋势段渲染）。
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Text(
                TREND_DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
            )
        }
    }
}

/** V3 §D5：「趋势 | 成长」分段切换（selectable + Role.Tab 双语义；≥48dp 触达）。 */
@Composable
private fun JourneySegmentSwitch(showGrowth: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JourneySegment(
            label = "趋势",
            selected = !showGrowth,
            testTag = "journey_segment_trend",
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f),
        )
        JourneySegment(
            label = "成长",
            selected = showGrowth,
            testTag = "journey_segment_growth",
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun JourneySegment(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .testTag(testTag)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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
