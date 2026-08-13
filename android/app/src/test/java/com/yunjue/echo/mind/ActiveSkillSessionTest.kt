package com.yunjue.echo.mind

import com.yunjue.echo.mind.data.ActiveSkillSessionEntity
import com.yunjue.echo.mind.ui.SkillRunSession
import com.yunjue.echo.mind.ui.SkillRunStatus
import com.yunjue.echo.mind.ui.SkillTerminal
import com.yunjue.echo.mind.ui.settleTerminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 ActiveSkillSession 持久化 + 时长语义单测（纯 JVM + fake clock）：
 *
 * - 暂停不计时：start → pause → wait → resume → complete，pause 时间不进入 active duration
 * - pause 期间 activeDurationMs 不增长
 * - toEntity / restoreFromEntity round-trip；进程死亡恢复统一为 PAUSED（不虚增时长）
 * - 终态 duration 包含最后一段（RUNNING 直接 complete）
 * - completion 幂等由 event_id 承载（SkillCompletionInput 每次生成新 event_id）
 */
class ActiveSkillSessionTest {

    private class FakeClock(var now: Long = 0L) {
        fun provider(): () -> Long = { now }
    }

    @Test
    fun pauseTimeIsNotCountedInActiveDuration() {
        val clock = FakeClock(0L)
        val session = SkillRunSession("sk_1", nowProvider = clock.provider())
        session.start() // t=0
        clock.now = 30_000L
        session.pause() // 活动段 30s 计入 accumulated
        clock.now = 90_000L // 暂停 60s（不计入）
        session.resume()
        clock.now = 100_000L // 恢复后活动 10s
        session.complete()

        assertEquals("pause 时间不应进入 active duration（30s + 10s = 40s）", 40, session.durationSeconds)
    }

    @Test
    fun activeDurationDoesNotGrowWhilePaused() {
        val clock = FakeClock(0L)
        val session = SkillRunSession("sk_1", nowProvider = clock.provider())
        session.start()
        clock.now = 20_000L
        session.pause()
        val atPause = session.activeDurationMs(20_000L)
        clock.now = 200_000L // 暂停很久
        assertEquals("暂停期间 activeDuration 不增长", atPause, session.activeDurationMs(200_000L))
        // elapsedSeconds 同样不增长
        assertEquals(20, session.elapsedSeconds())
    }

    @Test
    fun completeWhileRunningIncludesLastSegment() {
        val clock = FakeClock(0L)
        val session = SkillRunSession("sk_1", nowProvider = clock.provider())
        session.start() // t=0
        clock.now = 180_000L
        val status = session.complete()
        assertEquals("completed", status)
        assertEquals("直接完成应包含最后一段 180s", 180, session.durationSeconds)
    }

    @Test
    fun stopWhileRunningIncludesLastSegment() {
        val clock = FakeClock(0L)
        val session = SkillRunSession("sk_1", nowProvider = clock.provider())
        session.start()
        clock.now = 45_000L
        val status = session.stop()
        assertEquals("stopped", status)
        assertEquals(45, session.durationSeconds)
    }

    @Test
    fun toEntityRoundTripsThroughRestore() {
        val clock = FakeClock(0L)
        val session = SkillRunSession(
            skillId = "sk_1",
            skillVersion = 2,
            skillRevision = 3,
            actionType = "guided_steps",
            nowProvider = clock.provider()
        )
        session.start()
        session.nextStep(3) // RUNNING 中推进到第 1 步（currentStep=1）
        clock.now = 30_000L
        session.pause()

        val entity = session.toEntity(sessionId = "skse_123", now = 30_000L)
        assertEquals("skse_123", entity.sessionId)
        assertEquals("sk_1", entity.skillId)
        assertEquals(2, entity.skillVersion)
        assertEquals(3, entity.skillRevision)
        assertEquals("guided_steps", entity.actionType)
        assertEquals("paused", entity.status)
        assertEquals(1, entity.currentStep)
        assertEquals(30_000L, entity.accumulatedActiveMs)
        assertNull(entity.segmentStartedAtMs)

        // 恢复：paused 原样恢复为 PAUSED
        val restored = SkillRunSession.restoreFromEntity(entity)
        assertEquals(SkillRunStatus.PAUSED, restored.status)
        assertEquals(1, restored.currentStep)
        assertEquals("paused 恢复后 segment 应为 null", null, restored.segmentStartedAtMs)
        assertEquals("恢复后暂停时长不虚增", 30, restored.elapsedSeconds())
    }

