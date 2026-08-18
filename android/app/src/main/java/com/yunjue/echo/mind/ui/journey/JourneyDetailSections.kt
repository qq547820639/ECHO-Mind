package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.journeyNaturalSummary
import java.time.LocalDate

/**
 * 各尺度共用详情层（在各自滚动容器末尾）：长期叙事 → 期间故事 → 季解释/年视图/历史重建 →
 * 「查看依据」Evidence Layer（定量证据只在展开后出现，非第一视觉）。
 */
@Composable
internal fun JourneyDetailSections(
    state: JourneyUiState,
    onEvent: (JourneyEvent) -> Unit,
    feedback: (String) -> Boolean?,
    anchorDate: LocalDate,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val narrative = state.narrative
        if (narrative != null) {
            val portraits = state.timeline.portraits
            Text(
                if (narrative.result.text.isBlank()) journeyNaturalSummary(portraits)
                else narrative.result.text,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (narrative.result.usedSources.isNotEmpty()) {
                Text(
                    "依据：${narrative.result.usedSources.joinToString("、") { dataSourceLabelForJourney(it) }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (narrative.contextExceptions.isNotEmpty()) {
                Text(
                    "你告诉我的特殊日期：${narrative.contextExceptions.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.periodStory.isNotBlank()) {
            Text(
                state.periodStory,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.monthAgoLines.forEach { line ->
            Text(
                "现在和一个月前：$line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SeasonExplanationSection(lines = state.seasonExplanation)
        YearViewSection(state = state, seed = state.journeySeed)
        HistoricalReconstructionSection(
            day = state.selectedDay,
            canonical = state.selectedCanonical,
            fallbackSeed = state.journeySeed,
            explanation = state.selectedDayExplanation,
        )
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
                anchor = anchorDate,
            )
        }
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
