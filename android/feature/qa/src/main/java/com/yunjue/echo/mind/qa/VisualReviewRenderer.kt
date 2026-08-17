package com.yunjue.echo.mind.qa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_HOUR
import com.yunjue.echo.mind.journey.buildCanonicalDay
import com.yunjue.echo.mind.journey.reconstructJourneyFrame
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presencevisual.OrganismCanvasRenderer
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.render.OrganismFrame
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import java.io.File

/**
 * ERA 31 §5/§6 — Visual Review Renderer（qa/visual-review 工件生成器）。
 *
 * 原则：渲染的是 **production 帧模型 + production android.graphics 渲染器**
 * （V3 §H：EchoVisualMapper → VisualGenomeCompiler → SurfacePolicy
 *   → OrganismFrameComputer → OrganismCanvasRenderer），
 * 本类只负责把真实帧落成 PNG / 对比拼图 / 参数快照说明，供人眼评审——不是 QA 重写产品。
 *
 * 所有输出确定性：同一 profile/day/surface 恒同帧（渲染时间锚 = Journey canonical 12:00）。
 */
object VisualReviewRenderer {

    /** APP / Dream 视口（20.5:9 手机）。 */
    const val APP_WIDTH = 1080
    const val APP_HEIGHT = 2340

    /** Wallpaper / Lock 视口（20:9，桌面常见尺寸）。 */
    const val WALLPAPER_WIDTH = 1080
    const val WALLPAPER_HEIGHT = 2400

    /** 评审基准时刻 = Journey canonical 正午 12:00（与生产 Journey 同锚，跨天可比）。 */
    const val REVIEW_HOUR: Float = JOURNEY_CANONICAL_HOUR

    /** 评审表面（Part 5 要求的五个渲染面；V3 直接携带 EchoSurface 裁剪面）。 */
    enum class ReviewSurface(val surface: EchoSurface, val width: Int, val height: Int) {
        APP(EchoSurface.APP_PRIVATE, APP_WIDTH, APP_HEIGHT),
        HOME_WALLPAPER(EchoSurface.WALLPAPER_VISUAL_ONLY, WALLPAPER_WIDTH, WALLPAPER_HEIGHT),
        LOCK_SAFE(EchoSurface.LOCK_PUBLIC_SAFE, WALLPAPER_WIDTH, WALLPAPER_HEIGHT),
        DREAM(EchoSurface.DREAM_AMBIENT, APP_WIDTH, APP_HEIGHT),
        /** Journey 专用：走 production reconstructJourneyFrame（canonical 重建）。 */
        CANONICAL_JOURNEY(EchoSurface.APP_PRIVATE, APP_WIDTH, APP_HEIGHT),
    }

    fun frameFor(snap: QaDaySnapshot, surface: ReviewSurface): OrganismFrame? {
        if (surface == ReviewSurface.CANONICAL_JOURNEY) {
            val canonical = buildCanonicalDay(
                date = snap.date.toString(),
                state = snap.presence,
                keyEvidenceIds = snap.portrait?.let { listOf("portrait:${snap.date}") } ?: emptyList(),
                createdAtEpochMs = snap.date.toEpochDay() * 86_400_000L,
            )
            return reconstructJourneyFrame(
                canonical = canonical,
                fallbackPortrait = snap.portrait,
                fallbackSeed = snap.identity.seed,
                width = surface.width.toFloat(),
                height = surface.height.toFloat(),
            )
        }
        // V3 §H：genome 经唯一语义链（EchoVisualMapper → VisualGenomeCompiler）计算
        val genome = VisualGenomeCompiler.compile(
            EchoVisualMapper.map(snap.presence, REVIEW_HOUR),
            snap.presence.identityGenome,
        )
        return OrganismFrameComputer.compute(
            spec = SurfacePolicy.crop(genome, surface.surface, QaTimeline.frameTimeSeconds()),
            width = surface.width.toFloat(),
            height = surface.height.toFloat(),
            options = OrganismFrameComputer.EchoRenderOptions(
                maturityName = snap.presence.maturity.name,
            ),
        )
    }

    /** 经 production 渲染器把帧画进真 Bitmap。 */
    fun renderFrame(frame: OrganismFrame, width: Int, height: Int): Bitmap {
        return OrganismCanvasRenderer.renderToBitmap(frame, width, height)
    }

    fun writePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ===== 对比拼图（人眼评审工件） =====

    /** 拼图单元。 */
    data class SheetCell(val label: String, val bitmap: Bitmap)

