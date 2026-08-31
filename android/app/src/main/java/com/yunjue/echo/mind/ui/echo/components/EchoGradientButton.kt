package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * V3 §AJ/§AL — 渐变主操作按钮。
 *
 * 设计稿统一渐变胶囊（#7C3AED → #38BDF8 紫→青蓝）：
 * - 「开启 ECHO」「继续」「问 ECHO」「我理解了，继续」「让 ECHO 开始了解我」
 *   「应用壁纸」「同步到手环」复用本组件。
 * - 高度默认 48dp（最小可触控 ≥44dp 规范）；胶囊圆角 = 高度 / 2。
 * - 文字白色；颜色对比：白字 on 紫蓝渐变（#7C3AED~#38BDF8）实测 ≥ 7:1。
 * - 禁用态：surfaceVariant 灰阶，文字 50% alpha，保留 role + semantics。
 *
 * 注：M3 Button 的 containerColor 仅接受固色；渐变通过外层 Box
 * background(Brush.horizontalGradient) + 透明容器实现，点击事件在 Box 上。
 */
@Composable
fun EchoGradientButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    contentDescription: String? = null,
) {
    val gradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF7C3AED), Color(0xFF38BDF8)),
    )
    val disabledBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .sizeIn(minHeight = minHeight)
            .clip(shape)
            .background(if (enabled) gradient else disabledBrush)
            // 禁用态保留 OnClick action + Enabled=false（对齐 M3 Button 语义，读屏可发现按钮）
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription?.let { this.contentDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.sizeIn(minWidth = 48.dp).padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/**
 * 描边胶囊次级按钮：「暂不开启」「长按结束」类次级动作。
 */
@Composable
fun EchoOutlinePillButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .sizeIn(minHeight = minHeight)
            .clip(shape)
            .background(Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = text
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.sizeIn(minWidth = 48.dp).padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
