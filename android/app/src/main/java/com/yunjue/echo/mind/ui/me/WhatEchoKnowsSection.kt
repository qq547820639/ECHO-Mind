package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v3 §28/§30 — Me → What ECHO Knows：Memory 一等页面。
 * 分类展示（你的节律 / 确认过的上下文 / 纠正记录 / 还不确定的），
 * 四权齐全：确认（强化）/ 纠正 / 编辑 / 忘记（软删，可审计）。
 */
@Composable
fun WhatEchoKnowsSection(container: AppContainer, scope: CoroutineScope) {
    val memories by container.memoryRepository.observeMemories().collectAsStateWithLifecycle(initialValue = emptyList())
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What ECHO Knows About Me", style = MaterialTheme.typography.titleMedium)
            Text(
                "ECHO 的记忆由你的数据与你告诉它的话形成。你可以确认、纠正或忘记任何一条。",
                style = MaterialTheme.typography.bodySmall
            )
            val corrections = memories.filter { it.type == MemoryType.CORRECTION }
            val uncertain = memories.filter { it.confidence < 0.5f && it.type != MemoryType.CORRECTION }
            val confirmed = memories.filter { it !in corrections && it !in uncertain }
            if (memories.isEmpty()) {
                Text("还没有长期记忆。ECHO 正在慢慢认识你。", style = MaterialTheme.typography.bodySmall)
            }
            corrections.take(3).forEach { m ->
                MemoryRow(
                    label = "你纠正过我的",
                    memory = m,
                    onConfirm = { scope.launch { container.memoryRepository.confirm(m.id) } },
                    onForget = { scope.launch { container.memoryRepository.forget(m.id) } },
                    onEdit = { text -> scope.launch { container.memoryRepository.edit(m.id, text) } },
                )
            }
            uncertain.take(3).forEach { m ->
                MemoryRow(
                    label = "我还不确定的",
                    memory = m,
                    onConfirm = { scope.launch { container.memoryRepository.confirm(m.id) } },
                    onForget = { scope.launch { container.memoryRepository.forget(m.id) } },
                    onEdit = { text -> scope.launch { container.memoryRepository.edit(m.id, text) } },
                )
            }
            confirmed.take(3).forEach { m ->
                MemoryRow(
                    label = "我已确认的",
                    memory = m,
                    onConfirm = { scope.launch { container.memoryRepository.confirm(m.id) } },
                    onForget = { scope.launch { container.memoryRepository.forget(m.id) } },
                    onEdit = { text -> scope.launch { container.memoryRepository.edit(m.id, text) } },
                )
            }
        }
    }
}

/** 单条记忆行（四权：编辑/确认/忘记；编辑为内联文本框）。 */
@Composable
private fun MemoryRow(
    label: String,
    memory: EchoMemory,
    onConfirm: () -> Unit,
    onForget: () -> Unit,
    onEdit: (String) -> Unit,
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
                    onEdit(draft)
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
                TextButton(onClick = onConfirm) { Text("确认") }
                TextButton(onClick = onForget) { Text("忘记") }
            }
        }
    }
}