    /**
     * 拼图：按 [columns] 网格排列，单元等比缩放到 [cellWidth]，单元下方带标签条。
     * 标签绘制失败（无字体环境）时静默降级为无文字色条——拼图顺序仍可读。
     */
    fun contactSheet(
        cells: List<SheetCell>,
        columns: Int,
        cellWidth: Int,
        title: String?,
    ): Bitmap {
        val cellHeight = (cellWidth.toDouble() * cells.first().bitmap.height / cells.first().bitmap.width).toInt()
        val gap = 12
        val labelStrip = if (title != null) 56 else 40
        val titleStrip = 52
        val rows = (cells.size + columns - 1) / columns
        val sheetWidth = columns * cellWidth + (columns + 1) * gap
        val sheetHeight = titleStrip.takeIf { title != null }?.plus(rows * (cellHeight + labelStrip + gap) + gap)
            ?: rows * (cellHeight + labelStrip + gap) + gap
        val sheet = Bitmap.createBitmap(sheetWidth, sheetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(10, 10, 12))

        var y = gap
        val titlePaint = labelPaint(30f, Color.rgb(200, 200, 205))
        val labelPaint = labelPaint(24f, Color.rgb(190, 190, 195))
        if (title != null) {
            safeDrawText(canvas, title, gap.toFloat(), y + 34f, titlePaint)
            y += titleStrip
        }
        cells.forEachIndexed { index, cell ->
            val col = index % columns
            val row = index / columns
            val left = gap + col * (cellWidth + gap)
            val top = y + row * (cellHeight + labelStrip + gap)
            val scaled = Bitmap.createScaledBitmap(cell.bitmap, cellWidth, cellHeight, true)
            canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
            safeDrawText(canvas, cell.label, left + 6f, top + cellHeight + 30f, labelPaint)
            if (scaled !== cell.bitmap) scaled.recycle()
        }
        return sheet
    }

    private fun labelPaint(size: Float, color: Int): Paint = Paint().apply {
        isAntiAlias = true
        textSize = size
        this.color = color
        typeface = android.graphics.Typeface.DEFAULT
    }

