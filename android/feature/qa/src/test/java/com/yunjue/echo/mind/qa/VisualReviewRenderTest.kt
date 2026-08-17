package com.yunjue.echo.mind.qa

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * ERA 31 §5/§6 — Real Render Review 工件生成器。
 *
 * 用 **production 帧管线**（QaTimeline 长程 fixture → presence → EchoVisualMapper →
 * computeEchoSceneFrame → renderEchoFrameToCanvas）渲染真实 PNG 到 `qa/visual-review/`：
 * - 7 profile × Day 0/7/28/90/180 × 5 表面（APP / HOME_WALLPAPER / LOCK_SAFE / DREAM / CANONICAL_JOURNEY）
 * - 每格参数快照 + 状态解释（snapshot.md）
 * - 人眼评审拼图：不同用户对比 / 同一用户连续性 / 成熟度演进
 * - index.html 画廊
 *
 * 断言只覆盖「工件真实生成」与「人眼必答题的像素级代理」：
 * 同 profile 跨天 accent 色相恒定（同一个 ECHO），不同 profile 主色不同（不是换皮）。
 * 帧哈希回归仍由 VisualRegressionGoldenTest 承担；PNG 是人看工件，不是字节黄金。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VisualReviewRenderTest {

    /** 先跑矩阵（清空并重写 rendered/），再跑拼图——名称排序保证顺序。 */
    @Test
    fun a_renderFullMatrixWritesAllArtifacts() {
        val root = outputRoot()
        val rendered = File(root, "rendered")
        if (rendered.exists()) rendered.deleteRecursively()
        rendered.mkdirs()

        var pngCount = 0
        var sidecarCount = 0
        for (profile in QaProfiles.ALL) {
            for (day in QaProfiles.SNAPSHOT_DAYS) {
                val snap = snapshot(profile, day)
                val dir = dayDir(rendered, profile, day)
                for (surface in VisualReviewRenderer.ReviewSurface.entries) {
                    val frame = VisualReviewRenderer.frameFor(snap, surface)
                    assertNotNull("${profile.id} day$day ${surface.name} 帧为 null（不应发生）", frame)
                    val nonNullFrame = frame ?: error("帧为 null（assertNotNull 已失败）")
                    val bitmap = VisualReviewRenderer.renderFrame(nonNullFrame, surface.width, surface.height)
                    val file = File(dir, "scene_${surface.name}.png")
                    VisualReviewRenderer.writePng(bitmap, file)
                    bitmap.recycle()
                    assertPng(file)
                    pngCount++
                }
                VisualReviewRenderer.writeSnapshotMarkdown(snap, QaTimeline(profile), File(dir, "snapshot.md"))
                sidecarCount++
            }
        }
        val expectedPng = QaProfiles.ALL.size * QaProfiles.SNAPSHOT_DAYS.size *
            VisualReviewRenderer.ReviewSurface.entries.size
        assertEquals("矩阵 PNG 数量", expectedPng, pngCount)
        assertEquals("矩阵快照数量", QaProfiles.ALL.size * QaProfiles.SNAPSHOT_DAYS.size, sidecarCount)
        VisualReviewRenderer.writeGalleryIndex(root, QaProfiles.ALL, QaProfiles.SNAPSHOT_DAYS)
        assertTrue("画廊首页生成", File(root, "index.html").exists())
    }

    @Test
    fun b_comparisonSheetsCoverUsersContinuityAndMaturity() {
        val root = outputRoot()
        val sheets = File(root, "rendered/sheets").apply { mkdirs() }

        // 1) 不同用户 × 同一天（人眼必答：不看名字，是否明显不同？）
        for (day in listOf(0, 7, 90, 180)) {
            writeSheet(
                cells = QaProfiles.ALL.map { cell(it, day, VisualReviewRenderer.ReviewSurface.APP, short = true) },
                columns = 7,
                title = "APP · Day $day · 7 个不同用户",
                file = File(sheets, "users_APP_day$day.png"),
            )
        }
        for (day in listOf(7, 90)) {
            writeSheet(
                cells = QaProfiles.ALL.map { cell(it, day, VisualReviewRenderer.ReviewSurface.HOME_WALLPAPER, short = true) },
                columns = 7,
                title = "HOME_WALLPAPER · Day $day · 7 个不同用户",
                file = File(sheets, "users_WALLPAPER_day$day.png"),
            )
        }

        // 2) 同一用户 × 跨天（人眼必答：Day 7/90/180 是不是同一个 ECHO 在成长？）
        for (profile in QaProfiles.ALL) {
            val days = listOf(0, 7, 28, 90, 180)
            writeSheet(
                cells = days.map { cell(profile, it, VisualReviewRenderer.ReviewSurface.APP, short = true) },
                columns = 5,
                title = "${profile.id} · APP · Day 0→180 连续性",
                file = File(sheets, "continuity_APP_${profile.id}.png"),
            )
            writeSheet(
                cells = days.map { cell(profile, it, VisualReviewRenderer.ReviewSurface.HOME_WALLPAPER, short = true) },
                columns = 5,
                title = "${profile.id} · HOME_WALLPAPER · Day 0→180 连续性",
                file = File(sheets, "continuity_WALLPAPER_${profile.id}.png"),
            )
        }

        // 3) 成熟度五态（SEED→MATURE：Day 0/1/3/7/28）
        for (profile in QaProfiles.ALL) {
            val stages = listOf(0, 1, 3, 7, 28)
            writeSheet(
                cells = stages.map { cell(profile, it, VisualReviewRenderer.ReviewSurface.APP, short = true) },
                columns = 5,
                title = "${profile.id} · 成熟度演进 SEED→DISCOVERING→EMERGING→KNOWN→MATURE",
                file = File(sheets, "maturity_APP_${profile.id}.png"),
            )
        }

        // 4) 运动序列（Part 6「short animation captures where technically feasible」）：
        //    静态帧无法体现 motion character，用 36s 时间序列拼图表达运动（呼吸 + 轨道 + 流线方向）
        for (profile in QaProfiles.ALL) {
            val snap = snapshot(profile, 90)
            val genome = com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
                com.yunjue.echo.mind.presence.EchoVisualMapper.map(snap.presence, 12f),
                snap.presence.identityGenome,
            )
            val cells = (0 until 12).map { step ->
                val time = step * 3f
                val frame = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                    com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                        genome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE, time,
                    ),
                    1080f, 2340f,
                )
                VisualReviewRenderer.SheetCell("t=${step * 3}s", renderPng(frame))
            }
            writeSheet(
                cells = cells,
                columns = 6,
                title = "${profile.id} · APP · 36s 运动序列（3s/帧）",
                file = File(sheets, "motion_APP_${profile.id}.png"),
            )
        }

        // 5) ERA 31 R20：真实帧率运动证据——静态期 4fps（250ms/帧，R13 策略）与
        //    过渡期 30fps（33ms/帧）按真实采样间隔渲染（§16 Battery Reality 的人眼证据：
        //    静态期不是停帧也不是跳帧，是缓慢呼吸）。
        val realRateProfiles = QaProfiles.ALL.filter {
            it.id in setOf("PROFILE_A_STABLE", "PROFILE_G_WEEKEND_DIFFERENT")
        }
        for (profile in realRateProfiles) {
            val snap = snapshot(profile, 90)
            val lockGenome = com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
                com.yunjue.echo.mind.presence.EchoVisualMapper.map(snap.presence, 12f),
                snap.presence.identityGenome,
            )
            val idleCells = (0 until 12).map { step ->
                val frame = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                    com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                        lockGenome, com.yunjue.echo.mind.visual.surface.EchoSurface.WALLPAPER_VISUAL_ONLY,
                        600f + step * 0.25f,
                    ),
                    1080f, 2400f,
                )
                VisualReviewRenderer.SheetCell("+${step * 250}ms", renderWallpaperPng(frame))
            }
            writeSheet(
                cells = idleCells,
                columns = 6,
                title = "${profile.id} · WALLPAPER · 静态期真实 4fps（250ms/帧 × 3s）",
                file = File(sheets, "motion_wallpaper_idle4fps_${profile.id}.png"),
            )
            val transitionCells = (0 until 12).map { step ->
                val frame = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                    com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                        lockGenome, com.yunjue.echo.mind.visual.surface.EchoSurface.WALLPAPER_VISUAL_ONLY,
                        600f + step * 0.033f,
                    ),
                    1080f, 2400f,
                )
                VisualReviewRenderer.SheetCell("+${step * 33}ms", renderWallpaperPng(frame))
            }
            writeSheet(
                cells = transitionCells,
                columns = 6,
                title = "${profile.id} · WALLPAPER · 过渡期 30fps（33ms/帧 × 0.4s）",
                file = File(sheets, "motion_wallpaper_transition30fps_${profile.id}.png"),
            )
        }

        val manifest = StringBuilder()
        sheets.listFiles()?.sortedBy { it.name }?.forEach { manifest.appendLine(it.name) }
        File(sheets, "_MANIFEST.txt").writeText(manifest.toString())
        assertTrue("拼图已生成", File(sheets, "users_APP_day90.png").exists())
        assertTrue("运动序列已生成", File(sheets, "motion_APP_PROFILE_A_STABLE.png").exists())
        assertTrue(
            "真实帧率运动证据已生成",
            File(sheets, "motion_wallpaper_idle4fps_PROFILE_A_STABLE.png").exists(),
        )
    }

    @Test
    fun c_humanQuestionsHavePixelLevelProxies() {
        // 人眼必答题的机器代理（不能替代人眼，但能拦住「全黑/全同/换皮」级别的失败）：
        // a) 同一用户跨天：accent 主色恒同（色相属于 Identity 不随天变）；
        // b) 不同用户：主色互不相同（Identity 不同 → 画面不同）；
        // c) 帧不是纯色（确实画了东西）。
        val accents = HashMap<String, Int>()
        for (profile in QaProfiles.ALL) {
            val day0 = frameFor(profile, 0, VisualReviewRenderer.ReviewSurface.APP)
            val day180 = frameFor(profile, 180, VisualReviewRenderer.ReviewSurface.APP)
            assertEquals("${profile.id} Day0/Day180 accent RGB 恒同（同一个 ECHO）",
                day0.frontMembrane.color and 0xFFFFFF, day180.frontMembrane.color and 0xFFFFFF)
            accents[profile.id] = day0.frontMembrane.color and 0xFFFFFF
            assertDistinctPixels(renderPng(day0), profile.id)
        }
        val distinctAccents = accents.values.toSet()
        assertTrue("7 个用户的 accent 主色至少 4 种（不同用户画面不同）：$accents", distinctAccents.size >= 4)

        // d) ERA 31 结构可见性：用户差异必须来自结构（纹理族/环数/流线），不能只换颜色
        val structureKeys = HashSet<String>()
        for (profile in QaProfiles.ALL) {
            val frame = frameFor(profile, 180, VisualReviewRenderer.ReviewSurface.APP)
            val glints = frame.particles.count {
                it.kind == com.yunjue.echo.mind.visual.render.ParticleKind.GLINT
            }
            structureKeys += "${frame.structuralRings.size}|${frame.longFilaments.size}|${frame.localFragments.size}|$glints"
        }
        assertTrue(
            "7 个用户的结构签名至少 4 种（差异来自 texture/structure，不是只换颜色）：$structureKeys",
            structureKeys.size >= 4,
        )

        // e) 运动：粒子随时间真实移动（生命感机器代理），且不同用户运动幅度不同（motion personality）
        val displacements = HashMap<String, Double>()
        for (profile in QaProfiles.ALL) {
            val snap = snapshot(profile, 90)
            val genome = com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
                com.yunjue.echo.mind.presence.EchoVisualMapper.map(snap.presence, 12f),
                snap.presence.identityGenome,
            )
            val t0 = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                    genome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE, 0f,
                ),
                1080f, 2340f,
            )
            val t6 = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                    genome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE, 6f,
                ),
                1080f, 2340f,
            )
            val meanDisp = t0.particles.indices.sumOf { i ->
                val a = t0.particles[i]
                val b = t6.particles[i]
                val dx = a.x.toDouble() - b.x
                val dy = a.y.toDouble() - b.y
                kotlin.math.sqrt(dx * dx + dy * dy)
            } / t0.particles.size
            displacements[profile.id] = meanDisp
            assertTrue("${profile.id} 6 秒内粒子真实移动（disp=$meanDisp）", meanDisp > 0.0005)
        }
        val distinctDisp = displacements.values.map { (it * 1000).toInt() }.toSet()
        assertTrue(
            "不同用户运动幅度不同（motion personality 可见）：$displacements",
            distinctDisp.size >= 3,
        )
    }

    // ===== 工具 =====

    /** 测试进程内快照缓存（跨 @Test 方法共享，避免重复长程回放）。 */
    private companion object {
        val snapCache = HashMap<String, HashMap<Int, QaDaySnapshot>>()
    }

    private fun snapshot(profile: QaProfileSpec, day: Int): QaDaySnapshot {
        val byDay = snapCache.getOrPut(profile.id) { HashMap() }
        byDay[day]?.let { return it }
        val timeline = QaTimeline(profile)
        for (d in 0..day) {
            if (!byDay.containsKey(d)) byDay[d] = timeline.snapshot(d)
        }
        return byDay.getValue(day)
    }

    private fun frameFor(profile: QaProfileSpec, day: Int, surface: VisualReviewRenderer.ReviewSurface) =
        VisualReviewRenderer.frameFor(snapshot(profile, day), surface).also {
            assertNotNull("${profile.id} day$day 帧为 null", it)
        }!!

    private fun dayDir(rendered: File, profile: QaProfileSpec, day: Int): File =
        File(File(rendered, profile.id), "day%03d".format(day))

    private fun renderPng(frame: com.yunjue.echo.mind.visual.render.OrganismFrame): Bitmap =
        VisualReviewRenderer.renderFrame(frame, 1080, 2340)

    private fun renderWallpaperPng(frame: com.yunjue.echo.mind.visual.render.OrganismFrame): Bitmap =
        VisualReviewRenderer.renderFrame(
            frame,
            VisualReviewRenderer.WALLPAPER_WIDTH,
            VisualReviewRenderer.WALLPAPER_HEIGHT,
        )

    private fun cell(
        profile: QaProfileSpec,
        day: Int,
        surface: VisualReviewRenderer.ReviewSurface,
        short: Boolean,
    ): VisualReviewRenderer.SheetCell {
        val frame = frameFor(profile, day, surface)
        val bitmap = renderPng(frame)
        val label = if (short) "${profile.id.replace("PROFILE_", "").first()}·D$day" else "${profile.id} · Day $day"
        return VisualReviewRenderer.SheetCell(label, bitmap)
    }

    private fun writeSheet(
        cells: List<VisualReviewRenderer.SheetCell>,
        columns: Int,
        title: String,
        file: File,
    ) {
        val sheet = VisualReviewRenderer.contactSheet(cells, columns, cellWidth = 300, title = title)
        VisualReviewRenderer.writePng(sheet, file)
        sheet.recycle()
        cells.forEach { it.bitmap.recycle() }
        assertPng(file)
    }

    private fun assertPng(file: File) {
        assertTrue("PNG 存在: $file", file.exists())
        val bytes = file.readBytes()
        assertTrue("PNG 非空: $file", bytes.size > 1000)
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue("PNG magic: $file", bytes.take(8).toByteArray().contentEquals(magic))
    }

    private fun assertDistinctPixels(bitmap: Bitmap, label: String) {
        val sampled = HashMap<Int, Int>()
        val stride = 17
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                sampled[pixel] = (sampled[pixel] ?: 0) + 1
                y += stride
            }
            x += stride
        }
        bitmap.recycle()
        assertTrue("$label 帧非纯色（采样到 ${sampled.size} 种像素）", sampled.size >= 100)
    }

    /** 输出根目录：系统属性覆盖，否则向上找到仓库根（含 .git 与顶层 qa/）的 qa/visual-review。 */
    private fun outputRoot(): File {
        System.getProperty("echo.visualReview.out")?.let { return File(it) }
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null && !File(dir, ".git").exists() && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        val gradleRoot = dir ?: start
        // 仓库根 = 含 .git 的目录（android/ 是 Gradle 根但不是仓库根）
        var root: File? = gradleRoot
        while (root != null && !File(root, ".git").exists()) root = root.parentFile
        return File(root ?: gradleRoot, "qa/visual-review")
    }
}
