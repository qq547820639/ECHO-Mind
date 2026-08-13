package com.yunjue.echo.mind.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.ConsentRepository
import com.yunjue.echo.mind.data.EscalationEntity
import com.yunjue.echo.mind.data.ServiceRevocationCoordinator
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.data.mapSyncState
import com.yunjue.echo.mind.data.syncStateText
import com.yunjue.echo.mind.sensing.CapabilityState
import com.yunjue.echo.mind.sensing.SensingCapability
import com.yunjue.echo.mind.sensing.capabilityState
import kotlinx.coroutines.launch

/** 使用情况访问系统设置页（PACKAGE_USAGE_STATS 授权入口，Phase 6.1 权限恢复）。 */
internal fun usageAccessSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

/** 通知使用权系统设置页（NotificationListenerService 授权入口，Phase 6.1 权限恢复）。 */
internal fun notificationListenerSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

/**
 * 关闭被动感知总开关的**原子本地流程**（网络不可用不阻塞）。
 *
 * v0.6.1（P0-3）：委托 [ServiceRevocationCoordinator.disablePassiveSensingOnly]，
 * 保证支持页/数据权利/Onboarding 走同一条领域逻辑（不再各自实现一半）。
 * 保留本函数作为单测锚点（ConsentLifecycleTest 依赖）。
 */
internal suspend fun performPassiveSensingStop(
    context: Context,
    preferences: AppPreferences,
    consentRepository: ConsentRepository
) {
    ServiceRevocationCoordinator.disablePassiveSensingOnly(context, preferences, consentRepository)
}

