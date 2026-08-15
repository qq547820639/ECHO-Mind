package com.yunjue.echo.mind.qa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 19 §4 — Snapshot Suite：
 * 1. 为 7 个 profile × 6 个锚点日生成完整产品快照（六面 + Ask ECHO）并写入
 *    qa/reports/snapshots/（确定性，重复生成零漂移）；
 * 2. 对每个快照断言产品质量门（非空、克制、lock-safe、学习期文案）。
 */
class QaSnapshotSuiteTest {

    @Test
    fun generatesCompleteSnapshotsForEveryProfile() {
        QaSnapshotReport.writeAll()
        val snapshotsDir = java.io.File(QaSnapshotReport.repoRoot(), "${QaSnapshotReport.REPORTS_DIR}/snapshots")
        assertTrue("快照目录存在", snapshotsDir.isDirectory)
        for (profile in QaProfiles.ALL) {
            val file = java.io.File(snapshotsDir, "${profile.id}.md")
            assertTrue("${profile.id} 快照文件存在", file.isFile)
            val content = file.readText()
            for (section in listOf("ECHO Scene", "Why", "Journey", "Me · What ECHO Knows", "Wallpaper", "Dream", "Ask ECHO")) {
                assertTrue("${profile.id} 含 $section", content.contains(section))
            }
            for (day in QaProfiles.SNAPSHOT_DAYS) {
                assertTrue("${profile.id} 含 Day $day", content.contains("## Day $day"))
            }
        }
        assertTrue("索引存在", java.io.File(QaSnapshotReport.repoRoot(), "${QaSnapshotReport.REPORTS_DIR}/SNAPSHOT_INDEX.md").isFile)
    }

    @Test
    fun snapshotsAreDeterministic() {
        val a = QaSnapshotReport.reportFor(QaProfiles.E_PROJECT_CRUNCH)
        val b = QaSnapshotReport.reportFor(QaProfiles.E_PROJECT_CRUNCH)
        assertEquals(a, b)
    }

    @Test
    fun day0HeadlineIsFirstSightNotDataWarning() {
        for (profile in QaProfiles.ALL) {
            val s = QaProductSnapshot.snapshot(QaTimeline(profile), 0)
            assertFalse("$profile Day0 不得出现「数据不足」", s.scene.headline.public.contains("数据不足"))
            assertTrue("$profile Day0 有初见表达", s.scene.headline.public.isNotBlank())
            assertEquals("$profile Day0 无 AI 层（克制）", null, s.scene.headline.aiLayer)
        }
    }

    @Test
    fun headlineLayersAreDistinct() {
        val s = QaProductSnapshot.snapshot(QaTimeline(QaProfiles.E_PROJECT_CRUNCH), 75)
        assertTrue("public 一句话非空", s.scene.headline.public.isNotBlank())
        assertTrue("evidence 含数字对照", s.scene.headline.evidence.contains("通常") || s.scene.headline.evidence.contains("观察"))
        // 三层必须可区分：AI 层不重复 public
        s.scene.headline.aiLayer?.let { assertTrue(it != s.scene.headline.public) }
    }

    @Test
    fun whyLayerIsReadableInTenSeconds() {
        for (profile in QaProfiles.ALL) {
            val s = QaProductSnapshot.snapshot(QaTimeline(profile), 90)
            assertTrue("$profile Why ≤ 4 条（实际 ${s.why.facts.size}）", s.why.facts.size <= 4)
        }
    }

    @Test
    fun wallpaperIsPresenceOnly() {
        for (profile in QaProfiles.ALL) {
            val s = QaProductSnapshot.snapshot(QaTimeline(profile), 180)
            assertTrue(s.wallpaper.lockSafeNote.contains("Presence"))
            assertTrue("$profile 锁屏 flow ≤ 0.6（LOW 更静）", s.wallpaper.flowSpeed <= 0.6f)
        }
    }

    @Test
    fun askEchoAnswersCarryEvidence() {
        for (profile in QaProfiles.ALL) {
            val s = QaProductSnapshot.snapshot(QaTimeline(profile), 90)
            assertEquals(8, s.askEcho.size)
            for (a in s.askEcho) {
                assertTrue("${profile.id} 回答非空：${a.question}", a.answer.isNotBlank())
                assertTrue("${profile.id} 证据非空：${a.question}", a.evidence.isNotBlank())
            }
        }
    }

    @Test
    fun halfYearQuestionIsGroundedInThisPerson() {
        // B（夜猫+漂移）与 A（稳定）的半年回答必须不同且各自有数字证据
        val b = QaAskEcho.answer(QaTimeline(QaProfiles.B_NIGHT_OWL), 180, "这半年我有什么变化？")
        val a = QaAskEcho.answer(QaTimeline(QaProfiles.A_STABLE), 180, "这半年我有什么变化？")
        assertFalse("不同用户不得给通用答案", a.answer == b.answer)
        assertTrue("B 的半年回答带分钟证据", b.evidence.contains("分钟") || b.evidence.contains("起点"))
    }
}
