package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.model.SkillDisplay

/**
 * action_type 白名单分发（PRD 契约点 4 / T02 缺陷 7 修复）：
 *
 * - 5 个原生 Compose Renderer：guided_steps / breathing / checklist / journaling / reflection_prompt；
 * - 未知 actionType → [BlockedContent]（fail closed：不渲染开始、不产生 completion）。
 *
 * 无任何情绪评分 / 诊断输出（被动特征不做情绪推断）。
 */
@Composable
internal fun ActionRenderer(
    skill: SkillDisplay,
    session: SkillRunSession,
    uiStatus: SkillRunStatus,
    uiStep: Int,
    uiDuration: Int,
    onStatusChanged: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: (SkillTerminal) -> Unit
) {
    when (skill.actionType) {
        "guided_steps" -> GuidedStepsContent(skill, session, uiStatus, uiStep, uiDuration, onStatusChanged, onStart, onNext, onPause, onResume, onFinish)
        "breathing" -> BreathingContent(skill, session, uiStatus, uiDuration, onStatusChanged, onStart, onNext, onPause, onResume, onFinish)
        "checklist" -> ChecklistContent(skill, session, uiStatus, uiStep, onStatusChanged, onStart, onNext, onPause, onResume, onFinish)
        "journaling" -> JournalingContent(skill, session, uiStatus, onStatusChanged, onStart, onNext, onPause, onResume, onFinish)
        "reflection_prompt" -> ReflectionContent(skill, session, uiStatus, uiDuration, onStatusChanged, onStart, onNext, onPause, onResume, onFinish)
        else -> BlockedContent(skill)
    }
}

/** 白名单内 action_type 的展示名（纯函数，便于单测）。 */
internal fun actionTypeLabel(actionType: String): String = when (actionType) {
    "guided_steps" -> "逐步引导"
    "breathing" -> "呼吸练习"
    "checklist" -> "清单练习"
    "journaling" -> "反思记录"
    "reflection_prompt" -> "反思引导"
    else -> "此能力暂不可用"
}

/**
 * guided_steps：保留既有 step-by-step（下一步/暂停/完成/停止）。
 */
@Composable
private fun GuidedStepsContent(
    skill: SkillDisplay,
    session: SkillRunSession,
    uiStatus: SkillRunStatus,
    uiStep: Int,
    uiDuration: Int,
    onStatusChanged: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: (SkillTerminal) -> Unit
) {
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
    ExecutionControls(session, skill.steps.size, uiStatus, uiDuration, onStatusChanged, onStart, onNext, onPause, onResume, onFinish)
}

/**
 * breathing：原生节奏引导（阶段/倒计时展示 + 暂停/停止；无医疗功效宣称、不用 WebView）。
 * 节奏引导文案为固定非诊断表达；倒计时为本地演示，不产生情绪评分。
 */
@Composable
private fun BreathingContent(
    skill: SkillDisplay,
    session: SkillRunSession,
    uiStatus: SkillRunStatus,
    uiDuration: Int,
    onStatusChanged: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: (SkillTerminal) -> Unit
) {
    HorizontalDivider()
    Text("呼吸练习", style = MaterialTheme.typography.titleSmall)
    Text("跟随节奏：吸气 4 秒 → 屏息 4 秒 → 呼气 6 秒。可按自己的节奏暂停或停止。")
    if (session.isRunning) {
        val phase = uiDuration / 4 % 3
        val phaseText = when (phase) {
            0 -> "吸气"
            1 -> "屏息"
            else -> "呼气"
        }
        Text("当前阶段：$phaseText（本地节奏引导，非诊断）", style = MaterialTheme.typography.bodyMedium)
    }
    ExecutionControls(session, 1, uiStatus, uiDuration, onStatusChanged, onStart, onNext, onPause, onResume, onFinish)
}

/**
 * checklist：每 step 可完成、进度可见。
 */
@Composable
private fun ChecklistContent(
    skill: SkillDisplay,
    session: SkillRunSession,
    uiStatus: SkillRunStatus,
    uiStep: Int,
    onStatusChanged: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: (SkillTerminal) -> Unit
) {
    HorizontalDivider()
    Text("清单", style = MaterialTheme.typography.titleSmall)
    if (skill.steps.isEmpty()) {
        Text("本能力没有清单步骤。")
    } else {
        skill.steps.forEachIndexed { index, step ->
            val done = index < session.currentStep || session.status == SkillRunStatus.COMPLETED
            Text("${if (done) "☑" else "☐"} ${index + 1}. $step")
        }
        Text("进度：${session.currentStep.coerceAtMost(skill.steps.size)}/${skill.steps.size}", style = MaterialTheme.typography.labelSmall)
    }
    ExecutionControls(session, skill.steps.size, uiStatus, uiDuration = 0, onStatusChanged = onStatusChanged, onStart = onStart, onNext = onNext, onPause = onPause, onResume = onResume, onFinish = onFinish)
}

