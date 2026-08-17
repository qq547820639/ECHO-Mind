package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.BuildConfig
import java.time.LocalDate

/**
 * ERA 29 §64 — 内部质量反馈（仅 DEBUG 构建可见，绝不污染正式用户 UI）：
 * 「这个 ECHO 今天真实吗？」「这条解释有用吗？」「这个变化明显吗？」
 * 三个 yes/no 一键记录（本地 AppPreferences，当日可改），供 dogfood 回填。
 */
@Composable
fun InternalQualityFeedback(
    preferences: AppPreferences,
    headline: String,
) {
    if (!BuildConfig.DEBUG) return
    val today = remember { LocalDate.now().toString() }
    var recorded by remember(today) { mutableStateOf(preferences.internalFeedbackFor(today)) }

    fun toggle(field: String) {
        val next = recorded.toMutableSet()
        if (field in next) next.remove(field) else next.add(field)
        recorded = next
        preferences.recordInternalFeedback(today, field)
    }

    @Composable
    fun Row3(label: String, field: String) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { toggle("${field}_yes") }) {
                    Text(
                        if ("${field}_yes" in recorded) "✓ 是" else "是",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { toggle("${field}_no") }) {
                    Text(
                        if ("${field}_no" in recorded) "✓ 否" else "否",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        HorizontalDivider()
        Text("内部质量反馈（仅调试构建）", style = MaterialTheme.typography.labelSmall)
        Row3("这个 ECHO 今天真实吗？", "echoReal")
        Row3("这条解释有用吗？", "explanationUseful")
        Row3("这个变化明显吗？", "changeVisible")
        if (headline.isNotBlank()) {
            Text("headline：$headline", style = MaterialTheme.typography.labelSmall)
        }
    }
}