    @Test
    fun runningEntityRestoresAsPausedToAvoidInflatedDuration() {
        // 模拟进程死亡前最后一次持久化（status=running, segmentStartedAt=10s）
        val entity = ActiveSkillSessionEntity(
            sessionId = "skse_proc",
            skillId = "sk_1",
            skillVersion = 1,
            skillRevision = 1,
            actionType = "guided_steps",
            status = "running",
            currentStep = 0,
            startedAt = 0L,
            accumulatedActiveMs = 0L,
            segmentStartedAtMs = 10_000L,
            pausedAt = null,
            updatedAt = 10_000L
        )
        // 进程重启后很久才恢复（now=600s）
        val restored = SkillRunSession.restoreFromEntity(entity)
        // 恢复语义：统一 PAUSED、不自动计时（segment=null），避免进程死亡期间虚增时长
        assertEquals("running 实体恢复后应为 PAUSED", SkillRunStatus.PAUSED, restored.status)
        assertNull("恢复后不自动计时（segment 置空）", restored.segmentStartedAtMs)
        assertEquals("恢复后已结算活动时长仅为 persisted accumulated", 0L, restored.accumulatedActiveMs)
        assertEquals("进程死亡期间 elapsed 不虚增", 0, restored.elapsedSeconds())
    }

    @Test
    fun actionTypeIsCarriedThroughEntity() {
        val session = SkillRunSession("sk_breath", actionType = "breathing", nowProvider = { 0L })
        session.start()
        val entity = session.toEntity("skse_breath", now = 0L)
        assertEquals("breathing", entity.actionType)
    }

    @Test
    fun pausedEntityKeepsSegmentNull() {
        val clock = FakeClock(0L)
        val session = SkillRunSession("sk_1", nowProvider = clock.provider())
        session.start()
        clock.now = 5_000L
        session.pause()
        val entity = session.toEntity("skse_p", now = 5_000L)
        assertEquals("paused", entity.status)
        assertNull("paused 时 segment 应为 null", entity.segmentStartedAtMs)
        assertFalse("paused 时 isRunning 应为 false", session.isRunning)
    }

    @Test
    fun settleTerminalFreezesDurationOnRunInstance() {
        // 回归 T02/缺陷：finish 必须在协调器 run 实例上结算，duration 不能恒为 0。
        val clock = FakeClock(0L)
        val run = SkillRunSession("sk_1", nowProvider = clock.provider())
        run.start() // t=0
        clock.now = 90_000L

        val status = settleTerminal(run, SkillTerminal.COMPLETE)

        assertEquals("completed", status)
        assertEquals("finish 结算的 duration 应包含最后一段（90s）", 90, run.durationSeconds)
    }

    @Test
    fun settleTerminalStopReturnsStoppedAndFreezesDuration() {
        val clock = FakeClock(0L)
        val run = SkillRunSession("sk_1", nowProvider = clock.provider())
        run.start()
        clock.now = 30_000L
        run.pause() // 结算 30s
        clock.now = 60_000L // 暂停 30s 不计入
        val status = settleTerminal(run, SkillTerminal.STOP)

        assertEquals("stopped", status)
        assertEquals("暂停段不应计入 duration（30s）", 30, run.durationSeconds)
    }

    @Test
    fun syncFromViewMirrorsCoordinatorState() {
        val session = SkillRunSession("sk_1", nowProvider = { 0L })
        session.syncFromView(SkillRunStatus.RUNNING, currentStep = 2, durationSeconds = 15)

        assertEquals(SkillRunStatus.RUNNING, session.status)
        assertEquals(2, session.currentStep)
        assertEquals(15, session.durationSeconds)
        assertTrue("同步 RUNNING 后 isRunning 应为 true", session.isRunning)
    }
}
