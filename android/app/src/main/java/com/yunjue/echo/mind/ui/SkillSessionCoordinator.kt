package com.yunjue.echo.mind.ui

import com.yunjue.echo.mind.data.ActiveSkillSessionEntity
import com.yunjue.echo.mind.data.SkillRepository
import com.yunjue.echo.mind.model.SkillCompletionInput
import com.yunjue.echo.mind.model.SkillDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Skill Active Session 统一协调器（v0.6.1，P0-4）。
 *
 * 把 start/pause/resume/stop/finish/abort/process-death 收拢为共享状态机，
 * 替代「每个 SkillCardHost 独立 remember + 独立读取/决定全局会话」的旧模式。
 *
 * 领域规则（数据库与代码同时强制 single-active-session）：
 * 1. 同时只允许一个 Skill 执行；start 前清除旧会话（协调器唯一执行点）；
 * 2. 进程死亡恢复必须继续使用**原 sessionId**（restore 时取 entity.sessionId，
 *    绝不重新生成 UUID，保证 completion 删除的是真实恢复出来的会话）；
 * 3. 一个不匹配的 Skill Card 不得删除属于另一张 Skill 的有效会话
 *    （恢复按 skillId 精确查询，删除按 sessionId 精确删除）；
 * 4. 所有状态变更经 Mutex 串行化（多卡同时点击安全）。
 */
class SkillSessionCoordinator(private val repository: SkillRepository) {

    /** UI 可见的会话视图。 */
    data class SessionView(
        val skillId: String,
        val sessionId: String,
        val status: SkillRunStatus,
        val currentStep: Int,
        val durationSeconds: Int,
        val restored: Boolean
    )

    private data class RuntimeSession(
        val skill: SkillDisplay,
        val sessionId: String,
        val run: SkillRunSession,
        val restored: Boolean
    ) {
        fun view(): SessionView = SessionView(
            skillId = skill.id,
            sessionId = sessionId,
            status = run.status,
            currentStep = run.currentStep,
            durationSeconds = run.durationSeconds,
            restored = restored
        )
    }

    private val mutex = Mutex()
    private val runtime = mutableMapOf<String, RuntimeSession>()
    private val _sessions = MutableStateFlow<Map<String, SessionView>>(emptyMap())

    /** skillId -> SessionView（进程内单例流）。 */
    val sessions: StateFlow<Map<String, SessionView>> = _sessions.asStateFlow()

    /** 是否有任何活动会话（single-active 全局状态）。RUNNING/PAUSED 视为活动。 */
    val anyActive: Boolean
        get() = _sessions.value.values.any { it.status == SkillRunStatus.RUNNING || it.status == SkillRunStatus.PAUSED }

    private fun publish() {
        _sessions.value = runtime.mapValues { it.value.view() }
    }

    /**
     * 获取（或恢复）会话。进程重建后：
     * - 按 skillId 精确恢复（不匹配的卡片不会拿到/删除别的 Skill 的会话）；
     * - 继续使用**原 sessionId**（恢复为 PAUSED，不虚增时长）。
     */
    suspend fun getOrRestore(skill: SkillDisplay): SessionView = mutex.withLock {
        runtime[skill.id]?.let { return it.view() }
        val restored = repository.loadActiveSession(skill.id)
        if (restored != null) {
            val run = SkillRunSession.restoreFromEntity(restored)
            runtime[skill.id] = RuntimeSession(skill, restored.sessionId, run, restored = true)
        } else {
            val run = SkillRunSession(skill.id, skill.version, skill.revision, skill.actionType)
            runtime[skill.id] = RuntimeSession(skill, "skse_${UUID.randomUUID()}", run, restored = false)
        }
        publish()
        runtime[skill.id]!!.view()
    }

    /** 开始执行：single-active-session 切换（旧会话收口）→ start → 持久化。 */
    suspend fun start(skill: SkillDisplay): SessionView = mutex.withLock {
        // single-active-session：其他 Skill 的活动会话先收口（不产生 completion）
        val others = runtime.values.filter { it.skill.id != skill.id && it.run.isActive }
        if (others.isNotEmpty()) {
            repository.clearActiveSessions()
            runtime.keys.retainAll { it == skill.id }
        }
        val existing = runtime[skill.id]
        val run = existing?.run ?: SkillRunSession(skill.id, skill.version, skill.revision, skill.actionType)
        run.start()
        runtime[skill.id] = RuntimeSession(skill, existing?.sessionId ?: "skse_${UUID.randomUUID()}", run, restored = false)
        persist(runtime[skill.id]!!)
        publish()
        runtime[skill.id]!!.view()
    }

    /** 推进到下一步（仅 RUNNING 生效）。 */
    suspend fun nextStep(skillId: String, totalSteps: Int) = mutex.withLock {
        runtime[skillId]?.let { it.run.nextStep(totalSteps) }
        publish()
    }

    /** 暂停（暂停段不计入 active duration）。 */
    suspend fun pause(skillId: String) = mutex.withLock {
        runtime[skillId]?.let { it.run.pause(); persist(it) }
        publish()
    }

    /** 恢复。 */
    suspend fun resume(skillId: String) = mutex.withLock {
        runtime[skillId]?.let { it.run.resume(); persist(it) }
        publish()
    }

    /** 更新展示时长（计时器驱动；不修改状态机）。 */
    fun touchDuration(skillId: String, durationSeconds: Int) {
        runtime[skillId]?.let {
            // SkillRunSession.durationSeconds 是 private set；通过 run 内部结算
            // UI 计时仅展示用途，持久化时长以 terminal 结算为准。
        }
        _sessions.value = _sessions.value + (skillId to (_sessions.value[skillId]?.copy(durationSeconds = durationSeconds) ?: return))
    }

    /** 实时活动时长（秒，仅展示用）；持久化时长以 terminal 结算为准。 */
    fun liveDurationSeconds(skillId: String): Int = runtime[skillId]?.run?.elapsedSeconds() ?: 0

    /**
     * 完成/停止/中止（terminal）：
     * - 在**协调器的 run 实例**上结算（freezeDuration 才会给 durationSeconds 赋值），
     *   而非依赖调用方传入 terminal lambda 去结算 SkillCardHost 的本地影子；
     * - 用**真实 sessionId** 删除会话行（不匹配的卡片不会误删别的会话）；
     * - completion 入 Outbox（幂等 event_id）；app restart 后仍可同步；
     * - 从协调器状态中移除。
     */
    suspend fun finish(skill: SkillDisplay, terminal: SkillTerminal): Unit = mutex.withLock {
        val rt = runtime[skill.id] ?: return@withLock
        val status = settleTerminal(rt.run, terminal)
        val input = SkillCompletionInput(
            skillId = skill.id,
            status = status,
            durationSeconds = rt.run.durationSeconds
        )
        // 事务内：按真实 sessionId 删除 + 入 outbox（completion 幂等）
        repository.recordSkillCompletion(input, sessionId = rt.sessionId)
        runtime.remove(skill.id)
        publish()
    }

    /** 持久化当前会话（RUNNING/PAUSED；进程死亡后可恢复）。 */
    suspend fun persistCurrent(skillId: String) = mutex.withLock {
        runtime[skillId]?.let { if (it.run.isActive) persist(it) }
    }

    private suspend fun persist(rt: RuntimeSession) {
        runCatching {
            repository.saveActiveSession(rt.run.toEntity(rt.sessionId, System.currentTimeMillis()))
        }
    }
}
