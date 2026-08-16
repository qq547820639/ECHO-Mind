package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.journey.JourneyCanonicalDay
import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.identityEvolutionLines
import com.yunjue.echo.mind.journey.landmarkKindLabel
import com.yunjue.echo.mind.journey.reconstructJourneyFrame
import com.yunjue.echo.mind.presencevisual.drawOrganism
import com.yunjue.echo.mind.journey.seasonLabel
import com.yunjue.echo.mind.journey.shiftExplanationLines

/**
 * ERA 16 §84-§87 — Journey 长期记忆 UI 层（Screen 之外的独立组件，保持 JourneyScreen 薄）：
 * - Year View（§86 四季聚合 + 转变 + 上下文时期 + 身份演化）；
 * - Historical Reconstruction（§84 那一天的回声）；
 * - Life Season × Journey 解释（§87）。
 * - §85 河段（VisualMemoryRiverRow）于 ERA 31 R27 并入主河流（聚合格直接标注河段种类），
 *   本文件不再保留第二条河流行。
 *
 * 全部只消费 JourneyUiState；不持有 Repository / AI / Preferences。
 */

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
                    // §59：季/年聚合用 representative canonical portrait（production organism 管线）
                    val frame = season.visualParams?.let { params ->
                        com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                            spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                                com.yunjue.echo.mind.journey.JourneyOrganismVisuals.genomeFromParams(params, seed),
                                com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                                JOURNEY_CANONICAL_TIME_SECONDS,
                            ),
                            width = this.size.width,
                            height = this.size.height,
                        )
                    }
                    if (frame != null) {
                        drawOrganism(frame)
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
            // V3：历史帧经同一 production organism 管线（Canonical 优先；画像 genome fallback；
            // 无数据 quiet ring，不编造）
            val frame = reconstructJourneyFrame(
                canonical = canonical,
                fallbackPortrait = null,
                fallbackSeed = fallbackSeed,
                width = this.size.width,
                height = this.size.height,
            ) ?: day.genome?.let { g ->
                com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                    spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                        g, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                        JOURNEY_CANONICAL_TIME_SECONDS,
                    ),
                    width = this.size.width,
                    height = this.size.height,
                )
            }
            if (frame != null) {
                drawOrganism(frame)
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
