package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.yunjue.echo.mind.me.groupMemories
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType

/**
 * ERA 13.1 §36 — Me → What ECHO Knows：Memory 一等页面。
 * 分类展示（你纠正过我的 / 我还不确定的 / 我已确认的）+ 类别过滤，
 * 四权：确认（强化）/ 纠正（编辑）/ 忘记（软删，可审计）。
 * 业务在 MemoryManagementViewModel；本节只渲染。
 */
@Composable
fun WhatEchoKnowsSection(container: AppContainer) {
    val vm: MemoryManagementViewModel = viewModel(factory = MemoryManagementViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val groups = groupMemories(state.memories, state.filter)

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What ECHO Knows About Me", style = MaterialTheme.typography.titleMedium)
            Text(
                "ECHO 的记忆由你的数据与你告诉它的话形成。你可以确认、纠正或忘记任何一条。",
                style = MaterialTheme.typography.bodySmall
            )
            // 类别过滤（§36 filter）
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf<Pair<MemoryType?, String>>(
                    null to "全部",
                    MemoryType.CORRECTION to "你纠正过我的",
                    MemoryType.USER_CONFIRMED to "我已确认的",
                    MemoryType.CONTEXT to "我告诉你的",
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = state.filter == type,
                        onClick = { vm.onEvent(MemoryManagementEvent.SetFilter(type)) },
                        label = { Text(label) }
                    )
                }
            }
            if (state.memories.isEmpty()) {
                Text("还没有长期记忆。ECHO 正在慢慢认识你。", style = MaterialTheme.typography.bodySmall)
            }
            groups.corrections.take(3).forEach { m -> MemoryRow("你纠正过我的", m, vm) }
            groups.uncertain.take(3).forEach { m -> MemoryRow("我还不确定的", m, vm) }
            groups.confirmed.take(3).forEach { m -> MemoryRow("我已确认的", m, vm) }
        }
    }
}

/** 单条记忆行（四权：编辑/确认/忘记；编辑为内联文本框）。 */
@Composable
private fun MemoryRow(label: String, memory: EchoMemory, vm: MemoryManagementViewModel) {
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
                    vm.onEvent(MemoryManagementEvent.Edit(memory.id, draft))
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
                }
                TextButton(onClick = { editing = true; draft = memory.content }) { Text("编辑") }
                TextButton(onClick = { vm.onEvent(MemoryManagementEvent.Confirm(memory.id)) }) { Text("确认") }
                TextButton(onClick = { vm.onEvent(MemoryManagementEvent.Forget(memory.id)) }) { Text("忘记") }
            }
        }
    }
}
