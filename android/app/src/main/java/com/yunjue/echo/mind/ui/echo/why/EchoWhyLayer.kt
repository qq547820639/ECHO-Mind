package com.yunjue.echo.mind.ui.echo.why

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.model.PortraitFactDto
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState

/**
 * v3 §9/§14 — EchoWhyLayer：Progressive Explanation 三层。
 * Layer 1 一句话（由 EchoSceneUiState 装配）→ Layer 2 点「为什么？」Scene 内展开 facts →
 * Layer 3「查看更多 → Journey」（定量证据；z_score/coverage 不暴露在第一视觉）。
 * 只渲染 [EchoSceneUiState]，不接触 Repository。
 */
@Composable
fun EchoWhyLayer(
    uiState: EchoSceneUiState,
    onGoToJourney: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Layer 1：确定性一句话（产品真值）
        Text(
            uiState.headline,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )
        // ERA 20 §9 AI 层：与确定性 headline 不同的增量理解（克制展示，附依据）
        uiState.aiLayer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        if (uiState.headlineLevel == NarrativeFallbackLevel.AI_NARRATIVE &&
            uiState.headlineSources.isNotEmpty()
        ) {
            Text(
                "依据：${uiState.headlineSources.joinToString("、") { dataSourceLabelForWhy(it) }}（点「为什么？」看事实）",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!uiState.intelligenceAvailable) {
            Text("连接 AI 后可获得更深入的解释。", style = MaterialTheme.typography.bodySmall)
        }

        // Layer 2：为什么？（Scene 内展开，不切详情页）
        EchoEvidenceCards(uiState.facts)

        // Layer 3：查看更多 → Journey
        OutlinedButton(onClick = onGoToJourney, modifier = Modifier.fillMaxWidth()) {
            Text("查看更多 → Journey")
        }
    }
}

/** Layer 2：facts 证据卡（label + 今天/平常/变化对照）。 */
@Composable
private fun EchoEvidenceCards(facts: List<PortraitFactDto>) {
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("为什么？", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "展开")
        }
    }
    if (expanded) {
        if (facts.isEmpty()) {
            Text("暂无更多细节。", style = MaterialTheme.typography.bodySmall)
        }
        facts.forEach { fact ->
            // ERA 31：证据行去卡化——事实属于 ECHO 的呼吸，不属于 UI 卡片
            Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (fact.label.isNotBlank()) {
                    Text(fact.label, style = MaterialTheme.typography.titleSmall)
                }
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
    }
}

/** 数据源类别 → 用户可读标签（依据展示，不泄露内部名）。 */
private fun dataSourceLabelForWhy(category: DataSourceCategory): String = when (category) {
    DataSourceCategory.TODAY_AGGREGATE -> "今天的活动节律"
    DataSourceCategory.BASELINE -> "个人基线"
    DataSourceCategory.PORTRAIT_HISTORY -> "历史画像"
    DataSourceCategory.CONTEXT_EXCEPTIONS -> "你告诉我的特殊日期"
    DataSourceCategory.USER_CORRECTIONS -> "你纠正过我的"
    DataSourceCategory.PREFERENCES -> "你的偏好"
    DataSourceCategory.CONVERSATION_HISTORY -> "我们的对话"
    else -> "其他"
}
