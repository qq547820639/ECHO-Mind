package com.yunjue.echo.mind.ui.me

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.data.EveningReminderWorker
import com.yunjue.echo.mind.data.ServiceRevocationCoordinator
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.sensing.CapabilityState
import com.yunjue.echo.mind.sensing.SensingCapability
import com.yunjue.echo.mind.sensing.capabilityState
import com.yunjue.echo.mind.ui.formatTimestamp
import com.yunjue.echo.mind.ui.notificationListenerSettingsIntent
import com.yunjue.echo.mind.ui.usageAccessSettingsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3 §28/§31 — Me → Data & Sensing：信任控制中心。
 *
 * 人话展示运行状态（ECHO 正在运行 / 各能力 ✓○），支持 pause/resume/权限修复/数据删除。
 * 不显示工程类名；「关闭」只表示用户行为（六态铁律由 SensingRuntimeStatus 保证）。
 */
@Composable
fun DataAndSensingSection(
    container: AppContainer,
    context: Context,
    scope: CoroutineScope,
    runtime: MeRuntimeUi,
    onToggleSensing: (Boolean) -> Unit,
    onToggleMic: (Boolean) -> Unit,
    requestNotifPermissionIfNeeded: () -> Unit,
) {
    // 统一「数据与感知」consent 中心（契约点 3，10 项状态）
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("数据与感知", style = MaterialTheme.typography.titleMedium)
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
                    checked = container.preferences.eveningReminderEnabled,
                    onCheckedChange = { enabled ->
                        container.preferences.eveningReminderEnabled = enabled
                        if (enabled) {
                            EveningReminderWorker.scheduleNext(context)
                        } else {
                            EveningReminderWorker.cancel(context)
                        }
                    }
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
                    if (runtime.reEnabling) {
                        Text("正在重新启用 · 等待授权同步", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(
                    checked = runtime.passiveSensingEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            requestNotifPermissionIfNeeded()
                        }
                        onToggleSensing(enabled)
                    }
                )
            }
            // 能力级权限状态（系统真实状态；恢复按钮跳系统设置）
            val capabilityStates = SensingCapability.entries.associateWith {
                capabilityState(context, it, runtime.passiveSensingEnabled)
            }
            CapabilityStatusRow(
                state = capabilityStates[SensingCapability.SENSOR] ?: CapabilityState.UNAVAILABLE,
                name = "运动传感器（加速度 / 陀螺仪）",
                description = "运动传感器未开启。开启后 ECHO 才能开始了解你的日常节奏。",
                recoveryLabel = null,
                onRecover = null
            )
            CapabilityStatusRow(
                state = capabilityStates[SensingCapability.SCREEN] ?: CapabilityState.AVAILABLE,
                name = "屏幕状态",
                description = "",
                recoveryLabel = null,
                onRecover = null
            )
            CapabilityStatusRow(
                state = capabilityStates[SensingCapability.USAGE] ?: CapabilityState.DENIED,
                name = "应用使用情况",
                description = "未开启「应用使用情况」。ECHO 仍可工作，但行为分布会更粗略。",
                recoveryLabel = "开启使用情况访问",
                onRecover = {
                    runCatching { context.startActivity(usageAccessSettingsIntent(context)) }
                }
            )
            CapabilityStatusRow(
                state = capabilityStates[SensingCapability.NOTIFICATION] ?: CapabilityState.DENIED,
                name = "通知使用权",
                description = "未开启「通知使用权」。ECHO 仍可工作，但通知使用情况不会被记录。",
                recoveryLabel = "开启通知使用权",
                onRecover = {
                    runCatching { context.startActivity(notificationListenerSettingsIntent(context)) }
                }
            )
            CapabilityStatusRow(
                state = capabilityStates[SensingCapability.MIC] ?: CapabilityState.DENIED,
                name = "麦克风",
                description = "麦克风未开启（可选）。这不影响每日画像的生成。",
                recoveryLabel = "开启麦克风（可选）",
                onRecover = { onToggleMic(true) }
            )
            // 采集/同步时间观测（事实陈述，非工程噪音——这是信任控制中心，职责在此）
            Text("最近成功采集：${formatTimestamp(runtime.lastCollectionTs)}")
            Text("最近持久化失败：${formatTimestamp(runtime.lastPersistenceFailureTs ?: 0L)}")
            if (runtime.consecutiveFailures > 0) {
                Text("连续失败：${runtime.consecutiveFailures} 次（数据仍保存在本机，会自动重试）")
            }
            Text("最近成功同步：${formatTimestamp(runtime.lastSyncTs)}")
            runtime.lastPartialSyncTs?.let {
                Text("部分数据尚未同步：最近一次部分同步 ${formatTimestamp(it)}")
            }
            Text(if (isNetworkAvailable(context)) "当前在线" else "当前离线")
            Text(runtime.syncLabel)
        }
    }

    // 同步区块（状态文案，不暴露 HTTP status）
    Text("同步", style = MaterialTheme.typography.titleMedium)
    Text(runtime.syncLabel)
    Button(onClick = { SyncWorker.enqueue(context) }, modifier = Modifier.fillMaxWidth()) { Text("立即同步") }
    HorizontalDivider()

    // 麦克风分项
    Text("麦克风", style = MaterialTheme.typography.titleMedium)
    Text("麦克风采集为可选项，默认关闭。开启后仅在本地处理，不会上传录音。")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("麦克风采集")
        Switch(
            checked = runtime.micEnabled,
            onCheckedChange = { onToggleMic(it) }
        )
    }
    HorizontalDivider()

    // 本机数据面板（把「数据只在本机」从文案变成可视事实）
    var localWindows by remember { mutableStateOf(0) }
    var localPortraits by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val userId = container.preferences.userId
        withContext(Dispatchers.IO) {
            if (userId.isNotBlank()) {
                localWindows = runCatching {
                    container.database.dao().countFeatureVectorsByUser(userId)
                }.getOrDefault(0)
                localPortraits = runCatching {
                    container.database.portraitDao().countPortraitsByUser(userId)
                }.getOrDefault(0)
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("本机保存", style = MaterialTheme.typography.titleSmall)
            Text(
                "$localWindows 个学习窗口 · $localPortraits 张画像 · " +
                    if (container.preferences.localMode) "0 次上传（本地模式，数据不出手机）"
                    else "云端同步已开启",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    // 数据权利（v3 §102：新增数据类一并纳入——本地导出/删除覆盖记忆/画像/特征）
    Text("数据权利", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = {
        scope.launch {
            if (container.preferences.localMode) {
                val json = runCatching { container.localDataRights.exportLocalData(container.preferences.userId) }
                    .getOrElse { """{"error":"export failed"}""" }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, json)
                }
                runCatching { context.startActivity(Intent.createChooser(share, "导出本地数据")) }
                runtime.onMessage("已生成本地数据导出（仅本机处理，无上传）。")
            } else {
                container.consentRepository.requestDataAction("export")
                SyncWorker.enqueue(context)
                runtime.onMessage("已创建数据导出请求。")
            }
        }
    }, modifier = Modifier.fillMaxWidth()) {
        Text(if (container.preferences.localMode) "导出本地数据" else "申请导出数据")
    }
    var showLocalDeleteConfirm by remember { mutableStateOf(false) }
    OutlinedButton(onClick = {
        if (container.preferences.localMode) {
            showLocalDeleteConfirm = true
        } else {
            scope.launch {
                container.consentRepository.requestDataAction("delete")
                SyncWorker.enqueue(context)
                runtime.onMessage("已创建删除请求；依法需保留的数据可能不立即删除。")
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("申请删除数据") }
    if (showLocalDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showLocalDeleteConfirm = false },
            title = { Text("删除本地数据") },
            text = { Text("将删除本机保存的全部派生特征、画像缓存与同意记录，且不可恢复（本地模式无云端副本）。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showLocalDeleteConfirm = false
                    scope.launch {
                        runCatching { container.localDataRights.deleteLocalData(container.preferences.userId) }
                        runtime.onMessage("本地数据已删除。")
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showLocalDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
    OutlinedButton(onClick = {
        scope.launch {
            ServiceRevocationCoordinator.revokeService(context, container.preferences, container.consentRepository)
            runtime.onMessage("已停止服务并提交撤回请求。")
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("撤回同意并停止服务") }
    runtime.message?.let { Text(it) }
    HorizontalDivider()

    Text("绑定身份：${container.preferences.institutionCode.ifBlank { "未配置（本地模式）" }}")
    Text("同步身份：${container.preferences.userId}")
    Text("AI 身份提示：ECHO Mind 是支持性工具，不是医生。")
    Text("迫近危险时优先联系紧急服务和身边可信任的人。")
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