    private fun safeDrawText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        runCatching { canvas.drawText(text, x, y, paint) }
    }

    // ===== 参数快照 + 状态解释（Part 6：parameter snapshot + state explanation） =====

    /**
     * 每个 profile×day 一份 snapshot.md：
     * 状态事实（maturity/season/identity/ambient/visual params）+ 三层 headline + 人话解释。
     */
    fun writeSnapshotMarkdown(snap: QaDaySnapshot, timeline: QaTimeline, file: File) {
        val headline = QaHeadlineEngine.headlineFor(snap, timeline)
        val season = snap.season
        val identity = snap.identity
        val ambient = snap.ambient
        val app = snap.appVisual
        val lock = snap.lockVisual

        val sb = StringBuilder()
        sb.appendLine("# ${timeline.profile.id} · Day ${snap.dayIndex}（${snap.date}）")
        sb.appendLine()
        sb.appendLine("- **成熟度**: ${snap.presence.maturity}")
        sb.appendLine("- **Headline**: ${headline.public}")
        sb.appendLine("  - Evidence: ${headline.evidence}")
        sb.appendLine("  - AI layer: ${headline.aiLayer ?: "—（无增量理解）"}")
        sb.appendLine("- **Life Season**: phase ${season.phaseIndex} · drift ${season.drift.format(3)}" +
            " · rhythm ${season.rhythmShift} · screen ${season.screenFragmentation}" +
            " · activity ${season.activityVariability} · mobility ${season.mobilityTrend}" +
            " · regularity ${season.regularityTrend}")
        sb.appendLine("- **Identity**: seed=${identity.seed} · hue=${identity.accentHue.format(3)}" +
            " · colorFamily=${identity.colorFamily} · textureFamily=${identity.textureFamily}" +
            " · coreTopology=${identity.coreTopology.format(3)} · symmetry=${identity.symmetryTendency.format(3)}" +
            " · orbit=${identity.orbitGeometry.format(3)} · motionPersonality=${identity.motionPersonality.format(3)}")
        sb.appendLine("- **Ambient**: activation=${ambient.vector.activation.format(3)}" +
            " · regularity=${ambient.vector.regularity.format(3)} · density=${ambient.vector.density.format(3)}" +
            " · deviation=${ambient.vector.deviation.format(3)} · coverage=${ambient.coverage.format(3)}" +
            " · confidence=${ambient.vector.confidence.format(3)} · baselineDays=${snap.baseline?.validDays ?: 0}")
        sb.appendLine("- **Visual APP @12:00**: ${app.summaryLine()}")
        sb.appendLine("- **Visual LOCK_SAFE @12:00**: ${lock.summaryLine()}")
        sb.appendLine()
        sb.appendLine("## 为什么这一天看起来这样")
        sb.appendLine()
        sb.appendLine(explanationFor(snap, headline))
        sb.appendLine()
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    /** 机械解释（只陈述状态事实，不做心理推断；与 Grounding 纪律一致）。 */
    private fun explanationFor(snap: QaDaySnapshot, headline: QaHeadline): String {
        val lines = mutableListOf<String>()
        lines += when (snap.presence.maturity) {
            com.yunjue.echo.mind.model.EchoMaturity.SEED ->
                "Day 0：ECHO 刚苏醒，还没有基线——画面来自安装种子与此刻的少量观察，中性、克制。"
            com.yunjue.echo.mind.model.EchoMaturity.DISCOVERING,
            com.yunjue.echo.mind.model.EchoMaturity.EMERGING ->
                "基线正在形成（${snap.baseline?.validDays ?: 0} 个有效日）。ECHO 开始看到节奏，但视觉仍以学习期的安静为主。"
            else ->
                "基线已形成（${snap.baseline?.validDays ?: 0} 个有效日）。画面由「通常的你」参照驱动：${headline.public}"
        }
        val drift = snap.season.drift
        lines += when {
            drift > 0.25f -> "人生季节漂移较明显（drift ${drift.format(3)}，方向 ${snap.season.rhythmShift}）——湍流下限被抬高，画面比早期多一层缓慢波动。"
            drift > 0.05f -> "人生季节有轻微漂移（drift ${drift.format(3)}，方向 ${snap.season.rhythmShift}）——视觉只做极缓慢的调制。"
            else -> "人生季节稳定（drift ${drift.format(3)}）——视觉长期保持稳定，不随日噪声变化。"
        }
        val coherence = snap.appVisual.coherence
        lines += when {
            coherence > 0.6f -> "coherence ${coherence.format(2)} 偏高：同心环纹清晰、粒子收敛——今天的状态更「聚焦」。"
            coherence < 0.35f -> "coherence ${coherence.format(2)} 偏低：环纹更淡、粒子更散——今天的状态更「散」。"
            else -> "coherence ${coherence.format(2)} 中等：环纹与粒子处于平衡态。"
        }
        lines += "头部文案证据行：${headline.evidence}。"
        return lines.joinToString("\n\n")
    }

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)

    /**
     * 画廊首页（rendered/ 的相对链接；纯静态，浏览器直接打开）。
     * 目的：让人一眼看到所有画面，而不是翻文件。
     */
    fun writeGalleryIndex(root: File, profiles: List<QaProfileSpec>, days: List<Int>) {
        val surfaces = ReviewSurface.entries
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>ECHO Visual Review</title>")
        sb.appendLine("<style>body{background:#0b0b0d;color:#ccc;font-family:sans-serif;margin:24px}" +
            "h1,h2{color:#e8e8ea}img{width:240px;border-radius:8px;margin:4px}" +
            ".cell{display:inline-block;vertical-align:top;margin:8px}</style></head><body>")
        sb.appendLine("<h1>ECHO Visual Review（production 帧管线渲染）</h1>")
        sb.appendLine("<p>先看 <a href=\"rendered/sheets/users_APP_day180.png\">不同用户 Day 180</a> 与 " +
            "<a href=\"rendered/sheets/continuity_APP_PROFILE_A_STABLE.png\">同一用户连续性</a>。</p>")
        for (profile in profiles) {
            sb.appendLine("<h2>${profile.id}</h2>")
            for (day in days) {
                val dir = "rendered/${profile.id}/day%03d".format(day)
                sb.appendLine("<div class=\"cell\"><b>Day $day</b> · <a href=\"$dir/snapshot.md\">快照</a><br>")
                for (surface in surfaces) {
                    sb.appendLine("<a href=\"$dir/scene_${surface.name}.png\">" +
                        "<img src=\"$dir/scene_${surface.name}.png\" alt=\"${surface.name}\"></a>")
                }
                sb.appendLine("</div>")
            }
        }
        sb.appendLine("<h2>对比拼图（人眼必答题）</h2><div>")
        val sheetNames = listOf(
            "users_APP_day0", "users_APP_day7", "users_APP_day90", "users_APP_day180",
            "users_WALLPAPER_day7", "users_WALLPAPER_day90",
        ) + profiles.map { "continuity_APP_${it.id}" } + profiles.map { "maturity_APP_${it.id}" } +
            profiles.map { "motion_APP_${it.id}" } +
            // ERA 31 R20：真实帧率运动证据（静态期 4fps vs 过渡期 30fps，§16 人眼证据）
            listOf("PROFILE_A_STABLE", "PROFILE_G_WEEKEND_DIFFERENT").flatMap { id ->
                listOf("motion_wallpaper_idle4fps_$id", "motion_wallpaper_transition30fps_$id")
            }
        for (name in sheetNames) {
            sb.appendLine("<a href=\"rendered/sheets/$name.png\"><img src=\"rendered/sheets/$name.png\" alt=\"$name\"></a>")
        }
        sb.appendLine("</div></body></html>")
        root.resolve("index.html").writeText(sb.toString())
    }

    private fun com.yunjue.echo.mind.presence.EchoVisualParameters.summaryLine(): String =
        "flow=${flowSpeed.format(2)} coherence=${coherence.format(2)} turbulence=${turbulence.format(2)}" +
            " density=${particleDensity.format(2)} openness=${coreOpenness.format(2)}" +
            " dispersion=${dispersion.format(2)} pulse=${pulsePeriodSeconds.format(1)}s" +
            " depth=${depth.format(2)} brightness=${brightness.format(2)}" +
            " contrast=${contrast.format(2)} accent=${accentIntensity.format(2)}" +
            " structure=${structureComplexity.format(2)}"
}
