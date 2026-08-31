package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunjue.echo.mind.AppContainer
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
