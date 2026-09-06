package com.yunjue.echo.mind.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import com.yunjue.echo.mind.ui.artwork.drawGrowthLineIcon
import com.yunjue.echo.mind.ui.artwork.drawPrivacyLineIcon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOADING_QUIET
import com.yunjue.echo.mind.model.PORTRAIT_COPY_LOAD_FAILED
import com.yunjue.echo.mind.model.PORTRAIT_COPY_OFFLINE_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_PARTIAL_BANNER
import com.yunjue.echo.mind.model.PORTRAIT_COPY_REENABLE
import com.yunjue.echo.mind.model.PORTRAIT_COPY_RETRY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SECTION_WHY
import com.yunjue.echo.mind.model.PORTRAIT_COPY_SENSING_DISABLED
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.PortraitUiState
import com.yunjue.echo.mind.ui.echo.EchoSceneUiState
import com.yunjue.echo.mind.ui.echo.components.EchoGradientButton
import com.yunjue.echo.mind.ui.echo.components.EchoStatusOverlay
import com.yunjue.echo.mind.ui.echo.components.StatusCardData
import com.yunjue.echo.mind.ui.echo.components.StatusLevel
import com.yunjue.echo.mind.ui.echo.components.StatusCardsRow
import com.yunjue.echo.mind.ui.echo.components.UnlockBanner

/**
 * V3 §AB/§AH — Home 内容：visual + narrative + Why/Ask/Action 入口。
 *
 * 正常 READY home 只显示：ECHO organism + 1 条观察（headline+secondary）
 * + Why + Ask ECHO（+ §AG 降级行动入口）+ 至多一颗 wallpaper transient。
 * 结构：Column ├ 视觉区（61.5% 首 viewport；fontScale≥1.3 → 52%）
 *           └ 滚动区（narrative / WHY 内联证据 / Ask / Action / debug 反馈）。
 */
