package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.journey.JourneyCanonicalDay
import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.journey.JourneyRiverSegment
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.identityEvolutionLines
import com.yunjue.echo.mind.journey.landmarkKindLabel
import com.yunjue.echo.mind.journey.reconstructJourneyFrame
import com.yunjue.echo.mind.journey.seasonLabel
import com.yunjue.echo.mind.journey.shiftExplanationLines
import com.yunjue.echo.mind.presence.computeEchoSceneFrame
import com.yunjue.echo.mind.presence.drawEchoFrame

/**
 * ERA 16 §84-§87 — Journey 长期记忆 UI 层（Screen 之外的独立组件，保持 JourneyScreen 薄）：
 * - Visual Memory River（§85 河段概览）；
 * - Year View（§86 四季聚合 + 转变 + 上下文时期 + 身份演化）；
 * - Historical Reconstruction（§84 那一天的回声）；
 * - Life Season × Journey 解释（§87）。
 *
 * 全部只消费 JourneyUiState；不持有 Repository / AI / Preferences。
 */

/** §85 — 视觉记忆河流河段行（一眼看到平稳/密集/漂移/特殊/转变）。 */
@Composable
fun VisualMemoryRiverRow(
    segments: List<JourneyRiverSegment>,
    seed: Long,
) {
    if (segments.isEmpty()) return
    Column(Modifier.padding(top = 12.dp)) {
        Text("视觉记忆河流", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            segments.forEach { segment ->
                val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(44.dp)) {
                        val frame = segment.visualParams?.let {
                            computeEchoSceneFrame(
                                it, seed, JOURNEY_CANONICAL_TIME_SECONDS, this.size.width, this.size.height
                            )
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
                    Text(segment.label, style = MaterialTheme.typography.labelSmall)
                    Text(segment.startDate.take(7), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** §86 — 年视图：四季聚合 + 转变点 + 上下文时期 + 身份演化（不是 365 个点）。 */
@Composable
fun YearViewSection(state: JourneyUiState, seed: Long) {
    val year = state.yearView ?: return
    Column(Modifier.padding(top = 12.dp)) {
        Text("年度视图", style = MaterialTheme.typography.titleSmall)
        year.seasons.forEach { season ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
                Canvas(Modifier.size(48.dp)) {
                    val frame = season.visualParams?.let {
                        computeEchoSceneFrame(
                            it, seed, JOURNEY_CANONICAL_TIME_SECONDS, this.size.width, this.size.height
                        )
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
                Column(Modifier.padding(start = 8.dp)) {
                    Text("${seasonLabel(season.season)} · ${season.startDate} … ${season.endDate}（${season.dayCount} 天）")
                    if (season.majorShifts.isNotEmpty()) {
                        Text("转变 ${season.majorShifts.size} 次", style = MaterialTheme.typography.labelSmall)
                    }
                    if (season.contextPeriods.isNotEmpty()) {
                        Text(
                            "特殊阶段：${season.contextPeriods.joinToString("、") { it.kind }}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        year.majorShifts.forEach { shift ->
            shiftExplanationLines(shift).forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (year.identityEvolution.isNotEmpty()) {
            Text("身份演化（每月最近快照）", style = MaterialTheme.typography.labelSmall)
            identityEvolutionLines(year.identityEvolution).forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    // §41 时间地标：值得回看的时间锚点（§33 四类；安静列表，视觉仍是第一层）
    if (state.landmarks.isNotEmpty()) {
        Text("时间地标", style = MaterialTheme.typography.titleSmall)
        state.landmarks.forEach { landmark ->
            Text(
                "${landmark.date} · ${landmarkKindLabel(landmark.kind)}：${landmark.text}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** §87 — Life Season × Journey：为什么这个阶段 ECHO 的视觉慢慢变化。 */
@Composable
fun SeasonExplanationSection(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(Modifier.padding(top = 8.dp)) {
        Text("为什么这个阶段的 ECHO 在变化", style = MaterialTheme.typography.titleSmall)
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** §84 — 历史重建：选中那一天的 ECHO（Canonical 优先，画像 fallback，无数据弥散占位）。 */
@Composable
fun HistoricalReconstructionSection(
    day: JourneyDay?,
    canonical: JourneyCanonicalDay?,
    fallbackSeed: Long,
    explanation: List<String>,
) {
    if (day == null) return
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.padding(top = 12.dp)) {
        Text("那一天的回声 · ${day.date}", style = MaterialTheme.typography.titleSmall)
        Canvas(Modifier.size(96.dp)) {
            val frame = reconstructJourneyFrame(
                canonical = canonical,
                fallbackPortrait = null,
                fallbackSeed = fallbackSeed,
                width = this.size.width,
                height = this.size.height,
            ) ?: day.visualParams?.let {
                computeEchoSceneFrame(
                    it, fallbackSeed, JOURNEY_CANONICAL_TIME_SECONDS, this.size.width, this.size.height
                )
            }
            if (frame != null) {
                drawEchoFrame(frame)
            } else {
                drawCircle(
                    color = placeholderColor,
                    radius = this.size.minDimension * 0.25f,
                )
            }
        }
        if (day.headline.isNotBlank()) {
            Text(day.headline, style = MaterialTheme.typography.bodyMedium)
        }
        if (day.summary.isNotBlank()) {
            Text(day.summary, style = MaterialTheme.typography.bodySmall)
        }
        explanation.forEach { line ->
            Text(line, style = MaterialTheme.typography.labelSmall)
        }
        if (canonical == null) {
            Text("提示：Canonical 快照将从此后每天自动保存。", style = MaterialTheme.typography.labelSmall)
        }
    }
}
