package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 设计稿 19 — ECHO 成长页面
 *
 * 行为观察性数据展示（无心理诊断推断）：
 * - 已记住 N 个重要片段（记忆条数）
 * - 理解天数（基线形成天数）
 * - 最近行为变化趋势（来自 Portrait/Baseline 的行为派生观察）
 * - 陪伴时长（被动感知活跃时长）
 *
 * 所有数值来自真实数据源（PortraitRepository / MemoryRepository / BaselineSource）。
 * 数据不可用时显示 "—" 而非硬编码假数据。
 *
 * @param rememberedFragmentsCount 已记住的重要记忆片段数量（来自 MemoryRepository.topMemories）
 * @param understandingDays 形成基线的天数（来自 BaselineStatusDto.baselineDays）
 * @param behaviorTrendText 最近行为变化描述（来自 Portrait 行为派生观察，null 表示数据不足）
 * @param behaviorTrendLabel 变化维度的简短标签（如 "情绪" / "能量" / "专注"）
 * @param accompanimentHours 被动感知活跃陪伴时长（小时）
 * @param timelinePoints 成长时间线节点列表（至少需要4个节点）
 * @param onContinueClick "继续探索 ECHO" 按钮点击回调
 */
@Composable
fun EchoGrowthPage(
    rememberedFragmentsCount: Int?,
    understandingDays: Int?,
    behaviorTrendText: String?,
    behaviorTrendLabel: String?,
    accompanimentHours: Int?,
    timelinePoints: List<GrowthTimelinePoint>,
    onContinueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { testTag = "echo_growth_page" }
    ) {
        // 顶部紫色光晕装饰
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.TopCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x1A8F7CF0),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // 页面标题
            Text(
                text = "ECHO 的成长",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics {
                    contentDescription = "ECHO 的成长"
                    testTag = "growth_page_title"
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 副标题
            Text(
                text = "与你同行的观察记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "与你同行的观察记录"
                    testTag = "growth_page_subtitle"
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 四项数据卡片区域
            GrowthDataCardsRow(
                rememberedFragmentsCount = rememberedFragmentsCount,
                understandingDays = understandingDays,
                behaviorTrendText = behaviorTrendText,
                behaviorTrendLabel = behaviorTrendLabel,
                accompanimentHours = accompanimentHours
            )

            Spacer(modifier = Modifier.weight(1f))

            // 成长时间线
            if (timelinePoints.size >= 4) {
                GrowthTimeline(
                    points = timelinePoints.take(4),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 主按钮
            EchoGradientButton(
                onClick = onContinueClick,
                text = "继续探索 ECHO",
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "继续探索 ECHO 按钮"
                        testTag = "continue_explore_button"
                    }
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/**
 * 四项数据卡片行（2x2 网格布局）
 */
@Composable
private fun GrowthDataCardsRow(
    rememberedFragmentsCount: Int?,
    understandingDays: Int?,
    behaviorTrendText: String?,
    behaviorTrendLabel: String?,
    accompanimentHours: Int?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 第一行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GrowthDataCard(
                modifier = Modifier.weight(1f),
                title = "已记住",
                value = rememberedFragmentsCount?.toString() ?: "—",
                unit = "个重要片段",
                isTrend = false,
                cardTag = "card_remembered_fragments"
            )
            GrowthDataCard(
                modifier = Modifier.weight(1f),
                title = "理解天数",
                value = understandingDays?.toString() ?: "—",
                unit = "天",
                isTrend = false,
                cardTag = "card_understanding_days"
            )
        }

        // 第二行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GrowthDataCard(
                modifier = Modifier.weight(1f),
                title = "最近变化",
                value = behaviorTrendText ?: "—",
                unit = behaviorTrendLabel ?: "",
                isTrend = true,
                cardTag = "card_behavior_trend"
            )
            GrowthDataCard(
                modifier = Modifier.weight(1f),
                title = "陪伴时长",
                value = accompanimentHours?.toString() ?: "—",
                unit = "小时",
                isTrend = false,
                cardTag = "card_accompaniment_hours"
            )
        }
    }
}

/**
 * 成长数据卡片
 *
 * @param title 卡片标题（如 "已记住"、"理解天数"）
 * @param value 主数值（数据不可用时为 "—"）
 * @param unit 单位描述（如 "个重要片段"、"天"）
 * @param isTrend 是否为趋势变化卡片（使用不同强调色）
 * @param testTag 测试标识
 */
@Composable
private fun GrowthDataCard(
    title: String,
    value: String,
    unit: String,
    isTrend: Boolean,
    cardTag: String,
    modifier: Modifier = Modifier
) {
    val cardBackground = Color(0xFF0E1426)
    val accentColor = if (isTrend) {
        Color(0xFF38BDF8) // 趋势卡片使用蓝色强调
    } else {
        Color(0xFF8F7CF0) // 常规卡片使用紫色
    }
    val valueColor = if (value == "—") {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    } else {
        accentColor
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(cardBackground)
            .padding(16.dp)
            .semantics {
                contentDescription = "$title：$value$unit"
                testTag = cardTag
            }
    ) {
        Column {
            // 标题
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 数值（带光晕效果）
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = valueColor
            )

            // 单位
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * 成长时间线组件
 *
 * 展示用户与 ECHO 的关键成长节点：
 * - 初次相遇（首次使用）
 * - 开始理解（基线开始形成）
 * - 建立节律（基线稳定）
 * - 越来越懂你（持续成长）
 *
 * @param points 时间线节点列表（需要按时间顺序排列）
 * @param modifier 修饰符
 */
@Composable
private fun GrowthTimeline(
    points: List<GrowthTimelinePoint>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A1020))
            .padding(20.dp)
            .semantics {
                contentDescription = "成长时间线，包含 ${points.size} 个节点"
                testTag = "growth_timeline"
            }
    ) {
        Text(
            text = "成长轨迹",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics {
                contentDescription = "成长轨迹"
                testTag = "timeline_title"
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 时间线节点
        points.forEachIndexed { index, point ->
            TimelineNode(
                point = point,
                isLast = index == points.lastIndex
            )

            if (index < points.lastIndex) {
                TimelineConnector()
            }
        }
    }
}

/**
 * 时间线节点
 *
 * @param point 时间线节点数据
 * @param isLast 是否为最后一个节点
 */
@Composable
private fun TimelineNode(
    point: GrowthTimelinePoint,
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${point.label}：${point.date}"
                testTag = "timeline_node_${point.label}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 节点圆点
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (point.isToday) {
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF7C3AED),
                                Color(0xFF38BDF8)
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF8F7CF0),
                                Color(0xFF8F7CF0)
                            )
                        )
                    }
                )
        )

        Spacer(modifier = Modifier.width(12.dp))

        // 标签
        Text(
            text = point.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (point.isToday) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )

        // 日期
        Text(
            text = point.date,
            style = MaterialTheme.typography.bodySmall,
            color = if (point.isToday) {
                Color(0xFF8F7CF0) // 今天节点使用紫色高亮
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            }
        )
    }
}

/**
 * 时间线连接线
 */
@Composable
private fun TimelineConnector(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(start = 5.dp)
            .width(2.dp)
            .height(24.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF8F7CF0).copy(alpha = 0.5f),
                        Color(0xFF8F7CF0).copy(alpha = 0.2f)
                    )
                )
            )
    )
}

/**
 * 成长时间线节点数据
 *
 * @param label 节点标签（如 "初次相遇"、"开始理解"）
 * @param date 节点日期（格式如 "7月1日"、"今天"）
 * @param isToday 是否为"今天"节点（使用特殊样式）
 */
data class GrowthTimelinePoint(
    val label: String,
    val date: String,
    val isToday: Boolean = false
)
