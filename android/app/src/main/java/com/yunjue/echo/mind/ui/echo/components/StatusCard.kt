package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * V3 §AK — 三指标状态卡片（情绪 / 能量 / 专注）。
 *
 * 设计稿规范（图 5）：
 * - 三卡等宽横排，间距 12dp。
 * - 每卡：深色卡片（#0E1426，圆角 16-20dp），
 *   上方圆角图标区（图标+圆底）+ 下方指标名 + 数值/状态文字。
 * - 指标色系：情绪=青色波浪、能量=绿/橙闪电、专注=紫色同心圆。
 * - Abstain（未知）时：数值显示 "—" 而非假数字。
 *
 * [value] 为 null 时表示数据不足（abstain），显示 "--" 并用较暗色值。
 * [value] 有效时显示数值 + 单位（能量/专注为 %）。
 */
@Composable
fun StatusCard(
    label: String,
    value: String?,
    icon: StatusCardIcon,
    modifier: Modifier = Modifier,
    contentDesc: String? = null,
) {
    val cardBg = Color(0xFF0E1426)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val valueColor = if (value != null) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val displayValue = value ?: "—"

    Box(
        modifier = modifier
            .width(100.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(cardBg)
            .padding(vertical = 12.dp, horizontal = 10.dp)
            .semantics {
                contentDesc?.let { this.contentDescription = it }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 图标区（圆形彩色背景 + 文字图标）
            StatusCardIconView(icon, size = 28.dp)

            // 指标名称
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )

            // 数值
            Text(
                text = displayValue,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = valueColor,
                fontSize = 18.sp,
            )
        }
    }
}

/** 图标文字（emoji），避免 Canvas 绘制导入冲突。 */
@Composable
private fun StatusCardIconView(icon: StatusCardIcon, size: androidx.compose.ui.unit.Dp) {
    val iconText: String
    val iconColor: Color
    when (icon) {
        StatusCardIcon.EMOTION -> {
            iconText = "🌊"
            iconColor = Color(0xFF22D3EE)
        }
        StatusCardIcon.ENERGY -> {
            iconText = "⚡"
            iconColor = Color(0xFF34D399)
        }
        StatusCardIcon.FOCUS -> {
            iconText = "✦"
            iconColor = Color(0xFFA855F7)
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(iconColor.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = iconText,
            style = MaterialTheme.typography.titleSmall,
            color = iconColor,
        )
    }
}

/** 三指标卡横排容器。 */
@Composable
fun StatusCardsRow(
    emotion: StatusCardData?,
    energy: StatusCardData?,
    focus: StatusCardData?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(
            label = "情绪",
            value = emotion?.displayValue,
            icon = StatusCardIcon.EMOTION,
            contentDesc = "情绪：${emotion?.displayValue ?: "未知"}",
            modifier = Modifier.weight(1f),
        )
        StatusCard(
            label = "能量",
            value = energy?.displayValue,
            icon = StatusCardIcon.ENERGY,
            contentDesc = "能量：${energy?.displayValue ?: "未知"}",
            modifier = Modifier.weight(1f),
        )
        StatusCard(
            label = "专注",
            value = focus?.displayValue,
            icon = StatusCardIcon.FOCUS,
            contentDesc = "专注：${focus?.displayValue ?: "未知"}",
            modifier = Modifier.weight(1f),
        )
    }
}

/** 状态卡片数据模型（Stage 1 接占位，Stage 2 接真实派生值）。 */
data class StatusCardData(
    val displayValue: String,   // 如 "平静"、"62%"、"68%"
    val level: StatusLevel = StatusLevel.UNKNOWN,
)

enum class StatusLevel {
    LOW, MEDIUM, HIGH, UNKNOWN
}

/** 指标卡图标类型。 */
enum class StatusCardIcon {
    EMOTION,   // 青色波浪
    ENERGY,    // 绿/橙色闪电
    FOCUS,     // 紫色同心圆
}
