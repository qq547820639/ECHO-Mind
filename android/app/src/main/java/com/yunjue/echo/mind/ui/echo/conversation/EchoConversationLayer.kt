package com.yunjue.echo.mind.ui.echo.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.intelligence.ConversationPhase
import com.yunjue.echo.mind.intelligence.ConversationTurn
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.conversationPhaseText
import com.yunjue.echo.mind.memory.CORRECTION_REASONS

/**
 * v3 §9/§15 — EchoConversationLayer：Ask ECHO 对话层（Scene 展开，ECHO 保持可见）。
 * 每条回答：依据双清单（参考了/没有使用）+ 反馈（像我/不太像 + 原因 → Correction Service）。
 * 会话仅存内存（Memory ≠ 聊天记录）；状态机文案来自 [conversationPhaseText]。
 */
@Composable
fun EchoConversationLayer(
    turns: List<ConversationTurn>,
    phase: ConversationPhase,
    onAsk: (String) -> Unit,
    onFeedback: (question: String, answer: String, like: Boolean, reason: String?) -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("问 ECHO 关于你的事", style = MaterialTheme.typography.titleSmall)
            Text(
                "适合问：「最近我是不是越来越晚？」「为什么今天 ECHO 看起来不一样？」ECHO 的回答基于你的节律数据，会说明参考了什么。",
                style = MaterialTheme.typography.bodySmall
            )
            turns.forEach { turn ->
                EchoConversationMessage(
                    turn = turn,
                    onFeedback = onFeedback,
                )
            }
            if (phase != ConversationPhase.IDLE) {
                Text(
                    conversationPhaseText(phase),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("问 ECHO…") },
                    singleLine = true,
                    enabled = phase == ConversationPhase.IDLE,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val q = question.trim()
                        if (q.isNotBlank()) {
                            question = ""
                            onAsk(q)
                        }
                    },
                    enabled = phase == ConversationPhase.IDLE && question.isNotBlank(),
                ) { Text("发送") }
            }
        }
    }
}

/** 单条问答：问题 + 回答 + 依据双清单 + 反馈（UI 不自己写记忆，回调交给 Controller/Service）。 */
@Composable
private fun EchoConversationMessage(
    turn: ConversationTurn,
    onFeedback: (question: String, answer: String, like: Boolean, reason: String?) -> Unit,
) {
    var basisExpanded by remember { mutableStateOf(false) }
    var feedback by remember(turn.id) { mutableStateOf<Boolean?>(null) }
    var reasonPicked by remember(turn.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("你：${turn.question}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text("ECHO：${turn.answer}", style = MaterialTheme.typography.bodyMedium)
        // 依据（v2 §51：参考了 / 没有使用 双清单）
        TextButton(onClick = { basisExpanded = !basisExpanded }) {
            Text(if (basisExpanded) "收起依据" else "依据")
        }
        if (basisExpanded) {
            Text(
                "参考了：" + if (turn.sources.isEmpty()) "（无可追溯来源——请谨慎看待）"
                else turn.sources.joinToString("、") { dataSourceLabelForConversation(it) },
                style = MaterialTheme.typography.bodySmall
            )
            // ERA 32 R09（§52）：精确词表——「原始音频」而非「麦克风」：麦克风可选开启时
            // 派生特征（音量/语速级）会进入聚合，但原始音频永不进入任何回答/AI 上下文。
            Text("没有使用：原始音频、通知正文、精确位置", style = MaterialTheme.typography.bodySmall)
        }
        // 反馈（v3 §17：走 EchoCorrectionService，不直接创建 MemoryEntity）
        if (feedback == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    feedback = true
                    onFeedback(turn.question, turn.answer, true, null)
                }) { Text("像我") }
                TextButton(onClick = { feedback = false; reasonPicked = false }) { Text("不太像") }
            }
        } else if (feedback == true) {
            Text("已记录，感谢反馈。", style = MaterialTheme.typography.bodySmall)
        } else if (!reasonPicked) {
            Text("哪里不太对？", style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CORRECTION_REASONS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { reason ->
                            AssistChip(
                                onClick = {
                                    reasonPicked = true
                                    onFeedback(turn.question, turn.answer, false, reason)
                                },
                                label = { Text(reason) }
                            )
                        }
                    }
                }
            }
        } else {
            Text("知道了，我会少一点依赖这种判断。", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

private fun dataSourceLabelForConversation(category: DataSourceCategory): String = when (category) {
    DataSourceCategory.TODAY_AGGREGATE -> "今天的活动节律"
    DataSourceCategory.BASELINE -> "个人基线"
    DataSourceCategory.PORTRAIT_HISTORY -> "历史画像"
    DataSourceCategory.CONTEXT_EXCEPTIONS -> "你告诉我的特殊日期"
    DataSourceCategory.USER_CORRECTIONS -> "你纠正过我的"
    DataSourceCategory.PREFERENCES -> "你的偏好"
    DataSourceCategory.CONVERSATION_HISTORY -> "我们的对话"
    else -> "其他"
}
