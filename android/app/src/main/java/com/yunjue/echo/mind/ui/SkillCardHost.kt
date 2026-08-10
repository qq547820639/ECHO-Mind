package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.ActiveSkillSessionEntity
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SkillFetchResult
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.model.SkillCompletionInput
import com.yunjue.echo.mind.model.SkillDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Skill 执行状态（PRD v0.6 契约点 4：至少三态状态机）。 */
enum class SkillRunStatus { IDLE, RUNNING, PAUSED, COMPLETED, STOPPED }

/**
 * 单次 Skill 执行会话状态机（纯 Kotlin，便于单测）。
 *
 * v0.6 final 语义（T02）：
 * - start() → RUNNING；nextStep() 逐步推进；pause()/resume()；complete()/stop() 收口；
 * - **暂停不计时**：activeDurationMs = accumulatedActiveMs + (now - segmentStartedAtMs)
 *   （running 时）；paused/terminal 时 = accumulatedActiveMs；
 * - 支持持久化：toEntity() 导出 Room 行；restoreFrom(entity) 从进程死亡恢复为 PAUSED；
 * - actionType 白名单校验由 [SkillDisplay.ACTION_TYPE_WHITELIST] 承担（fail closed）。
 */
internal class SkillRunSession(
    val skillId: String,
    val skillVersion: Int = 1,
    val skillRevision: Int = 1,
    val actionType: String = "guided_steps",
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    var status: SkillRunStatus = SkillRunStatus.IDLE
        private set

    var currentStep: Int = 0
        private set

    var durationSeconds: Int = 0
        private set

    var startedAtMs: Long? = null
        private set

    var accumulatedActiveMs: Long = 0L
        private set

    var segmentStartedAtMs: Long? = null
        private set

    var pausedAtMs: Long? = null
        private set

    val isRunning: Boolean get() = status == SkillRunStatus.RUNNING
    val isActive: Boolean get() = status == SkillRunStatus.RUNNING || status == SkillRunStatus.PAUSED

    /** 开始执行（IDLE/已完成/已停止 均可重新开始）。 */
    fun start() {
        val now = nowProvider()
        status = SkillRunStatus.RUNNING
        currentStep = 0
        startedAtMs = now
        accumulatedActiveMs = 0L
        segmentStartedAtMs = now
        pausedAtMs = null
        durationSeconds = 0
    }

    /** 推进到下一步（不超过总步骤数-1；仅 RUNNING 状态生效）。 */
    fun nextStep(totalSteps: Int) {
        if (status != SkillRunStatus.RUNNING) return
        if (totalSteps <= 0) return
        currentStep = (currentStep + 1).coerceAtMost(totalSteps - 1)
    }

    /** 暂停：结算当前活动段到 accumulatedActiveMs（暂停段不计入 active duration）。 */
    fun pause() {
        if (status != SkillRunStatus.RUNNING) return
        val now = nowProvider()
        accumulatedActiveMs += (now - (segmentStartedAtMs ?: now)).coerceAtLeast(0L)
        segmentStartedAtMs = null
        pausedAtMs = now
        status = SkillRunStatus.PAUSED
    }

    /** 恢复：重新开启当前活动段。 */
    fun resume() {
        if (status != SkillRunStatus.PAUSED) return
        segmentStartedAtMs = nowProvider()
        pausedAtMs = null
        status = SkillRunStatus.RUNNING
    }

    /** 完成：返回上报 status "completed"（已停止后调用返回 "stopped"）。 */
    fun complete(): String {
        if (status == SkillRunStatus.STOPPED) return "stopped"
        status = SkillRunStatus.COMPLETED
        freezeDuration()
        return "completed"
    }

    /** 停止：返回上报 status "stopped"（已完成后调用返回 "completed"）。 */
    fun stop(): String {
        if (status == SkillRunStatus.COMPLETED) return "completed"
        status = SkillRunStatus.STOPPED
        freezeDuration()
        return "stopped"
    }

    private fun freezeDuration() {
        // 终态前先结算当前活动段（若正在运行），保证 completed/stopped 时长包含最后一段
        if (segmentStartedAtMs != null) {
            val now = nowProvider()
            accumulatedActiveMs += (now - (segmentStartedAtMs ?: now)).coerceAtLeast(0L)
            segmentStartedAtMs = null
        }
        durationSeconds = accumulatedActiveMs.toInt() / 1000
    }

    /** 当前活动时长（ms）：running = accumulated + 当前段；暂停/终态 = accumulated（暂停不计时）。 */
    fun activeDurationMs(now: Long): Long = when (status) {
        SkillRunStatus.RUNNING ->
            accumulatedActiveMs + (now - (segmentStartedAtMs ?: (startedAtMs ?: now))).coerceAtLeast(0L)
        SkillRunStatus.PAUSED, SkillRunStatus.COMPLETED, SkillRunStatus.STOPPED -> accumulatedActiveMs
        SkillRunStatus.IDLE -> 0L
    }

    /** 当前已耗时（秒），执行中实时展示用。 */
    fun elapsedSeconds(): Int = activeDurationMs(nowProvider()).toInt() / 1000

    /** 导出 Room 持久化实体（running/paused 均可保存）。 */
    fun toEntity(sessionId: String, now: Long): ActiveSkillSessionEntity = ActiveSkillSessionEntity(
        sessionId = sessionId,
        skillId = skillId,
        skillVersion = skillVersion,
        skillRevision = skillRevision,
        actionType = actionType,
        status = if (status == SkillRunStatus.RUNNING) "running" else "paused",
        currentStep = currentStep,
        startedAt = startedAtMs ?: now,
        accumulatedActiveMs = accumulatedActiveMs,
        segmentStartedAtMs = segmentStartedAtMs,
        pausedAt = pausedAtMs,
        updatedAt = now
    )

    /** 从持久化实体恢复：**统一恢复为 PAUSED**（进程死亡期间不自动计时，避免虚增时长）。 */
    fun restoreFrom(entity: ActiveSkillSessionEntity) {
        status = SkillRunStatus.PAUSED
        currentStep = entity.currentStep
        startedAtMs = entity.startedAt
        accumulatedActiveMs = entity.accumulatedActiveMs
        segmentStartedAtMs = null
        pausedAtMs = entity.pausedAt
        durationSeconds = 0
    }

    companion object {
        /** 从持久化实体构造新会话并恢复为 PAUSED（进程重建恢复入口）。 */
        fun restoreFromEntity(
            entity: ActiveSkillSessionEntity,
            nowProvider: () -> Long = System::currentTimeMillis
        ): SkillRunSession = SkillRunSession(
            skillId = entity.skillId,
            skillVersion = entity.skillVersion,
            skillRevision = entity.skillRevision,
            actionType = entity.actionType,
            nowProvider = nowProvider
        ).also { it.restoreFrom(entity) }
    }
}

