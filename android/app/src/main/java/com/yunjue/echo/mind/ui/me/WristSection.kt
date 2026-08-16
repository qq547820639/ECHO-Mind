package com.yunjue.echo.mind.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
 * ERA 33 — Me → "ECHO on Wrist"（§Me / Trust）。
 *
 * 一级只展示：Device / Connection / ECHO Wrist availability /
 * Wearing context permission / Sleep context permission（能力存在时）/
 * Haptics / Privacy / Disconnect。
 * Advanced：protocol version / last sync / device capability / diagnostics。
 *
 * 不暴露 SDK implementation details。
 * 每个权限只回答三件事：为什么？ECHO 会得到什么？可以关闭吗？
 */
@Composable
fun WristSection(container: AppContainer) {
    val wearable = container.wearable
    val runtimeState by wearable.runtime.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ECHO on Wrist", style = MaterialTheme.typography.titleMedium)
            Text(
                "手环不是另一个 ECHO。它是同一个 ECHO 的身体——低头看手腕，还是那个它。",
                style = MaterialTheme.typography.bodySmall,
            )

            // Device
            Text(
                "Device：${runtimeState.device?.model ?: "未连接设备"}",
                style = MaterialTheme.typography.bodySmall,
            )

            // Connection
            val connected = runtimeState.connection == WearableConnectionState.CONNECTED
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

            // ECHO Wrist availability
            val appInstalled = runtimeState.wearAppInstalled
            Text(
                "ECHO Wrist availability：${
                    when (appInstalled) {
                        true -> "手环端 ECHO 已安装"
                        false -> "手环端 ECHO 未安装"
                        null -> "尚未确认（未连接或 SDK 未集成）"
                    }
                }",
                style = MaterialTheme.typography.bodySmall,
            )

            // Wearing context permission（三件事：为什么 / 得到什么 / 可以关闭吗）
            var wearingEnabled by remember { mutableStateOf(wearable.prefs.wearingContextEnabled) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("佩戴状态", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "让我知道手环什么时候真的戴在你身上，避免把摘下手环误认为你没有活动。你可以随时关闭。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = wearingEnabled,
                    onCheckedChange = {
                        wearingEnabled = it
                        wearable.prefs.wearingContextEnabled = it
                        scope.launch { wearable.notifySurfacePrefsChanged() }
                    },
                )
            }

            // Sleep context permission（官方能力存在；真机验证前 vendor 值保持 UNKNOWN）
            var sleepEnabled by remember { mutableStateOf(wearable.prefs.sleepContextEnabled) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("睡眠状态", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "只用于区分“睡觉”和“静止”，让一天的边界更干净。它不会被用来判断你的情绪或压力。你可以随时关闭。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = sleepEnabled,
                    onCheckedChange = {
                        sleepEnabled = it
                        wearable.prefs.sleepContextEnabled = it
                        scope.launch { wearable.notifySurfacePrefsChanged() }
                    },
                )
            }

            // 运动摘要（前台加速度计 5–15s 窗口 summary；consent-first，默认关）
            var motionEnabled by remember { mutableStateOf(wearable.prefs.motionSummaryEnabled) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("运动摘要", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "只在手环上打开 ECHO 时，把当前几分钟的身体活动概括成中性节奏（比如“走动中”）。" +
                            "不传原始数据，不推断情绪。你可以随时关闭。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = motionEnabled,
                    onCheckedChange = {
                        motionEnabled = it
                        wearable.prefs.motionSummaryEnabled = it
                        scope.launch { wearable.notifySurfacePrefsChanged() }
                    },
                )
            }

            // Haptics（默认 SILENT）
            var hapticsEnabled by remember { mutableStateOf(wearable.prefs.hapticsEnabled) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("触觉", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "默认安静。开启后只在你主动轻触、开始呼吸节奏和行动完成时轻振一下；ECHO 不会因为“推测”打扰你。你可以随时关闭。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = hapticsEnabled,
                    onCheckedChange = {
                        hapticsEnabled = it
                        wearable.prefs.hapticsEnabled = it
                        scope.launch { wearable.notifySurfacePrefsChanged() }
                    },
                )
            }

            // Privacy
            Text(
                "Privacy：手环只收到渲染所需的公开投影（形态/节奏/色彩/成熟度）。" +
                    "Memory、Correction、私密上下文、密钥、位置永远不离开手机。",
                style = MaterialTheme.typography.bodySmall,
            )

            // Disconnect / Connect
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

            // Advanced
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
