package com.yunjue.echo.mind.ui.me

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.me.DataAndSensingEvent
import com.yunjue.echo.mind.me.DataAndSensingUiState
import com.yunjue.echo.mind.model.CapabilityState
import com.yunjue.echo.mind.model.SensingCapability
import com.yunjue.echo.mind.ui.formatTimestamp
import com.yunjue.echo.mind.ui.notificationListenerSettingsIntent
import com.yunjue.echo.mind.ui.usageAccessSettingsIntent

/**
 * ERA 13.1 §33 — Me → SENSING 页「数据与感知」：四分区信任控制中心（渐进披露重构）。
 *
 * A 总控与节奏 —— 页头数据通道声明（原 DATA_ORBIT 声明语义并入）/ 每晚小结 / 被动感知 / 同步
 * B 感知通道 —— 能力级权限状态（系统真实状态；恢复按钮跳系统设置）+ 麦克风分项
 * C 本机面板 —— 采集/同步时间观测 + 本机保存足迹 + 「ECHO 还不知道什么」
 * D 数据权利危险区（置底、危险红描边）—— 导出 / 删除 / 撤回同意（就地二次确认）
 *
 * 业务全部在 DataAndSensingViewModel；本节只渲染 + 系统权限请求 + 导出分享（UI 平台职责）。
 *
 * ERA 33 状态提升：Section 只做 VM 收集 + 平台权限编排（launcher / 分享 / 通知权限预检）；
 * 纯渲染在 DataAndSensingContent（state-in / event-out）。
 */
@Composable
fun DataAndSensingSection(container: AppContainer, context: Context) {
    val vm: DataAndSensingViewModel = viewModel(factory = DataAndSensingViewModel.factory(container))
    val state by vm.uiState.collectAsStateWithLifecycle()

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 结果无需处理：授权与否只影响通知可见性 */ }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onEvent(DataAndSensingEvent.MicPermissionResult(granted)) }

    // 本地导出分享在本分节收集（SENSING 页常驻组合；触发导出时本节必已组合）
    LaunchedEffect(vm) {
        vm.exportJson.collect { json ->
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, json)
            }
            runCatching { context.startActivity(Intent.createChooser(share, "导出本地数据")) }
        }
    }

    DataAndSensingContent(
        state = state,
        onEvent = vm::onEvent,
        onToggleSensing = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            vm.onEvent(DataAndSensingEvent.ToggleSensing(enabled))
        },
        onLaunchMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onRecoverUsageAccess = {
            runCatching { context.startActivity(usageAccessSettingsIntent(context)) }
        },
        onRecoverNotificationAccess = {
            runCatching { context.startActivity(notificationListenerSettingsIntent(context)) }
        },
    )
}

/**
 * ERA 33 — Data & Sensing 纯状态内容（state-in / event-out），四分区布局。
 * 系统意图（权限请求 / 设置页深链 / 分享）全部经回调注入；无 AppContainer / ViewModel 持有。
 */
