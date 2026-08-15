package com.yunjue.echo.mind.qa

import java.io.File

/**
 * ERA 19 §4 — 产品快照报告生成（markdown，确定性，可重复生成）。
 *
 * 输出：qa/reports/snapshots/PROFILE_<id>.md（每 profile 一个文件，
 * Day 0/3/7/28/90/180 纵向并排 → 同时展示「同一个 ECHO 在成长」）
 * 与 qa/reports/SNAPSHOT_INDEX.md。
 */
object QaSnapshotReport {

    const val REPORTS_DIR = "qa/reports"

    fun reportFor(profile: QaProfileSpec): String {
        val timeline = QaTimeline(profile)
        val sb = StringBuilder()
        sb.appendLine("# ${profile.id} — ${profile.displayName}")
        sb.appendLine()
        sb.appendLine("> ${profile.description}")
        sb.appendLine("> identitySeed=${profile.identitySeed} · motionPreference=${profile.motionPreference} · 安装日 ${QaProfiles.EPOCH_DATE}")
        sb.appendLine()

        for (day in QaProfiles.SNAPSHOT_DAYS) {
            val s = QaProductSnapshot.snapshot(timeline, day)
            sb.appendLine("## Day $day（${s.date}）")
            sb.appendLine()
            // ECHO Scene
            sb.appendLine("### ECHO Scene")
            sb.appendLine("- 成熟度：${s.scene.maturityName}")
            sb.appendLine("- 一句话：**${s.scene.headline.public}**")
            sb.appendLine("- 证据：${s.scene.headline.evidence}")
            s.scene.headline.aiLayer?.let { sb.appendLine("- AI 层：$it") }
            sb.appendLine("- 视觉：${s.scene.visual}")
            sb.appendLine()
            // Why
            sb.appendLine("### Why（${s.why.facts.size} 条证据）")
            if (s.why.facts.isEmpty()) sb.appendLine("- （尚无足够数据，不硬凑）") else s.why.facts.forEach { sb.appendLine("- $it") }
            sb.appendLine()
            // Journey
            sb.appendLine("### Journey")
            sb.appendLine("- ${s.journey.seasonLine}")
            sb.appendLine("- 日历 ${s.journey.calendarDays} 天 · 基线有效日 ${s.journey.baselineDays}")
            if (s.journey.landmarks.isNotEmpty()) {
                sb.appendLine("- 里程碑：")
                s.journey.landmarks.forEach { sb.appendLine("  - $it") }
            }
            if (s.journey.recentChanges.isNotEmpty()) {
                sb.appendLine("- 最近 30 天显著变化：")
                s.journey.recentChanges.forEach { sb.appendLine("  - $it") }
            }
            sb.appendLine()
            // Me
            sb.appendLine("### Me · What ECHO Knows")
            s.me.knows.forEach { sb.appendLine("- $it") }
            sb.appendLine()
            // Wallpaper
            sb.appendLine("### Wallpaper（LOCK_SAFE）")
            sb.appendLine("- flow=${"%.2f".format(s.wallpaper.flowSpeed)} coherence=${"%.2f".format(s.wallpaper.coherence)} turbulence=${"%.2f".format(s.wallpaper.turbulence)} pulse=${"%.1f".format(s.wallpaper.pulsePeriod)}s · 粒子 ${s.wallpaper.particleCount}")
            sb.appendLine("- ${s.wallpaper.lockSafeNote}")
            sb.appendLine()
            // Dream
            sb.appendLine("### Dream")
            sb.appendLine("- ${s.dream.line}")
            sb.appendLine()
            // Ask ECHO
            sb.appendLine("### Ask ECHO")
            s.askEcho.forEach { a ->
                sb.appendLine("- **${a.question}**")
                sb.appendLine("  - ${a.answer}")
                sb.appendLine("  - 证据：${a.evidence}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun indexFor(): String {
        val sb = StringBuilder()
        sb.appendLine("# ECHO Mind — 长期用户 Fixture 产品快照索引")
        sb.appendLine()
        sb.appendLine("Product Quality Era（ERA 19）确定性 fixture：7 个 profile × Day 0/3/7/28/90/180。")
        sb.appendLine("同一文件内纵向并排 → 检验「同一个 ECHO 在成长」；跨文件横向比较 → 检验「不同用户明显不是同一个 ECHO」。")
        sb.appendLine()
        sb.appendLine("| Profile | 人群形状 | 快照 |")
        sb.appendLine("|---|---|---|")
        for (p in QaProfiles.ALL) {
            sb.appendLine("| ${p.id} | ${p.displayName} | [snapshots/${p.id}.md](snapshots/${p.id}.md) |")
        }
        sb.appendLine()
        sb.appendLine("相关文档：")
        sb.appendLine("- [PRODUCT_EXPERIENCE_REVIEW.md](PRODUCT_EXPERIENCE_REVIEW.md) — 逐阶段体验审查（ERA 19 §5）")
        sb.appendLine("- [ECHO_SCENE_AUDIT.md](ECHO_SCENE_AUDIT.md) — ECHO Scene 产品质量审计（ERA 20 §8）")
        sb.appendLine("- [PERSONAL_REASONING_EVAL.md](PERSONAL_REASONING_EVAL.md) — Personal Reasoning 质量 eval（ERA 22 / Batch 2）")
        sb.appendLine("- [MEMORY_SELF_MODEL_EVAL.md](MEMORY_SELF_MODEL_EVAL.md) — Memory / Self Model 质量 eval（ERA 23 / Batch 3）")
        sb.appendLine()
        sb.appendLine("生成方式：`QaSnapshotSuiteTest`（:feature:qa 单元测试）确定性重放生成，可随时重复生成零漂移。")
        return sb.toString()
    }

    /** 定位仓库根（从测试 JVM 的 user.dir 向上找同时含 android/ 与 backend/ 的目录）。 */
    fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "android").isDirectory && File(dir, "backend").isDirectory) return dir
            dir = dir.parentFile
        }
        error("无法定位仓库根（user.dir=${System.getProperty("user.dir")}）")
    }

    fun writeAll() {
        val reportsDir = File(repoRoot(), REPORTS_DIR)
        val snapshotsDir = File(reportsDir, "snapshots")
        snapshotsDir.mkdirs()
        for (p in QaProfiles.ALL) {
            File(snapshotsDir, "${p.id}.md").writeText(reportFor(p))
        }
        File(reportsDir, "SNAPSHOT_INDEX.md").writeText(indexFor())
    }
}