/**
 * Skill 卡片宿主（原生 Compose，替代 WebView 静态展示）：
 *
 * - action_type 白名单分发：guided_steps / breathing / checklist / journaling / reflection_prompt
 *   分别渲染原生 Renderer；白名单外 fail closed（不渲染「开始」、不产生 completion）；
 * - v0.6.1（P0-4）：会话生命周期全部交由 [SkillSessionCoordinator]：
 *   - 进程死亡恢复继续使用原 sessionId（恢复为 PAUSED，不虚增时长）；
 *   - single-active-session 由协调器强制（多卡同时点击安全）；
 *   - 完成/停止：按真实 sessionId 删除会话 + completion 入 outbox（幂等）；
 *   - 本 Composable 不再自行 remember/读写全局会话（消除跨卡误删）。
 */
@Composable
fun SkillCardHost(skill: SkillDisplay, repository: LocalRepository, coordinator: SkillSessionCoordinator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 白名单 gate（fail closed）：不在白名单 → 不可用 UI，不渲染开始，不产生 completion
    if (skill.actionType !in SkillDisplay.ACTION_TYPE_WHITELIST) {
        UnsupportedSkillContent(skill)
        return
    }

    // 本地影子会话：仅用于渲染（currentStep/isRunning 读取）；状态所有权在协调器
    val session = remember(skill.id) {
        SkillRunSession(
            skillId = skill.id,
            skillVersion = skill.version,
            skillRevision = skill.revision,
            actionType = skill.actionType
        )
    }

    var uiStatus by remember(skill.id) { mutableStateOf(session.status) }
    var uiStep by remember(skill.id) { mutableStateOf(session.currentStep) }
    var uiDuration by remember(skill.id) { mutableStateOf(session.durationSeconds) }

    fun syncUi() {
        uiStatus = session.status
        uiStep = session.currentStep
        uiDuration = session.durationSeconds
    }

    /** 协调器视图 → 本地影子（恢复后继续使用原 sessionId 语义由协调器承载）。 */
    fun applyView(view: SkillSessionCoordinator.SessionView?) {
        if (view == null) return
        val entity = com.yunjue.echo.mind.data.ActiveSkillSessionEntity(
            sessionId = view.sessionId,
            skillId = view.skillId,
            skillVersion = skill.version,
            skillRevision = skill.revision,
            actionType = skill.actionType,
            status = if (view.status == SkillRunStatus.RUNNING) "running" else "paused",
            currentStep = view.currentStep,
            startedAt = System.currentTimeMillis(),
            accumulatedActiveMs = 0L,
            segmentStartedAtMs = null,
            pausedAt = null,
            updatedAt = System.currentTimeMillis()
        )
        session.restoreFrom(entity)
        syncUi()
    }

    // 进程/组件重建恢复：协调器按 skillId 精确恢复（不匹配的卡片不会拿到/删除别的会话）
    LaunchedEffect(skill.id) {
        val view = try {
            coordinator.getOrRestore(skill)
        } catch (_: Exception) {
            null
        }
        applyView(view)
    }

    // 协调器状态流 → UI（跨卡片/恢复/完成统一同步）
    LaunchedEffect(skill.id) {
        coordinator.sessions.collect { map ->
            applyView(map[skill.id])
        }
    }

    // 执行中实时计时（仅展示；持久化时长以 terminal 结算为准）
    LaunchedEffect(uiStatus) {
        while (session.isRunning) {
            kotlinx.coroutines.delay(1000)
            uiDuration = session.elapsedSeconds()
            coordinator.touchDuration(skill.id, uiDuration)
        }
    }

    // 完成/停止：协调器按真实 sessionId 删会话 + completion 入 outbox（幂等）
    fun finish(terminal: () -> String) {
        scope.launch {
            try {
                coordinator.finish(skill, terminal)
            } catch (_: Exception) {
                // completion 落库失败不阻断 UI；会话行仍在 → 恢复流程兜底
            }
            runCatching { SyncWorker.enqueue(context) }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(skill.name, style = MaterialTheme.typography.titleMedium)
            Text("v${skill.version} · ${skill.status}" + if (skill.revision > 1) " · rev${skill.revision}" else "",
                style = MaterialTheme.typography.labelSmall)

            // 执行契约字段展示（PRD 契约点 4）
            skill.estimatedDuration?.let {
                Text("预计时长：${it / 60} 分钟", style = MaterialTheme.typography.labelSmall)
            }
            if (skill.safetyConstraints.isNotEmpty()) {
                HorizontalDivider()
                Text("安全边界", style = MaterialTheme.typography.titleSmall)
                skill.safetyConstraints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }

            HorizontalDivider()

            // action_type 白名单分发；动作回调统一转发协调器（single-active / 恢复 / 幂等）
            ActionRenderer(
                skill = skill,
                session = session,
                repository = repository,
                sessionId = "coordinator-owned",
                uiStatus = uiStatus,
                uiStep = uiStep,
                uiDuration = uiDuration,
                onStatusChanged = { syncUi() },
                onStart = {
                    scope.launch {
                        try {
                            applyView(coordinator.start(skill))
                        } catch (_: Exception) {
                            // 持久化失败不阻断执行
                        }
                        syncUi()
                    }
                },
                onNext = {
                    scope.launch { coordinator.nextStep(skill.id, skill.steps.size); applyView(coordinator.getOrRestore(skill)); syncUi() }
                },
                onPause = {
                    scope.launch { coordinator.pause(skill.id); applyView(coordinator.getOrRestore(skill)); syncUi() }
                },
                onResume = {
                    scope.launch { coordinator.resume(skill.id); applyView(coordinator.getOrRestore(skill)); syncUi() }
                },
                onFinish = ::finish
            )
        }
    }
}

