package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.ui.echo.components.EchoGradientButton
import com.yunjue.echo.mind.wearable.WearableConnectionState
import kotlinx.coroutines.launch

/**
 * ERA 33 — Me → WRIST「手环」L1 页内容（§D5 次级拥挤整改）。
 *
 * 首屏 = 状态头卡 + 2 个分组：
 * - 状态头卡：Device / Connection / ECHO Wrist availability 三行整合；
 * - 「偏好」：佩戴状态 / 睡眠状态 / 运动摘要 / 触觉
 *   （每个权限只答三件事：为什么？ECHO 会得到什么？可以关闭吗？）；
 * - 「连接管理」：Privacy / Disconnect / Connect + Advanced 折叠诊断（主动展开才可见）。
 *
 * 不暴露 SDK implementation details；
 * 偏好为平台层直写（wearable.prefs + notifySurfacePrefsChanged），不动业务 state。
 */
@Composable
fun WristSection(container: AppContainer) {
    val wearable = container.wearable
    val runtimeState by wearable.runtime.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(false) }
    val connected = runtimeState.connection == WearableConnectionState.CONNECTED

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ===== 设计稿 17：表盘定制（样机预览 + 四样式 + 复杂信息 + 同步 CTA）=====
        var faceStyle by remember { mutableIntStateOf(wearable.prefs.watchFaceStyle) }
        var complications by remember { mutableStateOf(wearable.prefs.watchFaceComplications) }
        var syncHint by remember { mutableStateOf<String?>(null) }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("WRIST · 手环表盘", style = MaterialTheme.typography.titleMedium)
                Text(
                    "抬腕可见，随时感知 ECHO 的状态。",
                    style = MaterialTheme.typography.bodySmall,
                )
                // 手表样机预览（真实 identitySeed 生命体 + 真实时钟 + 真实连接态；不编造心率/电量）
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WatchFacePreview(
                        styleIndex = faceStyle,
                        showComplications = complications,
                        connected = connected,
                        identitySeed = container.preferences.identitySeed,
                    )
                }
                // 表盘样式四缩略图（确定性风格变体；选中 = 渐变描边 + ✓）
                Text("表盘样式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { index ->
                        val selected = faceStyle == index
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                .background(Color(0xFF0E1426))
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            2.dp,
                                            Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFF38BDF8))),
                                            androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                        )
                                    } else {
                                        Modifier.border(1.dp, Color(0xFF2A3550), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                    },
                                )
                                .clickable {
                                    faceStyle = index
                                    wearable.prefs.watchFaceStyle = index
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(40.dp)) {
                                drawWatchFaceOrb(styleIndex = index)
                            }
                            if (selected) {
                                Text(
                                    "✓",
                                    color = Color(0xFF38BDF8),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.align(Alignment.TopEnd),
                                )
                            }
                        }
                    }
                }
                // 复杂信息开关（真实持久化；影响预览与腕上信息行）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("复杂信息", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "显示更多信息（时钟与状态行）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    Switch(
                        checked = complications,
                        onCheckedChange = {
                            complications = it
                            wearable.prefs.watchFaceComplications = it
                            scope.launch { wearable.notifySurfacePrefsChanged() }
                        },
                    )
                }
                // 真实连接状态行（不编造电量/固件）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (connected) "✓" else "○",
                        color = if (connected) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (connected) {
                            "已连接 ${runtimeState.device?.model ?: "ECHO 手环"}"
                        } else {
                            "未连接手环（连接后自动同步）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 同步 CTA（真实推送 presence/prefs；未连接时禁用 + 提示）
                EchoGradientButton(
                    onClick = {
                        scope.launch {
                            wearable.notifySurfacePrefsChanged()
                            syncHint = "已同步——表盘将自动生效"
                        }
                    },
                    text = "同步到手环",
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = "同步到手环",
                )
                syncHint?.let {
                    Text(
                        "✦ $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                if (!connected) {
                    Text(
                        "✦ 连接手环后即可同步表盘",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }

        // 状态头卡（原 Device / Connection / availability 三行平铺整合）
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ECHO on Wrist", style = MaterialTheme.typography.titleMedium)
                Text(
                    "手环不是另一个 ECHO。它是同一个 ECHO 的身体——低头看手腕，还是那个它。",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text(
                    "Device：${runtimeState.device?.model ?: "未连接设备"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Connection：${
                        when (runtimeState.connection) {
                            WearableConnectionState.CONNECTED -> "已连接"
                            WearableConnectionState.CONNECTING -> "连接中"
                            WearableConnectionState.DISCONNECTED -> "未连接"
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "ECHO Wrist availability：${
                        when (runtimeState.wearAppInstalled) {
                            true -> "手环端 ECHO 已安装"
                            false -> "手环端 ECHO 未安装"
                            null -> "尚未确认（未连接或 SDK 未集成）"
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 偏好
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("偏好", style = MaterialTheme.typography.titleMedium)

                // Wearing context permission
                var wearingEnabled by remember { mutableStateOf(wearable.prefs.wearingContextEnabled) }
                WristPreferenceRow(
                    title = "佩戴状态",
                    description = "让我知道手环什么时候真的戴在你身上，避免把摘下手环误认为你没有活动。你可以随时关闭。",
                    checked = wearingEnabled,
                    onCheckedChange = {
                        wearingEnabled = it
                        wearable.prefs.wearingContextEnabled = it
                        scope.launch { wearable.notifySurfacePrefsChanged() }
                    },
                )

                // Sleep context permission（官方能力存在；真机验证前 vendor 值保持 UNKNOWN）
                var sleepEnabled by remember { mutableStateOf(wearable.prefs.sleepContextEnabled) }
                WristPreferenceRow(
                    title = "睡眠状态",
                    description = "只用于区分“睡觉”和“静止”，让一天的边界更干净。它不会被用来判断你的情绪或压力。你可以随时关闭。",
                    checked = sleepEnabled,
                    onCheckedChange = {
                        sleepEnabled = it
                        wearable.prefs.sleepContextEnabled = it
                        scope.launch { wearable.notifySurfacePrefsChanged() }
                    },
                )

                // 运动摘要（前台加速度计 5–15s 窗口 summary；consent-first，默认关）
                var motionEnabled by remember { mutableStateOf(wearable.prefs.motionSummaryEnabled) }
                WristPreferenceRow(
                    title = "运动摘要",
                    description = "只在手环上打开 ECHO 时，把当前几分钟的身体活动概括成中性节奏（比如“走动中”）。" +
                        "不传原始数据，不推断情绪。你可以随时关闭。",
                    checked = motionEnabled,
                    onCheckedChange = {
                        motionEnabled = it
                        wearable.prefs.motionSummaryEnabled = it
                        scope.launch { wearable.notifySurfacePrefsChanged() }
                    },
                )

                // Haptics（默认 SILENT）
                var hapticsEnabled by remember { mutableStateOf(wearable.prefs.hapticsEnabled) }
                WristPreferenceRow(
                    title = "触觉",
                    description = "默认安静。开启后只在你主动轻触、开始呼吸节奏和行动完成时轻振一下；ECHO 不会因为“推测”打扰你。你可以随时关闭。",
                    checked = hapticsEnabled,
                    onCheckedChange = {
                        hapticsEnabled = it
                        wearable.prefs.hapticsEnabled = it
                        scope.launch { wearable.notifySurfacePrefsChanged() }
                    },
                )
            }
        }

        // 连接管理（Privacy + Connect/Disconnect + Advanced 折叠诊断）
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("连接管理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Privacy：手环只收到渲染所需的公开投影（形态/节奏/色彩/成熟度）。" +
                        "Memory、Correction、私密上下文、密钥、位置永远不离开手机。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    if (connected) {
                        TextButton(onClick = { scope.launch { wearable.disconnect() } }) {
                            Text("Disconnect")
                        }
                    } else {
                        TextButton(onClick = { scope.launch { wearable.connect() } }) {
                            Text("Connect")
                        }
                    }
                }

                // Advanced（诊断细节主动展开才可见）
                HorizontalDivider()
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "收起 Advanced" else "Advanced")
                }
                if (showAdvanced) {
                    Text(
                        "protocol version：v${com.yunjue.echo.mind.wearable.WEAR_SCHEMA_CURRENT}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "last sync：${runtimeState.lastPresencePushedAt ?: "从未推送"}" +
                            "（ACK：${runtimeState.lastAckAt ?: "无"}）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "长跑仪表：入站 ${runtimeState.inboundMessageCount} · 推送 ${runtimeState.outboundPushCount}" +
                            " · 断连 ${runtimeState.disconnectCount}（进程内计数）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "presence revision：${runtimeState.presenceRevision}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "device capability：${
                            (runtimeState.device?.capabilities ?: emptyList())
                                .joinToString("，") { "${it.id.name}=${it.status.name}" }
                                .ifEmpty { "未连接设备（无能力画像）" }
                        }",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "diagnostics：${com.yunjue.echo.mind.wearable.XiaomiWearVendorBoundary.INTEGRATION_NOTE}" +
                            " 腕上观察缓存：${wearable.observationLog.size} 条（内存，进程死亡即清）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    runtimeState.lastTransportError?.let {
                        Text("transport：$it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** 偏好行：标题 + 说明（为什么/得到什么/可以关闭吗）+ 开关。 */
@Composable
private fun WristPreferenceRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 设计稿 17 — 手表样机预览：表带 + 表体 + 真实 identitySeed 生命体 + 真实时钟 +
 * 连接态状态行（不编造心率/电量/固件）。样式经 [styleIndex] 影响光环/环密度。
 */
@Composable
private fun WatchFacePreview(
    styleIndex: Int,
    showComplications: Boolean,
    connected: Boolean,
    identitySeed: Long,
) {
    val seedPresence = remember(identitySeed) {
        com.yunjue.echo.mind.presence.dayZeroSeedPresence(identitySeed = identitySeed)
    }
    val genome = remember(seedPresence) {
        com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
            com.yunjue.echo.mind.presence.EchoVisualMapper.map(seedPresence, 12f, reduceMotion = false),
            seedPresence.identityGenome,
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 上表带
        Box(
            Modifier
                .width(96.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(0xFF151A28)),
        )
        // 表体 + 屏幕
        Box(
            Modifier
                .size(width = 208.dp, height = 248.dp)
                .clip(RoundedCornerShape(48.dp))
                .background(Color(0xFF0B0F1C))
                .border(3.dp, Color(0xFF2A3550), RoundedCornerShape(48.dp))
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(40.dp))
                    .background(Color(0xFF05070F)),
                contentAlignment = Alignment.Center,
            ) {
                com.yunjue.echo.mind.presencevisual.EchoOrganism(
                    genome = genome,
                    modifier = Modifier.fillMaxSize(),
                    maturityName = seedPresence.maturity.name,
                    options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                        maturityName = seedPresence.maturity.name,
                        haloScale = listOf(1f, 1.25f, 0.85f, 1.1f)[styleIndex.coerceIn(0, 3)],
                        detailScale = listOf(1f, 0.85f, 1.15f, 1f)[styleIndex.coerceIn(0, 3)],
                        ringAlphaScale = listOf(1f, 1.3f, 1f, 0.7f)[styleIndex.coerceIn(0, 3)],
                    ),
                )
                if (showComplications) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFE8ECF5),
                        )
                        Text(
                            if (connected) "正在陪伴你" else "等待连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) Color(0xFF8F7CF0) else Color(0xFF6E6E8C),
                        )
                    }
                }
            }
        }
        // 下表带
        Box(
            Modifier
                .width(96.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(Color(0xFF151A28)),
        )
    }
}