@Composable
internal fun EchoHomeContent(
    state: EchoSceneContentState,
    navigation: EchoSceneNavigation,
    coreActions: EchoSceneCoreActions,
    feedbackActions: EchoSceneFeedbackActions,
    sceneHeight: Dp,
    visualSurface: @Composable () -> Unit,
    actionLayer: @Composable () -> Unit,
    qualityFeedback: @Composable () -> Unit,
    onOpenAsk: () -> Unit,
) {
    val uiState = state.uiState
    val portrait = state.portrait
    // §AD：WHY 内联展开状态（1 tap 展开证据；无 sheet）
    var whyOpen by remember { mutableStateOf(false) }
    // 设计稿 4：Day-0 问候态（SEED 且无基线=第一天；随真实基线生成退出）
    val dayZero = uiState.maturity == com.yunjue.echo.mind.model.EchoMaturity.SEED && uiState.baselineDays <= 0

    Column(Modifier.fillMaxSize()) {
        // 1. 视觉区（第一眼是 ECHO；testTag: echo_scene_visual）
        Box(
            Modifier
                .fillMaxWidth()
                .height(sceneHeight * visualFractionFor(LocalConfiguration.current.fontScale))
                .testTag(ECHO_SCENE_TAG_VISUAL),
        ) {
            visualSurface()
            // narrative gradient（视觉底部渐隐，保证文字可读性）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x00040814),
                            1f to Color(0xC0040814),
                        ),
                    ),
            )
            // §AC：ambient transient（wallpaper 引导唯一 pill；AI 提示已移除）
            AmbientPromptPill(
                dismissed = state.wallpaperPromptDismissed,
                onSelect = feedbackActions.onSelectWallpaper,
                onDismiss = feedbackActions.onDismissWallpaperPrompt,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            // 设计稿 5 顶栏（overlay：字标+波形居中 / 头像+在线绿点居右；不改布局流）
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ECHO",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.width(6.dp))
                val waveformColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.size(16.dp)) {
                    drawGrowthLineIcon(
                        type = com.yunjue.echo.mind.ui.artwork.GrowthLineIconType.WAVEFORM,
                        color = waveformColor,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 16.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A2438))
                    .clickable { navigation.onGoToMe }
                    .testTag("echo_topbar_avatar"),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(16.dp)) {
                    drawPrivacyLineIcon(
                        type = com.yunjue.echo.mind.ui.artwork.PrivacyLineIconType.PERSON,
                        color = Color(0xFFB9C0D4),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34D399)),
                )
            }
        }

        // 视觉区以下（narrative + Why/Ask/Action）允许滚动（§79：fontScale 1.5 不裁剪信息）
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (dayZero) {
                // ===== 设计稿 4：Day-0 问候态（唯一真相：首日无画像，只有问候与了解卡）=====
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .testTag(ECHO_SCENE_TAG_NARRATIVE),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "你好，我是你的 ECHO",
                        fontSize = 24.sp,
                        lineHeight = 32.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "今天是我们认识的第一天",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                            .background(Color(0xE60E1426))
                            .padding(20.dp)
                            .testTag("echo_day0_card"),
                    ) {
                        Column {
                            Text(
                                "我目前了解：",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(12.dp))
                            val lines = listOf(
                                Color(0xFF38BDF8) to "还在学习你的日常习惯",
                                Color(0xFF22D3EE) to "需要更多的时间和模式",
                                Color(0xFF8F7CF0) to "建立我们的共同语言",
                                Color(0xFF34D399) to "让我们一起慢慢了解彼此",
                            )
                            lines.forEach { (dotColor, line) ->
                                androidx.compose.foundation.layout.Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 5.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            // 成长进度（真实：基线天数 / 28 天基线窗口；Day-0 = 0%，不编造设计稿的 3%）
                            val pct = ((uiState.baselineDays * 100) / 28).coerceIn(0, 100)
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF232C44)),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(pct / 100f)
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(Color(0xFF7C3AED), Color(0xFF38BDF8))
                                                )
                                            ),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "$pct%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                )
                            }
                        }
                    }
                }
            } else {
                // 2. narrative（左右 24dp；testTag: echo_scene_narrative）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .testTag(ECHO_SCENE_TAG_NARRATIVE),
                ) {
                    Text(
                        "今天 · ${rememberTodayMd()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    )
                    Spacer(Modifier.height(6.dp))
                    val narrative = resolveSceneNarrative(uiState, portrait)
                    Text(
                        narrative.headline,
                        fontSize = 22.sp,
                        lineHeight = 29.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    narrative.secondary?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        )
                    }
                    // SENSING_DISABLED / ERROR：真实动作（re-enable / retry secondary）
                    when (portrait.status) {
                        PortraitStatus.SENSING_DISABLED -> TextButton(
                            onClick = coreActions.onReEnableSensing,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(PORTRAIT_COPY_REENABLE) }
                        PortraitStatus.ERROR -> TextButton(
                            onClick = coreActions.onRetryPortrait,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(PORTRAIT_COPY_RETRY) }
                        PortraitStatus.READY -> UnlockBanner(
                            consumeUnlocked = coreActions.onConsumeUnlocked,
                            state = portrait,
                        )
                        else -> Unit
                    }
                    // 感知状态透明（非 ACTIVE 才可见；安静文案，无卡片）
                    EchoStatusOverlay(
                        sensing = uiState.sensing,
                        onGoToMe = navigation.onGoToMe,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // 3. Why（48dp touch target；§AD 1-tap 内联证据；testTag: echo_scene_why）
                TextButton(
                    onClick = { whyOpen = !whyOpen },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .heightIn(min = 48.dp)
                        .testTag(ECHO_SCENE_TAG_WHY),
                ) { Text(PORTRAIT_COPY_SECTION_WHY) }
                AnimatedVisibility(whyOpen) {
                    EchoInlineEvidence(
                        uiState = uiState,
                        portrait = portrait,
                        feedbackActions = feedbackActions,
                        onGoToJourney = navigation.onGoToJourney,
                    )
                }

                // 3.5 V3 §AK — 设计稿图5 三指标卡（情绪/能量/专注）。
                // 阶段 2：注入真实行为派生值（情绪/能量/专注）。
                Spacer(Modifier.height(12.dp))
                StatusCardsRow(
                    emotion = mapEmotion(uiState.derivedBehavior.emotion),
                    energy = mapEnergy(uiState.derivedBehavior.energy),
                    focus = mapFocus(uiState.derivedBehavior.focus),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .testTag("echo_scene_status_cards"),
                )
                Spacer(Modifier.height(14.dp))

                // 4. Ask（§AF 1-tap 全屏目的地；设计稿图5 渐变主按钮 "✦ 问 ECHO"）。
                // V3 §AJ：紫→青蓝渐变胶囊（#7C3AED → #38BDF8），保留 testTag。
                EchoGradientButton(
                    onClick = onOpenAsk,
                    text = "问 ECHO",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ECHO_SCENE_TAG_ASK),
                    contentDescription = "问 ECHO",
                )
            }

            // 5. §AG：降级行动入口（Ask 之下 quiet 展开区；空 actions 不渲染）
            EchoActionEntry(
                modifier = Modifier.padding(horizontal = 24.dp),
                actionLayer = actionLayer,
            )

            // ERA 29 §64：内部质量反馈（仅 DEBUG 构建渲染）
            qualityFeedback()
        }
    }
}

