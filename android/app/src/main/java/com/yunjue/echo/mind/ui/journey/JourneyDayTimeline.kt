package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JourneyCanonicalDay
import com.yunjue.echo.mind.journey.JourneyDay
import java.time.LocalDate

/** §AL Day：按时间顺序的时间线列表（最新在前——「找昨天」一屏内）；行高 80dp。 */
@Composable
internal fun DayTimeline(
    days: List<JourneyDay>,
    today: LocalDate,
    selectedDate: String?,
    feedback: (String) -> Boolean?,
    onSelectDay: (String) -> Unit,
    selectedCanonical: JourneyCanonicalDay?,
    selectedDayExplanation: List<String>,
    detail: @Composable () -> Unit,
) {
    val sorted = remember(days) { days.sortedByDescending { it.date } }
    val todayKey = today.toString()
    // ERA 31 R19：锚定旅程实际最新一天（感知滞后时不渲染空占位的「今天」）
    val currentDate = sorted.firstOrNull { it.date == todayKey }?.date ?: sorted.firstOrNull()?.date
    LazyColumn(
        Modifier.fillMaxSize().testTag("journey_memory_visual"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(sorted.size) { index ->
            val day = sorted[index]
            DayTimelineRow(
                day = day,
                isCurrent = day.date == currentDate,
                isSelected = day.date == selectedDate,
                feedbackMark = feedback(day.date),
                onSelect = onSelectDay,
                selectedCanonical = selectedCanonical,
                selectedDayExplanation = selectedDayExplanation,
            )
        }
        item { detail() }
    }
}

@Composable
private fun DayTimelineRow(
    day: JourneyDay,
    isCurrent: Boolean,
    isSelected: Boolean,
    feedbackMark: Boolean?,
    onSelect: (String) -> Unit,
    selectedCanonical: JourneyCanonicalDay?,
    selectedDayExplanation: List<String>,
) {
    val shape = RoundedCornerShape(12.dp)
    val highlight = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        Color.Transparent
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(highlight, shape)
                .then(
                    if (isSelected) {
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                    } else {
                        Modifier
                    },
                )
                .clickable { onSelect(day.date) }
                .testTag("journey_day_item")
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JourneyPortrait(day = day, size = 40.dp, alpha = 1f)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    journeyDateLabel(day.date),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                )
                val line = day.headline.ifBlank { day.summary }
                if (line.isNotBlank()) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (feedbackMark != null) {
                    Text(
                        if (feedbackMark) "你觉得像" else "你觉得不太像",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    )
                }
            }
        }
        // 选中即就地展开当天完整详情（无需滚动到底部详情层）
        if (isSelected) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 68.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(12.dp),
            ) {
                JourneyDayOrganismCanvas(day = day, canonical = selectedCanonical, sizeDp = 72.dp)
                Spacer(Modifier.height(8.dp))
                if (day.headline.isNotBlank()) {
                    Text(day.headline, style = MaterialTheme.typography.bodyMedium)
                }
                if (day.summary.isNotBlank()) {
                    Text(
                        day.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                selectedDayExplanation.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectedCanonical == null) {
                    Text(
                        "提示：Canonical 快照将从此后每天自动保存。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
