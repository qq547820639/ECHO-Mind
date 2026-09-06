package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.ui.artwork.DuskHorizon
import com.yunjue.echo.mind.ui.artwork.GrowthLineIconType
import com.yunjue.echo.mind.ui.artwork.StarfieldCanvas
import com.yunjue.echo.mind.ui.artwork.drawGrowthLineIcon
import com.yunjue.echo.mind.ui.journey.JourneyMiniOrganism
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import kotlin.math.cos
import kotlin.math.sin

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
 * 中央生命体 = [JourneyMiniOrganism]（与 Journey/主 ECHO 同一 identitySeed 的确定性渲染，
 * 与设计稿中央星球同构；genome 为 null 时 quiet ring 占位，不编造）。
 * 轨道环 / 星空底纹为静态装饰（确定性种子），不承载测量语义。
 *
 * @param rememberedFragmentsCount 已记住的重要记忆片段数量（来自 MemoryRepository.topMemories）
 * @param understandingDays 形成基线的天数（来自 BaselineStatusDto.baselineDays）
 * @param behaviorTrendText 最近行为变化描述（来自 Portrait 行为派生观察，null 表示数据不足）
 * @param behaviorTrendLabel 变化维度的简短标签（如 "情绪" / "能量" / "专注"）
 * @param accompanimentHours 被动感知活跃陪伴时长（小时）
 * @param timelinePoints 成长时间线节点列表（真实记录装配，≥2 个节点才渲染；见 buildGrowthTimeline）
 * @param onContinueClick "继续探索 ECHO" 按钮点击回调
 * @param organismGenome 中央生命体 genome（真实 seed/画像派生；null = quiet ring）
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
    organismGenome: EchoVisualGenome? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { testTag = "echo_growth_page" }
    ) {
        // 顶部紫色光晕装饰（设计稿 19 顶部氛围）
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

        // 星空底纹（确定性种子静态装饰）
        StarfieldCanvas(Modifier.fillMaxSize())

        // 山湖暮色地平线（设计稿 19 底部氛围层；纯装饰，置于内容之下）
        DuskHorizon(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .align(Alignment.BottomCenter),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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

            // 副标题（设计稿 19：它正越来越懂你的节律）
            Text(
                text = "它正越来越懂你的节律",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "它正越来越懂你的节律"
                    testTag = "growth_page_subtitle"
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 设计稿 19 放射布局：中央生命体 + 四角数据卡（卡片悬于轨道环边缘）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(470.dp),
            ) {
                OrganismOrbit(
                    genome = organismGenome,
                    modifier = Modifier.align(Alignment.Center),
                )
                GrowthDataCard(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(0.46f),
                    title = "已记住",
                    value = rememberedFragmentsCount?.toString() ?: "—",
                    unit = "个重要片段",
                    isTrend = false,
                    icon = GrowthLineIconType.BOOKMARK,
                    cardTag = "card_remembered_fragments",
                )
                GrowthDataCard(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth(0.46f),
                    title = "理解天数",
                    value = understandingDays?.toString() ?: "—",
                    unit = "天",
                    isTrend = false,
                    icon = GrowthLineIconType.CALENDAR,
                    cardTag = "card_understanding_days",
                )
                GrowthDataCard(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(0.46f),
                    title = "最近变化",
                    value = behaviorTrendText ?: "—",
                    unit = behaviorTrendLabel ?: "",
                    isTrend = true,
                    icon = GrowthLineIconType.WAVEFORM,
                    cardTag = "card_behavior_trend",
                )
                GrowthDataCard(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .fillMaxWidth(0.46f),
                    title = "陪伴时长",
                    value = accompanimentHours?.toString() ?: "—",
                    unit = "小时",
                    isTrend = false,
                    icon = GrowthLineIconType.HEART,
                    cardTag = "card_accompaniment_hours",
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 持续进化中（设计稿 19 文案；静态定位语，非测量数据）
            Text(
                text = "持续进化中",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .semantics {
                        contentDescription = "持续进化中"
                        testTag = "growth_evolution_title"
                    }
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "你的每一次表达，都让 ECHO 变得更完整",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 成长时间线（真实节点 ≥2 才渲染；数据不足时整体隐藏，不显示空壳）
            if (timelinePoints.size >= 2) {
                GrowthTimeline(
                    points = timelinePoints.take(4),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

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
 * 中央生命体轨道（设计稿 19）：径向光晕 + 双轨道环 + 4 个确定性方位节点
 * + 真实 genome 生命体（无 genome 时 quiet ring，不编造）。
 */
@Composable
private fun OrganismOrbit(
    genome: EchoVisualGenome?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.height(250.dp),
        contentAlignment = Alignment.Center
    ) {
        // 径向光晕
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0x307C3AED),
                            Color.Transparent
                        )
                    )
                )
        )
        // 轨道环 + 节点
        Canvas(modifier = Modifier.size(250.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = Color(0xFF8F7CF0).copy(alpha = 0.18f),
                radius = size.minDimension * 0.48f,
                center = c,
                style = Stroke(width = 1.5f),
            )
            drawCircle(
                color = Color(0xFF38BDF8).copy(alpha = 0.14f),
                radius = size.minDimension * 0.33f,
                center = c,
                style = Stroke(width = 1.5f),
            )
            // 外环 4 节点（设计稿 19 轨道节点；确定性角度）
            val outerR = size.minDimension * 0.48f
            listOf(15f, 105f, 195f, 285f).forEach { deg ->
                val rad = Math.toRadians(deg.toDouble())
                drawCircle(
                    color = Color(0xFF9DB8FF).copy(alpha = 0.85f),
                    radius = 3.5f,
                    center = Offset(
                        c.x + outerR * cos(rad).toFloat(),
                        c.y + outerR * sin(rad).toFloat(),
                    ),
                )
            }
        }
        // 中央生命体（真实 genome；null = quiet ring）
        Box(
            modifier = Modifier
                .size(170.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0x268F7CF0), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            JourneyMiniOrganism(genome = genome, size = 150.dp)
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
 * @param icon 标题左侧强调图标（设计稿 19 细线图标语义）
 * @param cardTag 测试标识
 */
@Composable
private fun GrowthDataCard(
    title: String,
    value: String,
    unit: String,
    isTrend: Boolean,
    icon: GrowthLineIconType,
    cardTag: String,
    modifier: Modifier = Modifier
) {
    val cardBackground = Color(0xE60E1426)
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
            .padding(14.dp)
            .semantics {
                contentDescription = "$title：$value$unit"
                testTag = cardTag
            }
    ) {
        Column {
            // 标题（含设计稿细线图标徽章）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        drawGrowthLineIcon(type = icon, color = accentColor)
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

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
 * 成长时间线组件（设计稿 19：水平轨道，节点圆点由连线贯穿，标签与日期居节点下方）
 *
 * 节点语义（日期锚定真实记录，见 buildGrowthTimeline）：
 * - 初次相遇（首条记录日）
 * - 开始理解（第 7 个记录日）
 * - 建立节律（第 30 个记录日）
 * - 越来越懂你（今天）
 *
 * @param points 时间线节点列表（按时间顺序排列，≥2 才渲染）
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

        // 水平节点轨道（设计稿 19）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            points.forEachIndexed { index, point ->
                TimelineNode(
                    point = point,
                    index = index,
                    count = points.size,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 水平时间线节点（圆点 + 左右连接线 + 下方标签/日期）
 *
 * @param point 时间线节点数据
 * @param index 节点序号（决定连接线绘制侧）
 * @param count 节点总数
 */
@Composable
private fun TimelineNode(
    point: GrowthTimelinePoint,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier
) {
    val connectorColor = Color(0xFF8F7CF0).copy(alpha = 0.35f)
    Column(
        modifier = modifier
            .semantics {
                contentDescription = "${point.label}：${point.date}"
                testTag = "timeline_node_${point.label}"
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.5f)
                        .height(2.dp)
                        .background(connectorColor)
                )
            }
            if (index < count - 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.5f)
                        .height(2.dp)
                        .background(connectorColor)
                )
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                // 节点光晕（设计稿 19 发光节点；越界绘制不裁剪）
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF8F7CF0).copy(alpha = if (point.isToday) 0.55f else 0.28f),
                                        Color.Transparent,
                                    )
                                )
                            )
                        }
                )
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
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 标签
        Text(
            text = point.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (point.isToday) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        // 日期
        Text(
            text = point.date,
            style = MaterialTheme.typography.labelSmall,
            color = if (point.isToday) {
                Color(0xFF8F7CF0) // 今天节点使用紫色高亮
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
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
