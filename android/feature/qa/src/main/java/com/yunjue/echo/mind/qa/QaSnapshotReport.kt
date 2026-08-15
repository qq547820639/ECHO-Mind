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
        sb.appendLine("- [JOURNEY_EMOTIONAL_VALUE_EVAL.md](JOURNEY_EMOTIONAL_VALUE_EVAL.md) — Journey 情感价值 eval（ERA 24 / Batch 4）")
        sb.appendLine("- [ME_TRUST_EXPERIENCE_EVAL.md](ME_TRUST_EXPERIENCE_EVAL.md) — Me / Trust Experience eval（ERA 25 / Batch 5）")
        sb.appendLine("- [PRODUCT_SIMPLIFICATION_EVAL.md](PRODUCT_SIMPLIFICATION_EVAL.md) — 简化 / 模块卫生（ERA 26/28 / Batch 6）")
        sb.appendLine("- [RELEASE_QUALITY_GATE.md](RELEASE_QUALITY_GATE.md) — 发布质量门（Batch 8 收官）")
        sb.appendLine("- [../DOGFOOD_PROTOCOL.md](../DOGFOOD_PROTOCOL.md) — 30 天 dogfood 协议（ERA 29 §63）")
        sb.appendLine("- [ERA31_VISUAL_REVIEW_R1.md](ERA31_VISUAL_REVIEW_R1.md) — ERA 31 Real Render Review R1（真实画面缺陷修复闭环）")
        sb.appendLine("- [ERA31_ROUND2.md](ERA31_ROUND2.md) — ERA 31 R2（跨语言黄金门 + 运动序列 + Core Personal Reasoning Set）")
        sb.appendLine("- [CORE_PERSONAL_REASONING_SET.md](CORE_PERSONAL_REASONING_SET.md) — BATCH 2 §20 最高价值问题集（26 条）")
        sb.appendLine("- [QA_MIRROR_AUDIT.md](QA_MIRROR_AUDIT.md) — :feature:qa 与 production 重复实现审计（ERA 31 BATCH 1）")
        sb.appendLine("- [PERSONAL_REASONING_HUMAN_REVIEW.md](PERSONAL_REASONING_HUMAN_REVIEW.md) — BATCH 2 真实回答四层人审（R3/R4）")
        sb.appendLine("- [SELF_MODEL_VALUE_AUDIT.md](SELF_MODEL_VALUE_AUDIT.md) — BATCH 3 Self Model 价值审计（R4/R5）")
        sb.appendLine("- [ERA31_ROUND5.md](ERA31_ROUND5.md) — ERA 31 R5（BATCH 3 收口 / Journey 第一视觉修复）")
        sb.appendLine("- [ERA31_ROUND6.md](ERA31_ROUND6.md) — ERA 31 R6（BATCH 4 收口：时间地标入 Journey / Time-to-ECHO 指标）")
        sb.appendLine("- [TIME_TO_ECHO.md](TIME_TO_ECHO.md) — BATCH 5 §50 Time-to-ECHO 测量契约")
        sb.appendLine("- [ERA31_ROUND7.md](ERA31_ROUND7.md) — ERA 31 R7（Dogfood 准备 / Delete Audit / QA mirror 收尾）")
        sb.appendLine("- [DELETE_AUDIT.md](DELETE_AUDIT.md) — BATCH 7 §39/§40 删除与订阅审计")
        sb.appendLine("- [ERA31_ROUND8.md](ERA31_ROUND8.md) — ERA 31 R8（core:ports 依赖倒置收官，模块化停止）")
        sb.appendLine("- [ERA31_ROUND10.md](ERA31_ROUND10.md) — ERA 31 R9/R10（v0.10.0 Release Closure 全绿）")
        sb.appendLine("- [ERA31_ROUND11.md](ERA31_ROUND11.md) — ERA 31 R11（Headline 自然句优先 / What ECHO Knows 人类语言）")
        sb.appendLine("- [ERA31_ROUND12.md](ERA31_ROUND12.md) — ERA 31 R12（ECHO Scene 全链路走查：日期降噪）")
        sb.appendLine("- [ERA31_ROUND13.md](ERA31_ROUND13.md) — ERA 31 R13（§16 Wallpaper 自适应帧率：静态期 4fps）")
        sb.appendLine("- [ERA31_ROUND14.md](ERA31_ROUND14.md) — ERA 31 R14（Dream 表面自适应帧率对齐）")
        sb.appendLine("- [ERA31_ROUND15.md](ERA31_ROUND15.md) — ERA 31 R15（Why 层安静化：AI 催促移除 + 入口一致化）")
        sb.appendLine("- [ERA31_ROUND16.md](ERA31_ROUND16.md) — ERA 31 R16（进程死亡恢复锚点：快照 commit 落盘 + 恢复回归测试）")
        sb.appendLine("- [ERA31_ROUND17.md](ERA31_ROUND17.md) — ERA 31 R17（Ask ECHO 免费用户走查：AI 催促清零 + 问法归一）")
        sb.appendLine("- [ERA31_ROUND18.md](ERA31_ROUND18.md) — ERA 31 R18（§22 Correction Reuse：纠正 → 上下文推理桥梁）")
        sb.appendLine("- [ERA31_ROUND19.md](ERA31_ROUND19.md) — ERA 31 R19（Journey 第一眼走查：免费用户叙事说人话 + 河流锚定真实数据）")
        sb.appendLine("- [ERA31_ROUND20.md](ERA31_ROUND20.md) — ERA 31 R20（Wallpaper 运动现实检查：4fps 静态期实测 + 真实帧率人眼证据）")
        sb.appendLine("- [ERA31_ROUND21.md](ERA31_ROUND21.md) — ERA 31 R21（What ECHO Knows 10 秒可读：人称与方向统一）")
        sb.appendLine("- [ERA31_ROUND22.md](ERA31_ROUND22.md) — ERA 31 R22（Day-0 苏醒：第一次见面就是「这个 ECHO」）")
        sb.appendLine("- [ERA31_ROUND23.md](ERA31_ROUND23.md) — ERA 31 R23（Day-7 Why 可懂：开始活跃的「变化」直接说分钟数）")
        sb.appendLine("- [ERA31_ROUND24.md](ERA31_ROUND24.md) — ERA 31 R24（Ask ECHO 依据诚实化：不再一律「参考了：历史画像」）")
        sb.appendLine("- [ERA31_ROUND25.md](ERA31_ROUND25.md) — ERA 31 R25（Ask ECHO 证据说人话：z 分数与工程键退役）")
        sb.appendLine("- [ERA31_ROUND26.md](ERA31_ROUND26.md) — ERA 31 R26（Me 检查台走查：麦克风双控制去重）")
        sb.appendLine("- [ERA31_ROUND27.md](ERA31_ROUND27.md) — ERA 31 R27（Journey 一条河流：河段故事并入主河流）")
        sb.appendLine("- [ERA31_ROUND28.md](ERA31_ROUND28.md) — ERA 31 R28（Scene 早期状态去仪表化：进度条与覆盖率条退役）")
        sb.appendLine("- [ERA31_ROUND29.md](ERA31_ROUND29.md) — ERA 31 R29（Scene Action 安静化：按钮墙折叠成单一入口）")
        sb.appendLine("- [ERA31_ROUND30.md](ERA31_ROUND30.md) — ERA 31 R30（文案一致性收口：引号规范 + 重新生成按钮人话化）")
        sb.appendLine("- [ERA31_ROUND31.md](ERA31_ROUND31.md) — ERA 31 R31（苏醒 = 第一次 Presence：Day-0 SEED 单一构建点）")
        sb.appendLine("- [ERA31_ROUND32.md](ERA31_ROUND32.md) — ERA 31 R32（关键验收契约锁：UI 建议同步 + 纠正复用全链）")
        sb.appendLine("- [ERA31_ROUND33.md](ERA31_ROUND33.md) — ERA 31 R33（Release Integrity 复核：全量本地预检 + Baseline 文档对齐）")
        sb.appendLine("- [personal_answer_review/answers.md](personal_answer_review/answers.md) — Core Set 26 问真实回答捕获（机器生成）")
        sb.appendLine("- [../visual-review/index.html](../visual-review/index.html) — ERA 31 真实渲染画廊（浏览器打开直接看 ECHO）")
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
