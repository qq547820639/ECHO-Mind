package com.yunjue.echo.mind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * V3 §AC/§AI — transient status（home 唯一 transient = wallpaper pill）
 * + day-key 驱动的「今天」日期。
 */

/**
 * §AC — AmbientPromptPill：wallpaper 引导一次一颗，安静可关闭。
 * AI provider 提示分支已移除（ONLY ECHO MAY BE UNFAMILIAR：home 无 AI 营销）。
 */
@Composable
internal fun AmbientPromptPill(
    dismissed: Boolean,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (dismissed) return
    Row(
        modifier
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSelect) {
            Text("让 ECHO 留在桌面：设置动态壁纸 →", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onDismiss) {
            Text("以后再说", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * §AI — day-key 驱动的「今天」：不依赖组合瞬间的 now()，
 * 跨午夜（+1 分钟缓冲）自动翻转；静止期零重组。
 */
@Composable
internal fun rememberToday(format: DateTimeFormatter): String {
    var value by remember { mutableStateOf(LocalDate.now().format(format)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(Duration.between(now, nextMidnight).plusMinutes(1).toMillis())
            value = LocalDate.now().format(format)
        }
    }
    return value
}

private val TODAY_MD_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/** §AI：「M月d日」形式的今天（day-key 驱动）。 */
@Composable
internal fun rememberTodayMd(): String = rememberToday(TODAY_MD_FORMAT)
