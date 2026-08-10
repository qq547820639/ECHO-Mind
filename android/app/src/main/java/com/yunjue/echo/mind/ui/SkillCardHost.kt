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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SkillFetchResult
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.model.SkillDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Skill 执行状态（PRD v0.6 契约点 4：至少三态状态机）。 */
enum class SkillRunStatus { IDLE, RUNNING, PAUSED, COMPLETED, STOPPED }

/**
 * 单次 Skill 执行会话状态机（纯 Kotlin，便于单测）。
 *
 * - start() → RUNNING；nextStep() 逐步推进；pause()/resume()；complete()/stop() 收口；
 * - 完成/停止返回上报 status（"completed"/"stopped"），并计算 durationSeconds；
 * - 每个可见「开始」按钮经 [SkillCardHost] 绑定本会话，确保点击有真实行为。
 */
internal class SkillRunSession(
    val skillId: String,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    var status: SkillRunStatus = SkillRunStatus.IDLE
        private set

    var currentStep: Int = 0
        private set

    var durationSeconds: Int = 0
        private set

    private var startedAtMs: Long? = null

    val isRunning: Boolean get() = status == SkillRunStatus.RUNNING

    /** 开始执行（IDLE/已完成/已停止 均可重新开始）。 */
    fun start() {
        status = SkillRunStatus.RUNNING
        currentStep = 0
        startedAtMs = nowProvider()
        durationSeconds = 0
    }

    /** 推进到下一步（不超过总步骤数-1；仅 RUNNING 状态生效）。 */
    fun nextStep(totalSteps: Int) {
        if (status != SkillRunStatus.RUNNING) return
        if (totalSteps <= 0) return
        currentStep = (currentStep + 1).coerceAtMost(totalSteps - 1)
    }

    fun pause() {
        if (status == SkillRunStatus.RUNNING) status = SkillRunStatus.PAUSED
    }

    fun resume() {
        if (status == SkillRunStatus.PAUSED) status = SkillRunStatus.RUNNING
    }

    /** 完成：返回上报 status "completed"（已停止后调用返回 "stopped"）。 */
    fun complete(): String {
        if (status == SkillRunStatus.STOPPED) return "stopped"
        status = SkillRunStatus.COMPLETED
        durationSeconds = computeDurationSeconds()
        return "completed"
    }

    /** 停止：返回上报 status "stopped"（已完成后调用返回 "completed"）。 */
    fun stop(): String {
        if (status == SkillRunStatus.COMPLETED) return "completed"
        status = SkillRunStatus.STOPPED
        durationSeconds = computeDurationSeconds()
        return "stopped"
    }

    /** 当前已耗时（秒），执行中实时展示用。 */
    fun elapsedSeconds(): Int = startedAtMs?.let { ((nowProvider() - it).coerceAtLeast(0L) / 1000L).toInt() } ?: 0

    private fun computeDurationSeconds(): Int = elapsedSeconds()
}

/**
 * Skill 卡片宿主（原生 Compose，替代 WebView 静态展示）：
 *
 * - 移除 WebView：提升可访问性 / TalkBack / 字体缩放 / 暗色模式 / UI 一致性；
 * - 步骤 step-by-step 展示 + 执行控制（开始 → 下一步/暂停 → 继续 → 完成/停止）；
 * - 每个「开始」按钮都有真实行为（进入执行状态机）；
 * - 完成/停止后调用 [LocalRepository.recordSkillCompletion] 本地记录 + 触发 SyncWorker 上传。
 */
@Composable
fun SkillCardHost(skill: SkillDisplay, repository: LocalRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(skill.id) { SkillRunSession(skill.id) }

    var uiStatus by remember(skill.id) { mutableStateOf(session.status) }
    var uiStep by remember(skill.id) { mutableStateOf(session.currentStep) }
    var uiDuration by remember(skill.id) { mutableStateOf(session.durationSeconds) }

    fun syncUi() {
        uiStatus = session.status
        uiStep = session.currentStep
        uiDuration = session.durationSeconds
    }

    // 执行中实时计时
    LaunchedEffect(uiStatus) {
        while (session.isRunning) {
            delay(1000)
            uiDuration = session.elapsedSeconds()
        }
    }

    // 完成/停止：本地记录 + 可选 server sync
    fun finish(terminal: () -> String) {
        scope.launch {
            val status = terminal()
            syncUi()
            runCatching { repository.recordSkillCompletion(skill.id, status, session.durationSeconds) }
            runCatching { SyncWorker.enqueue(context) }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(skill.name, style = MaterialTheme.typography.titleMedium)
            Text("v${skill.version} · ${skill.status}", style = MaterialTheme.typography.labelSmall)

            if (skill.steps.isNotEmpty()) {
                HorizontalDivider()
                Text("步骤", style = MaterialTheme.typography.titleSmall)
                skill.steps.forEachIndexed { index, step ->
                    val marker = when {
                        index < session.currentStep -> "✓"
                        index == session.currentStep && session.isRunning -> "▶"
                        else -> "•"
                    }
                    Text("$marker ${index + 1}. $step")
                }
            }

            if (skill.guardrails.isNotEmpty()) {
                HorizontalDivider()
                Text("边界", style = MaterialTheme.typography.titleSmall)
                skill.guardrails.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }

            HorizontalDivider()

            when (uiStatus) {
                SkillRunStatus.IDLE -> Button(
                    onClick = { session.start(); syncUi() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("开始") }

                SkillRunStatus.RUNNING -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { session.nextStep(skill.steps.size); syncUi() },
                            enabled = skill.steps.size > 1 && session.currentStep < skill.steps.size - 1,
                            modifier = Modifier.weight(1f)
                        ) { Text("下一步") }
                        OutlinedButton(onClick = { session.pause(); syncUi() }, modifier = Modifier.weight(1f)) {
                            Text("暂停")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { finish { session.complete() } }, modifier = Modifier.weight(1f)) {
                            Text("完成")
                        }
                        OutlinedButton(onClick = { finish { session.stop() } }, modifier = Modifier.weight(1f)) {
                            Text("停止")
                        }
                    }
                }

                SkillRunStatus.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { session.resume(); syncUi() }, modifier = Modifier.weight(1f)) {
                        Text("继续")
                    }
                    Button(onClick = { finish { session.complete() } }, modifier = Modifier.weight(1f)) {
                        Text("完成")
                    }
                    OutlinedButton(onClick = { finish { session.stop() } }, modifier = Modifier.weight(1f)) {
                        Text("停止")
                    }
                }

                SkillRunStatus.COMPLETED -> Text("已完成 · 用时 ${uiDuration} 秒")
                SkillRunStatus.STOPPED -> Text("已停止 · 用时 ${uiDuration} 秒")
            }
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
 * - 非空 → Skill 卡片列表（每个「开始」按钮绑定真实执行行为）
 *
 * 危机入口由全局紧急 FAB 常驻，此页不重复放置。
 */
@Composable
fun SkillListScreen(repository: LocalRepository) {
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
            else -> items(skillState.skills) { skill -> SkillCardHost(skill, repository) }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}
