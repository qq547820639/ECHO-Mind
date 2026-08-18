package com.yunjue.echo.mind.ui.debug

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.BuildConfig
import com.yunjue.echo.mind.presencevisual.AgslEchoBackend
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.presencevisual.VisualLabMetrics
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy
import com.yunjue.echo.mind.visual.testing.VisualLabFixtures
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * ECHO Visual Lab — V3 §33/§34 debug-only 调参工作台。
 *
 * - **debug-only**：入口与本体双重 BuildConfig.DEBUG 门；不进入 production navigation；
 *   Release 构建不渲染任何内容（§91）。
 * - 只允许在定义范围内调参（0..1 Knobs）；不得通过 Lab 改产品语义。
 * - 一键输出：PNG screenshot + metrics JSON（§34 自动指标 + §82 Reference 门）。
 * - §R：预览与导出都经 EchoRendererFacade——**导出后端 == 实际解析后端**
 *   （此前导出恒 Canvas，与预览选中的 AGSL/ADVANCED 不一致，已修复）；
 *   后端解析与实际不符时 UI 明示（"实际后端: …"）。
 */

private const val EXPORT_W = 1080
private const val EXPORT_H = 1920

/** §R：导出/预览的确定性 canonical 时刻（与 Journey 同基准，秒）。 */
private const val EXPORT_CLOCK_SECONDS = 12f