/**
 * P3 冷启动文案资源映射：按后端返回的 stage key 返回对应 [R.string] 资源 ID。
 *
 * - stage_0 → 系统正在了解你
 * - stage_1_3 → 已采集 N 天数据（[days] 用于格式化 %1$d 占位）
 * - stage_4_7 → 画像成型中
 * - stage_7_plus → 暂无新能力
 * - 未知 stage 兜底 stage_0
 *
 * 抽成纯函数便于单测覆盖 4 档映射。返回值为资源 ID，调用方用 [stringResource] 取文本。
 */
internal fun coldStartHint(stage: String, days: Int): Int = when (stage) {
    "stage_0" -> R.string.cold_start_stage_0
    "stage_1_3" -> R.string.cold_start_stage_1_3
    "stage_4_7" -> R.string.cold_start_stage_4_7
    "stage_7_plus" -> R.string.cold_start_stage_7_plus
    else -> R.string.cold_start_stage_0
}

/**
 * 拉取已下发 Skill 列表（IO 线程）；返回三态 [SkillFetchResult] + 重试回调。
 *
 * - 首次组合：skills=null + loadFailed=false → 加载中
 * - 拉取完成：由 [LocalRepository.fetchSkills] 决定成功/失败/空态
 * - 重试：调用方调返回的 lambda 触发重新拉取（retryKey 自增驱动 [LaunchedEffect]）
 *
 * 复用于 [TodayScreen] 与 [SkillListScreen]，避免两处重复拉取逻辑。
 */