@Composable
fun SupportScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by container.syncStateRepository.observePendingCount().collectAsState(initial = 0)
    var message by remember { mutableStateOf<String?>(null) }

    // ===== 数据与感知状态 =====
    val passiveSensingEnabled by container.preferences.passiveSensingEnabledFlow().collectAsState(initial = false)
    val micEnabled by container.preferences.micEnabledFlow().collectAsState(initial = false)
    // v0.6.1（P0-3 B）：本地已 ON、服务端尚未接受 granted 证据 → 显示「等待授权同步」
    var reEnabling by remember { mutableStateOf(container.preferences.consentSyncPending) }

    val lastCollectionTs = container.preferences.lastCollectionTimestamp
    val lastSyncTs = container.preferences.lastSuccessfulSyncAt
    val lastPartialSyncTs = container.preferences.lastPartialSyncAt
    val lastPersistenceFailureTs = container.preferences.lastPersistenceFailure
    val consecutiveFailures = container.preferences.consecutivePersistenceFailures
    val syncState = mapSyncState(
        pendingCount = pending,
        networkAvailable = isNetworkAvailable(context),
        deadLetterCount = container.preferences.deadLetterCount(),
        // v0.6.2（Batch A）：批次级分类结果（不再使用 lastSyncHttpCode 覆盖式映射）
        authBlocked = container.preferences.authRequired,
        consentBlocked = container.preferences.lastSyncErrorClass == "consent",
        retrying = container.preferences.lastSyncErrorClass == "retryable"
    )
    val syncLabel = syncStateText(syncState, pending)

    // ===== 人工支持（v0.6.1，P0-2 客户端闭环） =====
    val escalations by container.escalationRepository.observeEscalations().collectAsState(initial = emptyList())
    var showSupportConfirm by remember { mutableStateOf(false) }

    fun requestSupport() {
        // v0.7 本地优先架构：未开通订阅（本地模式）时请求无法送达，明示不可用（不排队假送达）
        if (container.preferences.localMode) {
            message = "尚未开通订阅，无法发送支持请求（数据仅保存在本机）。请先在上方开通订阅。"
            return
        }
        scope.launch {
            try {
                val eventId = container.escalationRepository.requestHumanSupport()
                message = "支持请求已保存，网络恢复后自动送达。"
            } catch (_: Exception) {
                message = "请求暂时未能保存，请稍后重试。"
            }
            SyncWorker.enqueue(context)
        }
    }

    // ===== 麦克风可选模块开关（T03.3） =====
    var showMicConfirm by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                container.preferences.setMicEnabled(true)
                // P1.4：granted=true 后写 voice_features consent（含证据哈希）到 outbox
                // 走 SyncWorker 上传到后端，闭环麦克风授权证据链
                try {
                    container.consentRepository.saveVoiceFeaturesConsent(true)
                } catch (_: Exception) {
                    // consent 证据落库失败不阻断 UI（outbox 尽力；后续可重试）
                }
                SyncWorker.enqueue(context)
                message = "麦克风已开启（仅端侧处理，不会上传录音）。"
            } else {
                // 权限拒绝：micEnabled 仍为 false，Switch 自动回弹
                // P1.4：权限拒绝时写 voice_features consent（granted=false）作为撤销证据
                try {
                    container.consentRepository.saveVoiceFeaturesConsent(false)
                } catch (_: Exception) {
                    // 同上
                }
                SyncWorker.enqueue(context)
                message = "未授予录音权限，麦克风开关保持关闭。"
            }
        }
    }
    if (showMicConfirm) {
        AlertDialog(
            onDismissRequest = { showMicConfirm = false },
            title = { Text("开启麦克风采集") },
            text = {
                Text(
                    "麦克风数据仅在本地端侧处理，用于提取音频特征（音量 / 语速 / 停顿 / 基频），" +
                        "不会上传录音原始数据。你可随时在系统设置中撤回录音权限。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicConfirm = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("同意并继续") }
            },
            dismissButton = {
                TextButton(onClick = { showMicConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showSupportConfirm) {
        AlertDialog(
            onDismissRequest = { showSupportConfirm = false },
            title = { Text(stringResource(R.string.support_request_confirm_title)) },
            text = { Text(stringResource(R.string.support_request_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showSupportConfirm = false; requestSupport() }) {
                    Text(stringResource(R.string.support_request_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSupportConfirm = false }) {
                    Text(stringResource(R.string.support_request_confirm_cancel))
                }
            }
        )
    }

    Page("支持与设置") {
        // 危机按钮 accessibility 文案：在组合作用域解析资源（lint：不在 semantics lambda 内查询资源）
        val crisis12356Desc = stringResource(R.string.crisis_call_12356_desc)
        val crisis110Desc = stringResource(R.string.crisis_call_110_desc)
        val crisis120Desc = stringResource(R.string.crisis_call_120_desc)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.crisis_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.crisis_not_emergency_service))
                // 危机按钮：明确的 accessibility semantics（TalkBack 可准确朗读）
                Button(
                    onClick = { context.startActivity(dialIntent("12356")) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = crisis12356Desc
                    }
                ) { Text(stringResource(R.string.crisis_call_12356)) }
                OutlinedButton(
                    onClick = { context.startActivity(dialIntent("110")) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = crisis110Desc
                    }
                ) { Text(stringResource(R.string.crisis_call_110)) }
                OutlinedButton(
                    onClick = { context.startActivity(dialIntent("120")) },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = crisis120Desc
                    }
                ) { Text(stringResource(R.string.crisis_call_120)) }
            }
        }

        // ===== 订阅开通（v0.7 本地优先：可选付费能力，非门槛） =====
        var bindCode by remember { mutableStateOf("") }
        var binding by remember { mutableStateOf(false) }
        var bindMessage by remember { mutableStateOf<String?>(null) }
        fun bindInstitution() {
            val code = bindCode.trim()
            if (code.length < 8) {
                bindMessage = "激活码格式不正确，请检查后重试。"
                return
            }
            binding = true
            bindMessage = null
            scope.launch {
                try {
                    val res = container.onboardingRepository.verifyOnboardingCode(code)
                    binding = false
                    if (res.restricted) {
                        bindMessage = "该激活码已受限，请联系客服。"
                    } else {
                        bindMessage = "订阅已开通。云端同步与专业支持现在可用。"
                        bindCode = ""
                        // 开通后：拉取租户 flag（fail-closed）并触发一次同步
                        runCatching { container.featureFlagRepository.fetchFeatureFlags() }
                        SyncWorker.enqueue(context)
                    }
                } catch (e: Exception) {
                    binding = false
                    bindMessage = when ((e as? com.yunjue.echo.mind.data.OnboardingVerifyException)?.reason) {
                        "invalid_code" -> "激活码无效，请检查后重试，或联系客服获取订阅激活码。"
                        "restricted" -> "该激活码已受限，请联系客服。"
                        else -> "暂时无法验证激活信息，请检查网络后重试。"
                    }
                }
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("开通订阅（可选）", style = MaterialTheme.typography.titleMedium)
                Text(
                    "默认本地使用，数据只保存在本机。订阅后可获得云端同步备份、长周期分析与专业支持，输入订阅激活码完成开通。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    bindCode,
                    { bindCode = it },
                    label = { Text("订阅激活码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                bindMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { bindInstitution() },
                    enabled = bindCode.isNotBlank() && !binding,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (binding) "正在验证…" else "开通订阅")
                }
            }
        }

        // ===== 专业支持（订阅功能，v0.6.1，P0-2） =====
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.support_request_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.support_request_hint))
                Button(onClick = { showSupportConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.support_request_button))
                }
                if (escalations.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.support_recent_requests), style = MaterialTheme.typography.titleSmall)
                    escalations.take(3).forEach { esc ->
                        EscalationStatusRow(esc)
                    }
                }
            }
        }

        // ===== 统一"数据与感知" consent 中心（契约点 3，10 项状态） =====
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("数据与感知", style = MaterialTheme.typography.titleMedium)
                // 1. 被动感知总开关（flag 不覆盖用户 consent）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("被动感知")
                        if (reEnabling) {
                            Text("正在重新启用 · 等待授权同步", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Switch(
                        checked = passiveSensingEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                if (enabled) {
                                    // v0.6.1（P0-3 B）：OFF→ON 统一走协调器
                                    // （先产生 granted 证据，再启动服务；同步顺序先于新特征）
                                    ServiceRevocationCoordinator.reEnablePassiveSensing(
                                        context, container.preferences, container.consentRepository, container.featureFlagRepository
                                    )
                                    reEnabling = container.preferences.consentSyncPending
                                    message = "被动感知已开启，等待授权同步…"
                                } else {
                                    performPassiveSensingStop(context, container.preferences, container.consentRepository)
                                    reEnabling = false
                                    message = "已停止"
                                }
                            }
                        }
                    )
                }
                // 2-6. Phase 6.1：能力级权限状态（规格 §2.3 / §2.5 权限恢复入口）
                // 每个能力一行：能力名 + 状态 + 降级说明 + 恢复按钮（跳对应系统设置）
                val capabilityStates = SensingCapability.entries.associateWith {
                    capabilityState(context, it, passiveSensingEnabled)
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
                    onRecover = { showMicConfirm = true }
                )
                // 7-10. 采集/同步时间、离线、待同步、持久化失败观测
                Text("最近成功采集：${formatTimestamp(lastCollectionTs)}")
                Text("最近持久化失败：${formatTimestamp(lastPersistenceFailureTs ?: 0L)}")
                if (consecutiveFailures > 0) {
                    Text("连续失败：$consecutiveFailures 次（数据仍保存在本机，会自动重试）")
                }
                // v0.6.1（P1-6）：成功/部分成功/失败语义分离
                Text("最近成功同步：${formatTimestamp(lastSyncTs)}")
                lastPartialSyncTs?.let {
                    Text("部分数据尚未同步：最近一次部分同步 ${formatTimestamp(it)}")
                }
                Text(if (isNetworkAvailable(context)) "当前在线" else "当前离线")
                Text(syncLabel)
            }
        }

        // ===== 同步区块（PRD 契约点 9：状态文案，不暴露 HTTP status） =====
        Text("同步", style = MaterialTheme.typography.titleMedium)
        Text(syncLabel)
        Button(onClick = { SyncWorker.enqueue(context) }, modifier = Modifier.fillMaxWidth()) { Text("立即同步") }
        HorizontalDivider()

        // ===== 麦克风分项 =====
        Text("麦克风", style = MaterialTheme.typography.titleMedium)
        Text("麦克风采集为可选项，默认关闭。开启后仅在本地处理，不会上传录音。")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("麦克风采集")
            Switch(
                checked = micEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        // 开启前先弹二次确认对话框，确认后再请求权限
                        showMicConfirm = true
                    } else {
                        scope.launch {
                            container.preferences.setMicEnabled(false)
                            // P1.4：用户主动关闭开关 → 写 voice_features consent（granted=false）
                            // 作为撤销证据，与系统权限撤回路径一致
                            try {
                                container.consentRepository.saveVoiceFeaturesConsent(false)
                            } catch (_: Exception) {
                                // consent 证据落库失败不阻断 UI
                            }
                            SyncWorker.enqueue(context)
                            message = "麦克风已关闭。"
                        }
                    }
                }
            )
        }
        HorizontalDivider()

        Text("数据权利", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            scope.launch { container.consentRepository.requestDataAction("export"); SyncWorker.enqueue(context); message = "已创建数据导出请求。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("申请导出数据") }
        OutlinedButton(onClick = {
            scope.launch { container.consentRepository.requestDataAction("delete"); SyncWorker.enqueue(context); message = "已创建删除请求；依法需保留的数据可能不立即删除。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("申请删除数据") }
        OutlinedButton(onClick = {
            scope.launch {
                // v0.6.1（P0-3）：撤回同意并停止服务 → 唯一领域操作（原子协调全部撤回）
                ServiceRevocationCoordinator.revokeService(context, container.preferences, container.consentRepository)
                message = "已停止服务并提交撤回请求。"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("撤回同意并停止服务") }
        message?.let { Text(it) }
        HorizontalDivider()

        Text("绑定身份：${container.preferences.institutionCode.ifBlank { "未配置（本地模式）" }}")
        Text("同步身份：${container.preferences.userId}")
        Text("AI 身份提示：ECHO Mind 是支持性工具，不是医生。")
        Text("迫近危险时优先联系紧急服务和身边可信任的人。")
    }
}

/** 人工支持请求状态行（用户侧最小状态；未 ACK 前绝不显示"人工已收到"）。 */
@Composable
private fun EscalationStatusRow(esc: EscalationEntity) {
    val statusText = when (esc.status) {
        "QUEUED" -> stringResource(R.string.esc_status_queued)
        "DELIVERED" -> stringResource(R.string.esc_status_delivered)
        "ACKNOWLEDGED" -> stringResource(R.string.esc_status_acknowledged)
        "TAKEN_OVER" -> stringResource(R.string.esc_status_taken_over)
        "CLOSED" -> stringResource(R.string.esc_status_closed)
        "FAILED" -> stringResource(R.string.esc_status_failed)
        else -> stringResource(R.string.esc_status_unknown)
    }
    Text("• $statusText", style = MaterialTheme.typography.bodyMedium)
}

/**
 * Phase 6.1 权限恢复入口行（规格 §2.3 / §2.5）：能力名 + 状态 + 降级说明 + 恢复按钮。
 * - AVAILABLE：只显示「已开启」，不显示降级说明 / 恢复按钮；
 * - UNAVAILABLE：显示「设备不支持」；
 * - DENIED / DISABLED：显示降级说明（[description]），且提供恢复按钮（[onRecover]）。
 */
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
