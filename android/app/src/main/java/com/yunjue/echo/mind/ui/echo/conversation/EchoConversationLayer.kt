package com.yunjue.echo.mind.ui.echo.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.intelligence.ConversationPhase
import com.yunjue.echo.mind.intelligence.ConversationTurn
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.conversationPhaseText
import com.yunjue.echo.mind.memory.CORRECTION_REASONS

/**
 * v3 §9/§15 + V3 §46 — EchoConversationLayer：Ask ECHO 对话层。
 *
 * §46：不是 ChatGPT 页——user message 轻 tinted bubble；ECHO answer = plain text +
 * small identity glyph；不要灰色 bubble wall；THINKING 只用克制的状态文案（无 spinner）；
 * No Provider 与 Provider answer 同一视觉结构。
 * 每条回答：依据双清单（参考了/没有使用）+ 反馈（像我/不太像 + 原因 → Correction Service）。
 * 会话仅存内存（Memory ≠ 聊天记录）；状态机文案来自 [conversationPhaseText]。
 */
@Composable
fun EchoConversationLayer(
    turns: List<ConversationTurn>,
    phase: ConversationPhase,
    onAsk: (String) -> Unit,
    onFeedback: (question: String, answer: String, like: Boolean, reason: String?) -> Unit,
    /** small identity glyph 的颜色（identity palette primary；调用侧从 identity 派生）。 */
    identityColor: Color = MaterialTheme.colorScheme.primary,
) {
    var question by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("问 ECHO 关于你的事", style = MaterialTheme.typography.titleSmall)
        Text(
            "适合问：「最近我是不是越来越晚？」「为什么今天 ECHO 看起来不一样？」ECHO 的回答基于你的节律数据，会说明参考了什么。",
            style = MaterialTheme.typography.bodySmall,
        )
        turns.forEach { turn ->
            EchoConversationMessage(
                turn = turn,
                onFeedback = onFeedback,
                identityColor = identityColor,
            )
        }
        // THINKING：无 spinner——只有非常克制的状态文案（§46）
        if (phase != ConversationPhase.IDLE) {
            Text(
                conversationPhaseText(phase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            )
        }
        // 设计稿 6：胶囊输入框 + 圆形渐变发送钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问 ECHO…") },
                singleLine = true,
                enabled = phase == ConversationPhase.IDLE,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            val sendEnabled = phase == ConversationPhase.IDLE && question.isNotBlank()
            val currentSendEnabled by androidx.compose.runtime.rememberUpdatedState(sendEnabled)
            val currentQuestion by androidx.compose.runtime.rememberUpdatedState(question)
            val currentOnAsk by androidx.compose.runtime.rememberUpdatedState(onAsk)
            val currentClearInput by androidx.compose.runtime.rememberUpdatedState({ question = "" })
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF7C3AED), Color(0xFF38BDF8))
                        )
                    )
                    .semantics { contentDescription = "发送" }
                    .clickable {
                        if (currentSendEnabled) {
                            val q = currentQuestion.trim()
                            if (q.isNotBlank()) {
                                currentClearInput()
                                currentOnAsk(q)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}

/** 单条问答（设计稿 6：user 渐变气泡居右 + ECHO 头像深色气泡居左 + 理由卡 + 反馈）。 */
@Composable
private fun EchoConversationMessage(
    turn: ConversationTurn,
    onFeedback: (question: String, answer: String, like: Boolean, reason: String?) -> Unit,
    identityColor: Color,
) {
    var basisExpanded by remember { mutableStateOf(false) }
    var feedback by remember(turn.id) { mutableStateOf<Boolean?>(null) }
    var reasonPicked by remember(turn.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // user message：右对齐渐变气泡（设计稿 6）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6366F1)))
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(turn.question, style = MaterialTheme.typography.bodySmall, color = Color.White)
            }
        }
        // ECHO answer：生命体色头像 + 深色气泡（设计稿 6）
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(identityColor, identityColor.copy(alpha = 0.35f))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("E", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(Color(0xFF16203A))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(turn.answer, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // 理由卡（v2 §51：参考了 / 没有使用 双清单；设计稿 6 绿勾语义）
        TextButton(onClick = { basisExpanded = !basisExpanded }) {
            Text(if (basisExpanded) "收起依据" else "依据")
        }
        if (basisExpanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0E1426))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = Color(0xFF34D399), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "参考了：" + if (turn.sources.isEmpty()) "（无可追溯来源——请谨慎看待）"
                        else turn.sources.joinToString("、") { dataSourceLabelForConversation(it) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // ERA 32 R09（§52）：精确词表——「原始音频」而非「麦克风」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = Color(0xFF34D399).copy(alpha = 0.55f), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Text("没有使用：原始音频、通知正文、精确位置", style = MaterialTheme.typography.bodySmall)
                }
            }
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
            // §AJ：FlowRow——360dp / fontScale 1.5 永不溢出（与 EchoInlineEvidence 一致）
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CORRECTION_REASONS.forEach { reason ->
                    AssistChip(
                        onClick = {
                            reasonPicked = true
                            onFeedback(turn.question, turn.answer, false, reason)
                        },
                        label = { Text(reason) },
                    )
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
