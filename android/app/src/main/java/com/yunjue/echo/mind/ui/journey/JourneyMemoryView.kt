package com.yunjue.echo.mind.ui.journey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.journey.JourneyCanonicalDay
import com.yunjue.echo.mind.journey.JourneyDay
import com.yunjue.echo.mind.journey.JourneyOrganismVisuals
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.identityEvolutionLines
import com.yunjue.echo.mind.journey.landmarkKindLabel
import com.yunjue.echo.mind.journey.seasonLabel
import com.yunjue.echo.mind.journey.shiftExplanationLines
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.surface.EchoSurface

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
                // §59/§AP：季/年聚合用 representative canonical portrait——
                // 经 facade 低预算 mini session（production organism 管线）
                val params = season.visualParams
                if (params != null) {
                    val genome = remember(params, seed) {
                        JourneyOrganismVisuals.genomeFromParams(params, seed)
                    }
                    JourneyMiniOrganism(genome = genome, size = 48.dp)
                } else {
                    Canvas(Modifier.size(48.dp)) {
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

/**
 * 那一天 ECHO 的 organism 画布（§84 共用）：Canonical 优先，画像 genome fallback，
 * 无数据 quiet placeholder（不编造）。detail portrait 走全质量 facade request
 * （JOURNEY_PRIVATE / quality NORMAL / tier LEGACY / canonical 确定时钟，静态无 ticker）。
 */
@Composable
internal fun JourneyDayOrganismCanvas(
    day: JourneyDay?,
    canonical: JourneyCanonicalDay?,
    sizeDp: Dp,
) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    val canonicalGenome = remember(canonical) {
        canonical?.let { JourneyOrganismVisuals.genomeFromParams(it.visualParams, it.visualSeed) }
    }
    val genome: EchoVisualGenome? = canonicalGenome ?: day?.genome
    val maturityName = canonical?.maturity?.name ?: "KNOWN"
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.roundToPx() }.coerceAtLeast(1)
    val session = remember(genome, sizePx, maturityName) {
        genome?.let {
            EchoRendererFacade.createSession(
                EchoRenderRequest(
                    genome = it,
                    surface = EchoSurface.JOURNEY_PRIVATE,
                    motion = com.yunjue.echo.mind.visual.surface.MotionPolicy.NORMAL,
                    maturityName = maturityName,
                    requestedTier = EchoRenderTier.LEGACY,
                    quality = EchoRenderQuality.NORMAL,
                ),
                sizePx,
                sizePx,
            )
        }
    }
    Canvas(Modifier.size(sizeDp)) {
        val s = session
        if (s == null) {
            drawCircle(color = placeholderColor, radius = this.size.minDimension * 0.25f)
        } else {
            drawIntoCanvas { c -> s.draw(c.nativeCanvas, JOURNEY_CANONICAL_NANOS) }
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
    Column(Modifier.padding(top = 12.dp)) {
        Text("那一天的回声 · ${day.date}", style = MaterialTheme.typography.titleSmall)
        JourneyDayOrganismCanvas(day = day, canonical = canonical, sizeDp = 96.dp)
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