@Composable
internal fun rememberSkillList(repository: LocalRepository): Pair<SkillFetchResult, () -> Unit> {
    var result by remember {
        mutableStateOf(SkillFetchResult(skills = null, coldStartHint = null, loadFailed = false))
    }
    var retryKey by remember { mutableStateOf(0) }
    LaunchedEffect(retryKey) {
        result = withContext(Dispatchers.IO) { repository.fetchSkills() }
    }
    return result to { retryKey++ }
}

/**
 * 「能力」Tab 全页 Skill 列表：拉取已下发 Skill，区分三态。
 *
 * - 加载中（skills==null + 未失败）→ CircularProgressIndicator
 * - 加载失败（loadFailed）→「加载失败」+ 重试按钮
 * - 空列表（冷启动）→ 按 [SkillFetchResult.coldStartHint] 分阶段文案
 * - 非空 → Skill 卡片列表（每个「开始」按钮绑定真实执行行为；白名单外 fail closed）
 *
 * 危机入口由全局紧急 FAB 常驻，此页不重复放置。
 */
@Composable
fun SkillListScreen(repository: LocalRepository, coordinator: SkillSessionCoordinator) {
    val (skillState, retry) = rememberSkillList(repository)

    // P5 灰度回滚：拉取 feature flags 缓存 + 观察 skills_delivery_enabled。
    // flag 关闭时隐藏 Skill 卡片区，显示「能力下发已暂停」。
    LaunchedEffect(Unit) {
        runCatching { repository.fetchFeatureFlags() }
    }
    val featureFlags by repository.featureFlagsFlow.collectAsState(
        initial = mapOf("skills_delivery_enabled" to true)
    )
    val skillsDeliveryEnabled = featureFlags["skills_delivery_enabled"] ?: true

    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("能力", style = MaterialTheme.typography.headlineMedium) }
        when {
            !skillsDeliveryEnabled -> item {
                // P5 灰度回滚：skills_delivery_enabled=false 时隐藏 Skill 卡片区
                Text(
                    stringResource(R.string.skills_delivery_paused),
                    Modifier.padding(top = 40.dp)
                )
            }
            skillState.loadFailed -> item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.cold_start_load_failed))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = retry) { Text(stringResource(R.string.cold_start_retry)) }
                }
            }
            skillState.skills == null -> item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            }
            skillState.skills.isEmpty() -> item {
                val stage = skillState.coldStartHint ?: "stage_0"
                val resId = coldStartHint(stage, skillState.observationDays)
                Text(
                    if (stage == "stage_1_3") stringResource(resId, skillState.observationDays)
                    else stringResource(resId),
                    Modifier.padding(top = 40.dp)
                )
            }
            else -> items(skillState.skills) { skill -> SkillCardHost(skill, repository, coordinator) }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}
