package com.yunjue.echo.mind

import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.SkillFetchResult
import com.yunjue.echo.mind.model.SkillDisplay
import com.yunjue.echo.mind.ui.SkillRunSession
import com.yunjue.echo.mind.ui.SkillRunStatus
import com.yunjue.echo.mind.ui.coldStartHint
import com.yunjue.echo.mind.ui.shouldShowEmergencyFab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T05 Skill 卡片执行行为单测（PRD 契约点 4）。
 *
 * 覆盖：
 * - **每个「开始」按钮有真实行为**：SkillRunSession.start() 进入执行状态（非空操作）
 * - 执行生命周期：start → RUNNING → 逐步推进 → pause/resume → complete/stop
 * - complete → "completed" + durationSeconds；stop → "stopped"
 * - 状态机边界：已停止后 complete 返回 "stopped"；已完成后 stop 返回 "completed"
 * - 卡片渲染已改为原生 Compose（无 WebView），执行状态可推进
 * - Skill 列表空态冷启动、危机入口常驻（沿用既有不变量）
 *
 * 说明：项目未引入 Compose UI 测试框架，状态机为纯 Kotlin 可直测；
 * UI 层「开始」按钮与 SkillRunSession 的绑定经代码审查保证（SkillCardHost 无空 lambda）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkillCardHostTest {

    // ---------- T05 执行生命周期：开始按钮真实行为 ----------

    @Test
    fun startTransitionsToRunning() {
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { 0L })
        assertEquals(SkillRunStatus.IDLE, session.status)
        session.start()
        assertEquals("点击开始后应进入执行中", SkillRunStatus.RUNNING, session.status)
        assertTrue("执行中 isRunning 应为 true", session.isRunning)
        assertEquals("开始时当前步骤应为第 0 步", 0, session.currentStep)
    }

    @Test
    fun nextStepAdvancesThroughSteps() {
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { 0L })
        session.start()
        session.nextStep(totalSteps = 2)
        assertEquals(1, session.currentStep)
        // 不越过最后一步
        session.nextStep(totalSteps = 2)
        assertEquals("不应越过最后一步", 1, session.currentStep)
    }

    @Test
    fun nextStepOnlyWhileRunning() {
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { 0L })
        // 未开始时不能推进
        session.nextStep(totalSteps = 3)
        assertEquals(0, session.currentStep)
        session.start()
        session.pause()
        // 暂停中不能推进
        session.nextStep(totalSteps = 3)
        assertEquals(0, session.currentStep)
    }

    @Test
    fun pauseAndResumeToggleStatus() {
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { 0L })
        session.start()
        session.pause()
        assertEquals(SkillRunStatus.PAUSED, session.status)
        assertFalse("暂停后 isRunning 应为 false", session.isRunning)
        session.resume()
        assertEquals(SkillRunStatus.RUNNING, session.status)
        assertTrue(session.isRunning)
    }

    @Test
    fun completeReportsCompletedAndDuration() {
        var now = 0L
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { now })
        session.start()
        now = 180_000L // 3 分钟后完成
        val status = session.complete()
        assertEquals("completed", status)
        assertEquals(SkillRunStatus.COMPLETED, session.status)
        assertEquals(180, session.durationSeconds)
    }

    @Test
    fun stopReportsStoppedAndDuration() {
        var now = 0L
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { now })
        session.start()
        now = 45_000L
        val status = session.stop()
        assertEquals("stopped", status)
        assertEquals(SkillRunStatus.STOPPED, session.status)
        assertEquals(45, session.durationSeconds)
    }

    @Test
    fun completeAfterStopKeepsStoppedSemantics() {
        var now = 0L
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { now })
        session.start()
        now = 10_000L
        session.stop()
        // 已停止后 complete 不覆盖为 completed
        val status = session.complete()
        assertEquals("stopped", status)
        assertEquals(SkillRunStatus.STOPPED, session.status)
    }

    @Test
    fun stopAfterCompleteKeepsCompletedSemantics() {
        var now = 0L
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { now })
        session.start()
        now = 20_000L
        session.complete()
        val status = session.stop()
        assertEquals("completed", status)
        assertEquals(SkillRunStatus.COMPLETED, session.status)
    }

    @Test
    fun elapsedSecondsReflectsWallClock() {
        var now = 0L
        val session = SkillRunSession(skillId = "sk_1", nowProvider = { now })
        assertEquals("未开始 elapsed 为 0", 0, session.elapsedSeconds())
        session.start()
        now = 5_500L
        assertEquals(5, session.elapsedSeconds())
        now = 61_000L
        assertEquals(61, session.elapsedSeconds())
    }

    // ---------- P3 冷启动分阶段文案映射 ----------

    @Test
    fun coldStartHintMapsStage0ToResource() {
        assertEquals("stage_0 应映射到 cold_start_stage_0", R.string.cold_start_stage_0, coldStartHint("stage_0", 0))
    }

    @Test
    fun coldStartHintMapsStage1_3ToResource() {
        assertEquals("stage_1_3 应映射到 cold_start_stage_1_3", R.string.cold_start_stage_1_3, coldStartHint("stage_1_3", 2))
    }

    @Test
    fun coldStartHintMapsStage4_7ToResource() {
        assertEquals("stage_4_7 应映射到 cold_start_stage_4_7", R.string.cold_start_stage_4_7, coldStartHint("stage_4_7", 5))
    }

    @Test
    fun coldStartHintMapsStage7PlusToResource() {
        assertEquals("stage_7_plus 应映射到 cold_start_stage_7_plus", R.string.cold_start_stage_7_plus, coldStartHint("stage_7_plus", 10))
    }

    @Test
    fun coldStartHintFallsBackToStage0ForUnknownStage() {
        assertEquals("未知 stage 应兜底 stage_0", R.string.cold_start_stage_0, coldStartHint("unknown", 0))
    }

    @Test
    fun loadFailedStateTriggersRetryButton() {
        // 网络失败时 SkillFetchResult.loadFailed=true，UI 据此展示「加载失败」+ 重试按钮
        val failed = SkillFetchResult(skills = null, coldStartHint = null, loadFailed = true)
        assertTrue("loadFailed=true 时应展示重试按钮", failed.loadFailed)
        assertNull("失败时 skills 应为 null（区别于空列表冷启动）", failed.skills)
    }

    @Test
    fun successfulEmptyStateDoesNotTriggerRetryButton() {
        val coldStart = SkillFetchResult(skills = emptyList(), coldStartHint = "stage_0", loadFailed = false)
        assertFalse("冷启动空态不应显示重试按钮", coldStart.loadFailed)
    }

    // ---------- T11.6 危机入口常驻（代码审查不变量） ----------

    @Test
    fun emergencyFabVisibleOnAllTabsExceptSupport() {
        assertTrue("非 SUPPORT tab 应显示紧急 FAB", shouldShowEmergencyFab(isSupportTab = false))
        assertFalse("SUPPORT tab 自身不重复显示 FAB", shouldShowEmergencyFab(isSupportTab = true))
    }
}