@Composable
fun VisualLabScreen(onClose: () -> Unit) {
    if (!BuildConfig.DEBUG) return // §91：Release 不泄露 internal control

    val context = LocalContext.current
    var preset by remember { mutableStateOf(VisualLabFixtures.Preset.KNOWN_DAY28) }
    var surface by remember { mutableStateOf(EchoSurface.APP_PRIVATE) }
    var backend by remember { mutableStateOf(EchoRenderTier.LEGACY) }
    var knobs by remember { mutableStateOf(VisualLabFixtures.Knobs()) }
    var seed by remember { mutableStateOf(VisualLabFixtures.LAB_SEED) }
    var reduced by remember { mutableStateOf(false) }
    var exportReport by remember { mutableStateOf<String?>(null) }

    val genome = remember(preset, knobs, seed) {
        VisualLabFixtures.withKnobs(
            VisualLabFixtures.withSeed(VisualLabFixtures.genomeFor(preset), seed),
            knobs,
        )
    }
    // §R：Lab 预览请求（production facade 路径；quality 固定 NORMAL 保持 Lab 调参基准）
    val request = remember(genome, surface, backend, preset, reduced) {
        EchoRenderRequest(
            genome = genome,
            surface = surface,
            motion = if (reduced) MotionPolicy.REDUCED else MotionPolicy.NORMAL,
            maturityName = VisualLabFixtures.maturityFor(preset),
            requestedTier = backend,
            quality = EchoRenderQuality.NORMAL,
        )
    }
    val resolution = remember(request) { EchoRendererFacade.resolve(request) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ECHO Visual Lab", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onClose) { Text("关闭") }
        }

        // 预览（§R：production facade——genome → request → EchoRendererFacade.Organism）
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            EchoRendererFacade.Organism(
                request = request,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Text("Preset", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VisualLabFixtures.Preset.entries.forEach { p ->
                FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p.name) })
            }
        }
        Text("Surface", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                EchoSurface.APP_PRIVATE to "APP",
                EchoSurface.WALLPAPER_VISUAL_ONLY to "WALLPAPER",
                EchoSurface.DREAM_AMBIENT to "DREAM",
            ).forEach { (s, label) ->
                FilterChip(selected = surface == s, onClick = { surface = s }, label = { Text(label) })
            }
        }
        val agslOk = remember { AgslEchoBackend.isAvailable() } // §X：进程级缓存能力
        Text(
            "Backend" + if (agslOk) "" else "（本机 RuntimeShader 不可用 → AGSL/ADVANCED 回退 CANVAS）",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                EchoRenderTier.LEGACY to "CANVAS",
                EchoRenderTier.STANDARD to "AGSL",
                EchoRenderTier.ADVANCED to "ADVANCED",
                EchoRenderTier.ULTRA to "ULTRA",
            ).forEach { (t, label) ->
                // ULTRA 永不自动启用（§97：无四硬门 → ULTRA_DISABLED_BY_CAPABILITY）
                val enabled = t == EchoRenderTier.LEGACY ||
                    t != EchoRenderTier.ULTRA && agslOk
                FilterChip(
                    selected = backend == t,
                    onClick = { if (enabled) backend = t },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
        // §R：后端解析与请求不一致时明示（如 AGSL 请求在无 RuntimeShader 设备上回退 CANVAS）
        if (resolution.backendName != requestedBackendLabel(backend)) {
            Text(
                "实际后端: ${resolution.backendName}" +
                    (resolution.reason?.let { "（$it）" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = reduced, onClick = { reduced = !reduced }, label = { Text("Reduced Motion") })
            OutlinedButton(onClick = { seed = seed + 1 }) { Text("换 identity (seed=$seed)") }
        }

        // 参数滑杆（只允许定义范围内）
        LabSlider("flow", knobs.flow) { knobs = knobs.copy(flow = it) }
        LabSlider("coherence", knobs.coherence) { knobs = knobs.copy(coherence = it) }
        LabSlider("turbulence", knobs.turbulence) { knobs = knobs.copy(turbulence = it) }
        LabSlider("particleDensity", knobs.particleDensity) { knobs = knobs.copy(particleDensity = it) }
        LabSlider("depth", knobs.depth) { knobs = knobs.copy(depth = it) }
        LabSlider("coreOpenness", knobs.coreOpenness) { knobs = knobs.copy(coreOpenness = it) }
        LabSlider("structureComplexity", knobs.structureComplexity) { knobs = knobs.copy(structureComplexity = it) }
        LabSlider("halo", knobs.halo) { knobs = knobs.copy(halo = it) }
        LabSlider("exposure", knobs.exposure) { knobs = knobs.copy(exposure = it) }
        LabSlider("warm accent", knobs.warmAccent) { knobs = knobs.copy(warmAccent = it) }
        LabSlider("motion", knobs.motion) { knobs = knobs.copy(motion = it) }

        Button(onClick = {
            exportReport = exportLab(context.cacheDir, request, preset)
        }) { Text("导出 PNG + metrics（Reference 门评估）") }

        exportReport?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** 请求 tier → 预期后端名（与 FilterChip 标签一致；ULTRA 预期按 ADVANCED 解析）。 */
private fun requestedBackendLabel(tier: EchoRenderTier): String = when (tier) {
    EchoRenderTier.LEGACY -> EchoRendererFacade.BACKEND_CANVAS
    EchoRenderTier.STANDARD -> EchoRendererFacade.BACKEND_AGSL
    EchoRenderTier.ADVANCED, EchoRenderTier.ULTRA -> EchoRendererFacade.BACKEND_AGSL_ADVANCED
}

@Composable
private fun LabSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("%-20s %.2f".format(label, value), style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f))
    }
}

/** 离屏渲染（§R：真实解析后端）+ 指标 + Reference 门 + 落盘（PNG / metrics JSON）。 */
private fun exportLab(
    cacheDir: File,
    request: EchoRenderRequest,
    preset: VisualLabFixtures.Preset,
): String {
    // §R：导出走 facade session——导出后端 == 实际选中后端（不再恒 Canvas）
    val session = EchoRendererFacade.createSession(request, EXPORT_W, EXPORT_H)
    val bitmap = session.renderToBitmap((EXPORT_CLOCK_SECONDS * 1_000_000_000L).toLong())
    val resolution = session.resolution
    // 视觉半径与帧计算机同公式（单一事实源 baseRadiusFor）
    val identity = com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(request.genome.identitySeed)
    val baseR = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.baseRadiusFor(
        request.genome.radialSpread, request.genome.coreIntensity, identity.membraneBias,
    )
    val metrics = VisualLabMetrics.compute(bitmap, EXPORT_W / 2f, EXPORT_H / 2f, baseR * EXPORT_W)
    val gate = VisualLabMetrics.evaluate(metrics)

    val dir = File(cacheDir, "visual-lab").also { it.mkdirs() }
    val tag = "${preset.name.lowercase()}_${request.surface.name.lowercase()}"
    FileOutputStream(File(dir, "$tag.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val json = JSONObject()
        .put("preset", preset.name)
        .put("surface", request.surface.name)
        .put("requestedBackend", request.requestedTier.name)
        .put("resolvedBackend", resolution.resolvedTier.name)
        .put("actualBackend", session.offscreenActualBackend)
        .put("backendName", resolution.backendName)
        .put("quality", resolution.quality.name)
        .put("reason", session.offscreenReason ?: JSONObject.NULL)
        .put("reducedMotion", resolution.reducedMotion)
        .put("nearBlackRatio", metrics.nearBlackRatio.toDouble())
        .put("highLuminanceRatio", metrics.highLuminanceRatio.toDouble())
        .put("extremeGlintRatio", metrics.extremeGlintRatio.toDouble())
        .put("warmRatio", metrics.warmRatio.toDouble())
        .put("negativeSpaceRatio", metrics.negativeSpaceRatio.toDouble())
        .put("visualMassInside", metrics.visualMassInside.toDouble())
        .put("organismWidthFraction", metrics.organismWidthFraction.toDouble())
        .put("organismHeightFraction", metrics.organismHeightFraction.toDouble())
        .put("edgeDensity", metrics.edgeDensity.toDouble())
        .put("centerLuminance", metrics.centerLuminance.toDouble())
        .put("outerLuminance", metrics.outerLuminance.toDouble())
        .put("gateAllPass", gate.allPass)
    File(dir, "$tag.metrics.json").writeText(json.toString(2))

    return buildString {
        appendLine("导出：${dir.absolutePath}/$tag.{png,metrics.json}")
        appendLine(
            "后端：预览解析 ${resolution.backendName}${resolution.reason?.let { "（$it）" } ?: ""}；" +
                "离屏导出 raster ${session.offscreenActualBackend}（软件位图无法执行 RuntimeShader——Android 真实约束）",
        )
        appendLine("near-black %.1f%%（≥58%% %s）".format(metrics.nearBlackRatio * 100, ok(gate.nearBlackPass)))
        appendLine("high-luminance %.2f%%（≤4%% %s）".format(metrics.highLuminanceRatio * 100, ok(gate.highLuminancePass)))
        appendLine("extreme glint %.2f%%（≤2.5%% %s）".format(metrics.extremeGlintRatio * 100, ok(gate.extremeGlintPass)))
        appendLine("warm %.1f%%（target≤10%% %s / hard≤15%% %s）".format(metrics.warmRatio * 100, ok(gate.warmTargetPass), ok(gate.warmPass)))
        appendLine("negative-space %.1f%%（≥40%% %s）".format(metrics.negativeSpaceRatio * 100, ok(gate.negativeSpacePass)))
        appendLine("visual-mass@.9R %.1f%%（≥82%% %s）".format(metrics.visualMassInside * 100, ok(gate.visualMassPass)))
        appendLine("organism bbox %.1f%%×%.1f%%（宽 72–82%% %s）".format(metrics.organismWidthFraction * 100, metrics.organismHeightFraction * 100, ok(gate.organismWidthPass)))
        appendLine("edge-density %.3f".format(metrics.edgeDensity))
        appendLine("center-luma %.3f（cavity %s）".format(metrics.centerLuminance, ok(gate.cavityPass)))
        appendLine("Reference 门：${if (gate.allPass) "PASS" else "FAIL"}")
    }
}

private fun ok(pass: Boolean) = if (pass) "PASS" else "FAIL"