/** narrative 文案解析（状态机 → 一句 headline + 可选 secondary；全部 canonical copy）。 */
private data class SceneNarrative(val headline: String, val secondary: String?)

private fun resolveSceneNarrative(
    uiState: EchoSceneUiState,
    portrait: PortraitUiState,
): SceneNarrative = when (portrait.status) {
    // §40：LOADING 不用 spinner——quiet ECHO + canonical 语义「正在整理今天的观察…」
    PortraitStatus.LOADING -> SceneNarrative(PORTRAIT_COPY_LOADING_QUIET, null)
    PortraitStatus.WARMING_UP -> {
        val factsOnly = portrait.portrait?.summary
            ?.substringAfter("\n\n", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
        SceneNarrative(uiState.headline, factsOnly)
    }
    PortraitStatus.EARLY_BASELINE, PortraitStatus.LOW_CONFIDENCE ->
        SceneNarrative(uiState.headline, portrait.portrait?.summary)
    PortraitStatus.READY -> SceneNarrative(uiState.headline, uiState.aiLayer)
    PortraitStatus.PARTIAL_DATA -> SceneNarrative(uiState.headline, PORTRAIT_COPY_PARTIAL_BANNER)
    PortraitStatus.OFFLINE_CACHED -> SceneNarrative(
        uiState.headline,
        if (portrait.offline) PORTRAIT_COPY_OFFLINE_BANNER else null,
    )
    // §42：Sensing Disabled 不是大型 error screen——identity 保留 + 真实 re-enable
    PortraitStatus.SENSING_DISABLED -> SceneNarrative(uiState.headline, PORTRAIT_COPY_SENSING_DISABLED)
    // §43：Error —— identity 继续存在，Retry secondary，无红色全屏
    PortraitStatus.ERROR -> SceneNarrative(uiState.headline, PORTRAIT_COPY_LOAD_FAILED)
}

/** 阶段 2：行为派生状态 → 卡片数据映射。 */
private fun mapEmotion(emotion: com.yunjue.echo.mind.features.behaviorderived.DerivedEmotion): StatusCardData {
    val (label, value, level) = when (emotion) {
        com.yunjue.echo.mind.features.behaviorderived.DerivedEmotion.ACTIVE -> Triple("情绪·活跃", "活跃", StatusLevel.HIGH)
        com.yunjue.echo.mind.features.behaviorderived.DerivedEmotion.CALM -> Triple("情绪·平静", "平静", StatusLevel.MEDIUM)
        com.yunjue.echo.mind.features.behaviorderived.DerivedEmotion.FATIGUED -> Triple("情绪·疲惫", "疲惫", StatusLevel.LOW)
        else -> Triple("情绪·—", "—", StatusLevel.UNKNOWN)
    }
    return StatusCardData(displayValue = value, level = level)
}

private fun mapEnergy(energy: com.yunjue.echo.mind.features.behaviorderived.DerivedEnergy): StatusCardData {
    val (label, value, level) = when (energy) {
        com.yunjue.echo.mind.features.behaviorderived.DerivedEnergy.HIGH -> Triple("能量", "高", StatusLevel.HIGH)
        com.yunjue.echo.mind.features.behaviorderived.DerivedEnergy.MEDIUM -> Triple("能量", "中", StatusLevel.MEDIUM)
        com.yunjue.echo.mind.features.behaviorderived.DerivedEnergy.LOW -> Triple("能量", "低", StatusLevel.LOW)
        else -> Triple("能量·—", "—", StatusLevel.UNKNOWN)
    }
    return StatusCardData(displayValue = value, level = level)
}

private fun mapFocus(focus: com.yunjue.echo.mind.features.behaviorderived.DerivedFocus): StatusCardData {
    val (label, value, level) = when (focus) {
        com.yunjue.echo.mind.features.behaviorderived.DerivedFocus.FOCUSED -> Triple("专注", "较专注", StatusLevel.HIGH)
        com.yunjue.echo.mind.features.behaviorderived.DerivedFocus.DIVIDED -> Triple("专注", "较分散", StatusLevel.LOW)
        else -> Triple("专注·—", "—", StatusLevel.UNKNOWN)
    }
    return StatusCardData(displayValue = value, level = level)
}