/** 表盘样式缩略球（确定性 Canvas 风格变体：色调 + 环密度 + 光晕差异）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWatchFaceOrb(styleIndex: Int) {
    val w = size.width
    val h = size.height
    val c = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f)
    val palettes = listOf(
        listOf(Color(0xFF8F7CF0), Color(0xFF38BDF8)),
        listOf(Color(0xFF38BDF8), Color(0xFF2563EB)),
        listOf(Color(0xFFE879F9), Color(0xFF8F7CF0)),
        listOf(Color(0xFF34D399), Color(0xFF38BDF8)),
    )
    val palette = palettes[styleIndex.coerceIn(0, 3)]
    drawCircle(
        brush = Brush.radialGradient(listOf(palette[0], palette[1], Color(0xFF05070F)), center = c, radius = w * 0.5f),
        radius = w * 0.30f,
        center = c,
    )
    drawCircle(
        color = palette[1].copy(alpha = 0.5f),
        radius = w * (0.38f + 0.04f * styleIndex),
        center = c,
        style = Stroke(width = 1.4f),
    )
    if (styleIndex % 2 == 1) {
        drawCircle(
            color = palette[0].copy(alpha = 0.3f),
            radius = w * 0.47f,
            center = c,
            style = Stroke(width = 1f),
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            listOf(palette[0].copy(alpha = 0.25f), Color.Transparent),
            center = c,
            radius = w * 0.5f,
        ),
        radius = w * 0.5f,
        center = c,
    )
}
