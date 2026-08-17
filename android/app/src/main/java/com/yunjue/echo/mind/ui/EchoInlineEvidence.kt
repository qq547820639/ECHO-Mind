package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.memory.CORRECTION_REASONS
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_LIKE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_QUESTION
import com.yunjue.echo.mind.model.PORTRAIT_COPY_FEEDBACK_SAVED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REGENERATE
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState

/**
 * V3 §AD/§AE — WHY 内联证据（≤1 tap）：Tap WHY 直接在 home 内容内展开
 * （AnimatedVisibility；无 bottom sheet / 无 0.42/0.82 progressive 机制）。
 *
 * 一级展开：今天的依据（ECHO 说了什么 / 今天观察到 / 个人平常 / 差异）
 * + 纠正入口「这不符合实际？」立即可见（§AE：纠正 ≤2 taps 到原因 chips）。
 * 二级展开「更多技术详情」：时间窗口 / coverage / 来源 / 没有使用 / Journey。
 */
@Composable
internal fun EchoInlineEvidence(
    uiState: EchoSceneUiState,
    portrait: PortraitUiState,
    feedbackActions: EchoSceneFeedbackActions,
    onGoToJourney: () -> Unit,
) {
    var techExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("今天的依据", style = MaterialTheme.typography.titleMedium)
        // 1. ECHO 说了什么
        Text(uiState.headline, style = MaterialTheme.typography.bodyLarge)
        uiState.aiLayer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        // 2–4. 今天观察到了什么 / 你的个人通常（基线）/ 两者差异（真实 facts）
        uiState.facts.forEach { fact ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (fact.label.isNotBlank()) Text(fact.label, style = MaterialTheme.typography.titleSmall)
                if (fact.todayText.isNotBlank()) {
                    Text("今天：${fact.todayText}", style = MaterialTheme.typography.bodySmall)
                }
                if (fact.baselineText.isNotBlank()) {
                    Text("平常：${fact.baselineText}", style = MaterialTheme.typography.bodySmall)
                }
                if (fact.deltaText.isNotBlank()) {
                    Text("变化：${fact.deltaText}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // §AE：纠正入口立即可见（READY/PARTIAL_DATA 才有反馈对象）
        if (portrait.status == PortraitStatus.READY || portrait.status == PortraitStatus.PARTIAL_DATA) {
            PortraitFeedbackContent(
                state = portrait,
                feedbackLookup = feedbackActions.portraitFeedbackFor,
                onLike = feedbackActions.onPortraitLike,
                onNotLike = feedbackActions.onPortraitNotLike,
                onCorrection = feedbackActions.onPortraitCorrection,
                onRebuild = feedbackActions.onRebuildTodayPortrait,
            )
        }
        // §AD：二级内联展开（时间窗口/coverage/来源/没有使用/Journey）
        TextButton(
            onClick = { techExpanded = !techExpanded },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (techExpanded) "收起技术详情" else "更多技术详情")
        }
        if (techExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                portrait.portrait?.let { p ->
                    Text(
                        "时间窗口：${p.date} · 基线 ${p.baselineDays} 天" +
                            (p.baselineVersion?.let { " · 基线版本 $it" } ?: "") +
                            (p.timezoneUsed?.let { " · 时区 $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    p.coverage?.takeIf { it.isNotEmpty() }?.let { cov ->
                        Text(
                            "数据覆盖：" + cov.entries.joinToString("、") { (k, v) -> "$k $v" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (uiState.headlineSources.isNotEmpty()) {
                    Text(
                        "参考了：" + uiState.headlineSources.joinToString("、") { dataSourceLabelForSheet(it) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 精确词表与对话层一致（原始音频/通知正文/精确位置 永不进入）
                Text("没有使用：原始音频、通知正文、精确位置", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onGoToJourney, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("查看更多 → Journey")
                }
            }
        }
    }
}

/**
 * ERA 36/§AE — 画像反馈纯内容：「挺像」+「这不符合实际？」直达原因 chips
 * （≤2 taps）；不直接创建 MemoryEntity，不持有 ViewModel。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortraitFeedbackContent(
    state: PortraitUiState,
    feedbackLookup: (String) -> Boolean?,
    onLike: (String) -> Unit,
    onNotLike: (String) -> Unit,
    onCorrection: (String, String, String?) -> Unit,
    onRebuild: () -> Unit,
) {
    val portrait = state.portrait ?: return
    val date = portrait.date
    var feedback by remember(date) { mutableStateOf(feedbackLookup(date)) }
    var reasonPicked by remember(date) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        if (feedback == null) {
            // §AE：「这不符合实际？」一步进入原因 chips（记录 not-like 后展开）
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    onLike(date)
                    feedback = true
                }) { Text(PORTRAIT_COPY_FEEDBACK_LIKE) }
                TextButton(
                    onClick = {
                        onNotLike(date)
                        feedback = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(PORTRAIT_COPY_FEEDBACK_QUESTION) }
            }
        } else {
            Text(PORTRAIT_COPY_FEEDBACK_SAVED, style = MaterialTheme.typography.bodySmall)
            if (feedback == false) {
                if (!reasonPicked) {
                    Text("今天有什么不一样？", style = MaterialTheme.typography.bodySmall)
                    // §AJ：FlowRow——360dp / fontScale 1.5 永不溢出
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CORRECTION_REASONS.forEach { reason ->
                            AssistChip(
                                onClick = {
                                    reasonPicked = true
                                    onCorrection(date, reason, portrait.summary)
                                },
                                label = { Text(reason) }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onRebuild,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(PORTRAIT_COPY_REGENERATE)
                }
            }
        }
    }
}

/** 数据源类别 → 用户可读标签（WHY 依据展示）。 */
private fun dataSourceLabelForSheet(category: DataSourceCategory): String = when (category) {
    DataSourceCategory.TODAY_AGGREGATE -> "今天的活动节律"
    DataSourceCategory.BASELINE -> "个人基线"
    DataSourceCategory.PORTRAIT_HISTORY -> "历史画像"
    DataSourceCategory.CONTEXT_EXCEPTIONS -> "你告诉我的特殊日期"
    DataSourceCategory.USER_CORRECTIONS -> "你纠正过我的"
    DataSourceCategory.PREFERENCES -> "你的偏好"
    DataSourceCategory.CONVERSATION_HISTORY -> "我们的对话"
    else -> "其他"
}
