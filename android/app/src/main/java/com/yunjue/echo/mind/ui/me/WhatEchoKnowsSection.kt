package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.me.MemoryManagementEvent
import com.yunjue.echo.mind.me.MemoryManagementUiState
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType

/**
 * ERA 15.5 §80 — Me → What ECHO Knows：七分类明确区分
 * （Observed / User-confirmed / Context / Correction / Preference /
 * Derived Pattern / Temporary Interpretation）+ §78/§79 特殊时期用户入口。
 *
 * 业务在 MemoryManagementViewModel；本节只渲染。
 *
 * ERA 33 状态提升：Section 只做 VM 收集；纯渲染在 WhatEchoKnowsContent。
 */
@Composable
fun WhatEchoKnowsSection(container: AppContainer) {
    val vm: MemoryManagementViewModel = viewModel(factory = MemoryManagementViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    WhatEchoKnowsContent(state = state, onEvent = vm::onEvent)
}

/** ERA 33 — What ECHO Knows 纯状态内容（state-in / event-out）。 */
@Composable
fun WhatEchoKnowsContent(
    state: MemoryManagementUiState,
    onEvent: (MemoryManagementEvent) -> Unit,
) {
    val memories = state.memories

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What ECHO Knows About Me", style = MaterialTheme.typography.titleMedium)
            Text(
                "ECHO 的记忆由你的数据与你告诉它的话形成。你可以确认、纠正或忘记任何一条。",
                style = MaterialTheme.typography.bodySmall
            )
            // ERA 60（ADR-060 第 1 轮）：§80 七层分层摘要（用户看到的永远是全貌计数，过滤不影响）
            val counts = state.layerCounts
            if (counts.total > 0) {
                Text(
                    "共 ${counts.total} 条：你确认过 ${counts.userConfirmed} · 你告诉我的 ${counts.context} · " +
                        "我观察到 ${counts.observation} · 你的偏好 ${counts.preference} · " +
                        "你纠正过 ${counts.correction} · 发现的模式 ${counts.derivedPattern} · " +
                        "还在推测 ${counts.temporaryInterpretation}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // ERA 61（ADR-060 第 2 轮）：「ECHO 还不知道什么」能力边界（诚实呈现，不编造）
            if (state.doesNotKnow.isNotEmpty()) {
                Text("ECHO 还不知道什么", style = MaterialTheme.typography.titleSmall)
                state.doesNotKnow.forEach { line ->
                    Text("· $line", style = MaterialTheme.typography.bodySmall)
                }
            }
            // §78/§79：用户解释优先（特殊时期入口）
            var showAddDialog by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { showAddDialog = true }) {
                Text("告诉 ECHO 一个特殊时期（如出差、考试周）")
            }
            if (showAddDialog) {
                ContextExceptionDialog(
                    onDismiss = { showAddDialog = false },
                    onConfirm = { kind, note ->
                        onEvent(MemoryManagementEvent.AddContextException(kind, note))
                        showAddDialog = false
                    },
                )
            }
            // 类别过滤（§36 filter）
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf<Pair<MemoryType?, String>>(
                    null to "全部",
                    MemoryType.CORRECTION to "你纠正过我的",
                    MemoryType.CONTEXT to "我告诉你的",
                    MemoryType.USER_CONFIRMED to "我已确认的",
                    MemoryType.PREFERENCE to "我的偏好",
                    MemoryType.DERIVED_PATTERN to "发现的模式",
                    MemoryType.OBSERVATION to "观察到的事实",
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = state.filter == type,
                        onClick = { onEvent(MemoryManagementEvent.SetFilter(type)) },
                        label = { Text(label) }
                    )
                }
            }
            if (memories.isEmpty()) {
                Text("还没有长期记忆。ECHO 正在慢慢认识你。", style = MaterialTheme.typography.bodySmall)
            }
            // §80 七分类分组展示（低置信的临时解释单独标注「我还不确定的」）
            val visible = if (state.filter == null) memories else memories.filter { it.type == state.filter }
            typeGroups().forEach { (type, label) ->
                val group = visible.filter { it.type == type }
                if (group.isNotEmpty()) {
                    group.take(4).forEach { m ->
                        MemoryRow(
                            label = label + if (m.type == MemoryType.TEMPORARY_INTERPRETATION && m.confidence < 0.5f) "（还不确定）" else "",
                            memory = m,
                            onEvent = onEvent,
                        )
                    }
                }
            }
        }
    }
}

/** §80 七分类展示顺序（Observed → User-confirmed → Context → Correction → Preference → Pattern → Temporary）。 */
private fun typeGroups(): List<Pair<MemoryType, String>> = listOf(
    MemoryType.OBSERVATION to "观察到的事实",
    MemoryType.USER_CONFIRMED to "我已确认的",
    MemoryType.CONTEXT to "我告诉你的",
    MemoryType.CORRECTION to "你纠正过我的",
    MemoryType.PREFERENCE to "我的偏好",
    MemoryType.DERIVED_PATTERN to "发现的模式",
    MemoryType.TEMPORARY_INTERPRETATION to "临时解释",
)

/** §78 特殊时期类型（travel/holiday/work crunch/exam/illness/event/user-defined）。 */
private val CONTEXT_KINDS = listOf(
    "出差/旅行", "假期", "工作特别忙", "考试周", "生病/恢复期", "重要事件", "其他",
)

@Composable
private fun ContextExceptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (kind: String, note: String) -> Unit,
) {
    var kind by remember { mutableStateOf(CONTEXT_KINDS[0]) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("告诉 ECHO 一个特殊时期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这段时间的节律可能和平时不一样；ECHO 之后判断变化时会优先考虑你的解释。", style = MaterialTheme.typography.bodySmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    CONTEXT_KINDS.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k) })
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("补充说明（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(kind, note.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 单条记忆行（四权：编辑/确认/忘记；编辑为内联文本框）。 */
@Composable
private fun MemoryRow(
    label: String,
    memory: EchoMemory,
    onEvent: (MemoryManagementEvent) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(memory.content) }
    Column(Modifier.fillMaxWidth()) {
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row {
                TextButton(onClick = {
                    editing = false
                    onEvent(MemoryManagementEvent.Edit(memory.id, draft))
                }, enabled = draft.isNotBlank()) { Text("保存") }
                TextButton(onClick = { editing = false; draft = memory.content }) { Text("取消") }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Text(memory.content, style = MaterialTheme.typography.bodySmall)
                    // ERA 62（ADR-060 第 3 轮）：来源=层标签（上行）；保留策略=确定性映射（下行）
                    Text("保留 ${com.yunjue.echo.mind.me.retentionLabelText(memory.retentionClass)}", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { editing = true; draft = memory.content }) { Text("编辑") }
                TextButton(onClick = { onEvent(MemoryManagementEvent.Confirm(memory.id)) }) { Text("确认") }
                TextButton(onClick = { onEvent(MemoryManagementEvent.Forget(memory.id)) }) { Text("忘记") }
            }
        }
    }
}