/**
 * journaling：仅执行 completion_schema 明确要求的结构化输入；不要求文本上报则不上报。
 * 本地草稿仅内存（不落盘、不触发旧日记系统上传）。
 */
@Composable
private fun JournalingContent(
    skill: SkillDisplay,
    session: SkillRunSession,
    uiStatus: SkillRunStatus,
    onStatusChanged: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: (SkillTerminal) -> Unit
) {
    HorizontalDivider()
    Text("反思记录", style = MaterialTheme.typography.titleSmall)
    // 仅当 completion_schema 明确要求文本时才展示结构化输入提示；端侧不自行上传任何日记正文
    val requiresText = skill.completionSchema?.contains("text") == true
    if (requiresText) {
        Text("按提示记录要点（仅保存在本机本次会话中）。", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("本能力不要求文字上报。", style = MaterialTheme.typography.bodyMedium)
    }
    ExecutionControls(session, 1, uiStatus, uiDuration = 0, onStatusChanged = onStatusChanged, onStart = onStart, onNext = onNext, onPause = onPause, onResume = onResume, onFinish = onFinish)
}

/**
 * reflection_prompt：原生逐步反思；无 mood_score / trend 输出（对应移除的情绪检查模板）。
 */
@Composable
private fun ReflectionContent(
    skill: SkillDisplay,
    session: SkillRunSession,
    uiStatus: SkillRunStatus,
    uiDuration: Int,
    onStatusChanged: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: (SkillTerminal) -> Unit
) {
    HorizontalDivider()
    Text("反思引导", style = MaterialTheme.typography.titleSmall)
    if (skill.steps.isNotEmpty()) {
        skill.steps.forEachIndexed { index, step ->
            val marker = if (index < session.currentStep) "✓" else "•"
            Text("$marker ${index + 1}. $step")
        }
    } else {
        Text("按节奏回顾今天的活动与感受（非诊断、非情绪评分）。")
    }
    ExecutionControls(session, skill.steps.size, uiStatus, uiDuration, onStatusChanged, onStart, onNext, onPause, onResume, onFinish)
}

/**
 * fail closed：未知 action_type 的不可用 UI——不渲染「开始」、不产生 completion。
 */
@Composable
internal fun UnsupportedSkillContent(skill: SkillDisplay) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(skill.name, style = MaterialTheme.typography.titleMedium)
            Text("此能力暂不可用", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BlockedContent(skill: SkillDisplay) {
    UnsupportedSkillContent(skill)
}

/**
 * 通用执行控制（开始/下一步/暂停/继续/完成/停止）。
 *
 * v0.6.1（P0-4）：所有动作经回调上抛给 SkillCardHost → SkillSessionCoordinator，
 * 渲染器不再直接操作 session（single-active-session / 进程死亡恢复 / completion
 * 删除真实 sessionId 的统一语义由协调器承担）。
 */
@Composable
private fun ExecutionControls(
    session: SkillRunSession,
    totalSteps: Int,
    uiStatus: SkillRunStatus,
    uiDuration: Int,
    onStatusChanged: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: (SkillTerminal) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (uiStatus) {
            SkillRunStatus.IDLE -> Button(
                onClick = { onStart(); onStatusChanged() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始") }

            SkillRunStatus.RUNNING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onNext(); onStatusChanged() },
                        enabled = totalSteps > 1 && session.currentStep < totalSteps - 1,
                        modifier = Modifier.weight(1f)
                    ) { Text("下一步") }
                    OutlinedButton(onClick = { onPause(); onStatusChanged() }, modifier = Modifier.weight(1f)) {
                        Text("暂停")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onFinish(SkillTerminal.COMPLETE) }, modifier = Modifier.weight(1f)) {
                        Text("完成")
                    }
                    OutlinedButton(onClick = { onFinish(SkillTerminal.STOP) }, modifier = Modifier.weight(1f)) {
                        Text("停止")
                    }
                }
            }

            SkillRunStatus.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResume(); onStatusChanged() }, modifier = Modifier.weight(1f)) {
                    Text("继续")
                }
                Button(onClick = { onFinish(SkillTerminal.COMPLETE) }, modifier = Modifier.weight(1f)) {
                    Text("完成")
                }
                OutlinedButton(onClick = { onFinish(SkillTerminal.STOP) }, modifier = Modifier.weight(1f)) {
                    Text("停止")
                }
            }

            SkillRunStatus.COMPLETED -> Text("已完成 · 用时 $uiDuration 秒")
            SkillRunStatus.STOPPED -> Text("已停止 · 用时 $uiDuration 秒")
        }
    }
}