@Composable
fun DataAndSensingContent(
    state: DataAndSensingUiState,
    onEvent: (DataAndSensingEvent) -> Unit,
    onToggleSensing: (Boolean) -> Unit,
    onLaunchMicPermission: () -> Unit,
    onRecoverUsageAccess: () -> Unit,
    onRecoverNotificationAccess: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // A 总控与节奏 —— 页头声明（原 DATA_ORBIT 声明语义并入 SENSING 页头）+ 总控 + 同步
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("数据与感知", style = MaterialTheme.typography.titleMedium)
                // 数据通道声明：本机数据通道全景（声明语义，不编造状态；实时状态见 B 区能力行）
                Text(
                    "本机数据通道：屏幕节律 · 通知使用权 · 应用使用情况 · 运动传感器 · 麦克风（可选）。" +
                        "数据默认只保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 每晚小结提醒
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每晚小结提醒")
                        Text("每天 21:00 提醒一次：今天的数据已记录完毕。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = state.eveningReminderEnabled,
                        onCheckedChange = { onEvent(DataAndSensingEvent.SetEveningReminder(it)) }
                    )
                }
                // 被动感知总开关（flag 不覆盖用户 consent）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("被动感知")
                        if (state.reEnabling) {
                            Text("正在重新启用 · 等待授权同步", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Switch(
                        checked = state.sensingEnabled,
                        onCheckedChange = onToggleSensing
                    )
                }
                // 同步区块（状态文案，不暴露 HTTP status）
                Text("同步", style = MaterialTheme.typography.titleMedium)
                Text(state.syncLabel)
                Button(onClick = { onEvent(DataAndSensingEvent.SyncNow) }, modifier = Modifier.fillMaxWidth()) {
                    Text("立即同步")
                }
            }
        }

        // B 感知通道 —— 能力级权限状态（系统真实状态；恢复按钮跳系统设置）+ 麦克风分项
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("感知通道", style = MaterialTheme.typography.titleMedium)
                CapabilityStatusRow(
                    state = state.capabilityStates[SensingCapability.SENSOR] ?: CapabilityState.UNAVAILABLE,
                    name = "运动传感器（加速度 / 陀螺仪）",
                    description = "运动传感器未开启。开启后 ECHO 才能开始了解你的日常节奏。",
                    recoveryLabel = null,
                    onRecover = null
                )
                CapabilityStatusRow(
                    state = state.capabilityStates[SensingCapability.SCREEN] ?: CapabilityState.AVAILABLE,
                    name = "屏幕状态",
                    description = "",
                    recoveryLabel = null,
                    onRecover = null
                )
                CapabilityStatusRow(
                    state = state.capabilityStates[SensingCapability.USAGE] ?: CapabilityState.DENIED,
                    name = "应用使用情况",
                    description = "未开启「应用使用情况」。ECHO 仍可工作，但行为分布会更粗略。",
                    recoveryLabel = "开启使用情况访问",
                    onRecover = onRecoverUsageAccess
                )
                CapabilityStatusRow(
                    state = state.capabilityStates[SensingCapability.NOTIFICATION] ?: CapabilityState.DENIED,
                    name = "通知使用权",
                    description = "未开启「通知使用权」。ECHO 仍可工作，但通知使用情况不会被记录。",
                    recoveryLabel = "开启通知使用权",
                    onRecover = onRecoverNotificationAccess
                )
                // ERA 31 R26：麦克风不再进能力行——下方「麦克风」分项已有完整控制
                // （状态 + 说明 + 开关 + 二次确认），同一屏幕两个麦克风控制是重复噪音（§10）。
                Text("麦克风", style = MaterialTheme.typography.titleMedium)
                // ERA 31 R26：合并被移除的能力行说明——不开启也不影响每日画像（控制 + 安心一句在此）
                Text(
                    "麦克风采集为可选项，默认关闭。开启后仅在本地处理，不会上传录音；不开启也不影响每日画像。",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("麦克风采集")
                    Switch(
                        checked = state.micEnabled,
                        onCheckedChange = { onEvent(DataAndSensingEvent.ToggleMic(it)) }
                    )
                }
            }
        }

        // C 本机面板 —— 时间观测（事实陈述）+ 本机保存足迹 + 「ECHO 还不知道什么」
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("最近成功采集：${formatTimestamp(state.lastCollectionTs)}")
                Text("最近持久化失败：${formatTimestamp(state.lastPersistenceFailureTs ?: 0L)}")
                if (state.consecutiveFailures > 0) {
                    Text("连续失败：${state.consecutiveFailures} 次（数据仍保存在本机，会自动重试）")
                }
                Text("最近成功同步：${formatTimestamp(state.lastSyncTs)}")
                state.lastPartialSyncTs?.let {
                    Text("部分数据尚未同步：最近一次部分同步 ${formatTimestamp(it)}")
                }
                Text(if (state.online) "当前在线" else "当前离线")
                Text("本机保存", style = MaterialTheme.typography.titleSmall)
                val fp = state.footprint
                Text(
                    "${fp.featureWindows} 个学习窗口 · ${fp.portraits} 张画像 · " +
                        "${fp.consents} 条同意记录 · ${fp.memories} 条记忆 · ${fp.journeyCanonicalDays} 天视觉快照",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (state.localMode) "0 次上传（本地模式，数据不出手机）"
                    else "云端同步已开启",
                    style = MaterialTheme.typography.bodySmall
                )
                // ERA 67（ADR-062 第 2 轮）：记忆计数按存储真值（含已忘记但保留审计的软删行）
                Text(
                    "记忆条数包含你已选择「忘记」、但依法保留待清理的行；「删除本地数据」会一并清除。",
                    style = MaterialTheme.typography.labelSmall
                )
                // ERA 68（ADR-062 第 3 轮）：「ECHO 还不知道什么」聚合行（与 What ECHO Knows 同源事实）
                if (state.doesNotKnow.isNotEmpty()) {
                    state.doesNotKnow.forEach { line ->
                        Text("· $line", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // D 数据权利危险区（置底、危险红描边；本地导出/删除覆盖记忆/画像/特征）
        OutlinedCard(
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "数据权利",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { onEvent(DataAndSensingEvent.RequestExport) },
                    modifier = Modifier.fillMaxWidth().testTag("me_entry_export"),
                ) {
                    Text(if (state.localMode) "导出本地数据" else "申请导出数据")
                }
                OutlinedButton(
                    onClick = { onEvent(DataAndSensingEvent.RequestDelete) },
                    modifier = Modifier.fillMaxWidth().testTag("me_entry_delete"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) { Text("申请删除数据") }
                OutlinedButton(
                    onClick = { onEvent(DataAndSensingEvent.RevokeConsent) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) { Text("撤回同意并停止服务") }
                state.message?.let { Text(it) }
            }
        }

        if (state.showLocalDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { onEvent(DataAndSensingEvent.DismissLocalDelete) },
                title = { Text("删除本地数据") },
                text = { Text("将删除本机保存的全部派生特征、画像缓存与同意记录，且不可恢复（本地模式无云端副本）。是否继续？") },
                confirmButton = {
                    TextButton(onClick = {
                        onEvent(DataAndSensingEvent.ConfirmLocalDelete)
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(DataAndSensingEvent.DismissLocalDelete) }) { Text("取消") }
                }
            )
        }

        if (state.showMicConfirm) {
            AlertDialog(
                onDismissRequest = { onEvent(DataAndSensingEvent.MicConfirmDismissed) },
                title = { Text("开启麦克风采集") },
                text = {
                    Text(
                        "麦克风数据仅在本地端侧处理，用于提取音频特征（音量 / 语速 / 停顿 / 基频），" +
                            "不会上传录音原始数据。你可随时在系统设置中撤回录音权限。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onEvent(DataAndSensingEvent.MicConfirmDismissed)
                        onLaunchMicPermission()
                    }) { Text("同意并继续") }
                },
                dismissButton = {
                    TextButton(onClick = { onEvent(DataAndSensingEvent.MicConfirmDismissed) }) { Text("取消") }
                }
            )
        }

        // 身份行（信任事实，置底陈述）
        Text("绑定身份：${state.institutionCode.ifBlank { "未配置（本地模式）" }}")
        Text("同步身份：${state.userId}")
        Text("AI 身份提示：ECHO Mind 是支持性工具，不是医生。")
        Text("迫近危险时优先联系紧急服务和身边可信任的人。")
    }
}

/** 权限恢复入口行：能力名 + 状态 + 降级说明 + 恢复按钮（系统真实状态）。 */
@Composable
private fun CapabilityStatusRow(
    state: CapabilityState,
    name: String,
    description: String,
    recoveryLabel: String?,
    onRecover: (() -> Unit)?
) {
    val statusText = when (state) {
        CapabilityState.AVAILABLE -> "已开启"
        CapabilityState.DENIED -> "未开启"
        CapabilityState.UNAVAILABLE -> "设备不支持"
        CapabilityState.DISABLED -> "未开启"
    }
    val degraded = state != CapabilityState.AVAILABLE
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (degraded && description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(statusText, style = MaterialTheme.typography.labelMedium)
            if (degraded && recoveryLabel != null && onRecover != null) {
                TextButton(onClick = onRecover) { Text(recoveryLabel) }
            }
        }
    }
}
