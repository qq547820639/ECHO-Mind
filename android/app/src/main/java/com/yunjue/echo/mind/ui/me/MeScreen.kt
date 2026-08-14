package com.yunjue.echo.mind.ui.me

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.data.mapSyncState
import com.yunjue.echo.mind.data.syncStateText
import com.yunjue.echo.mind.ui.Page
import com.yunjue.echo.mind.ui.dialIntent
import com.yunjue.echo.mind.ui.performPassiveSensingStop
import kotlinx.coroutines.launch

/**
 * v3 §27/§28 — MeScreen：Me 世界的根页面（含义：我的控制权）。
 *
 * Me = 控制权，不是 Support + Settings 杂物箱。子领域各独立文件：
 * Subscription / Support（人工支持）/ Data & Sensing / Presence / Intelligence / Memory。
 * 根页面只负责：共享状态提升（message/reEnabling/麦克风确认/支持确认）+ 子领域组合。
 */

/** Me 根页面与子领域共享的运行时 UI 状态（避免 10+ 参数与状态重复推导）。 */
data class MeRuntimeUi(
    val message: String?,
    val onMessage: (String?) -> Unit,
    val reEnabling: Boolean,
    val passiveSensingEnabled: Boolean,
    val micEnabled: Boolean,
    val syncLabel: String,
    val lastCollectionTs: Long,
    val lastSyncTs: Long,
    val lastPartialSyncTs: Long?,
    val lastPersistenceFailureTs: Long?,
    val consecutiveFailures: Int,
)

@Composable
fun MeScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by container.syncStateRepository.observePendingCount().collectAsStateWithLifecycle(initialValue = 0)
    var message by remember { mutableStateOf<String?>(null) }

    val passiveSensingEnabled by container.preferences.passiveSensingEnabledFlow().collectAsStateWithLifecycle(initialValue = false)
    val micEnabled by container.preferences.micEnabledFlow().collectAsStateWithLifecycle(initialValue = false)
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
        authBlocked = container.preferences.authRequired,
        consentBlocked = container.preferences.lastSyncErrorClass == "consent",
        retrying = container.preferences.lastSyncErrorClass == "retryable"
    )
    val syncLabel = syncStateText(syncState, pending)

    // ===== 人工支持 =====
    val escalations by container.escalationRepository.observeEscalations().collectAsStateWithLifecycle(initialValue = emptyList())
    var showSupportConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(escalations.size) {
        if (!container.preferences.localMode) {
            escalations.filter { !it.serverEscalationId.isNullOrBlank() }.forEach { esc ->
                runCatching { container.escalationRepository.refreshEscalationStatus(esc.serverEscalationId!!) }
            }
        }
    }

    fun requestSupport() {
        if (container.preferences.localMode) {
            message = "尚未开通订阅，无法发送支持请求（数据仅保存在本机）。请先在上方开通订阅。"
            return
        }
        if (container.preferences.subscriptionExpired) {
            message = "订阅已到期，请续订后再提交支持请求。"
            return
        }
        scope.launch {
            try {
                container.escalationRepository.requestHumanSupport()
                message = "支持请求已保存，网络恢复后自动送达。"
            } catch (_: Exception) {
                message = "请求暂时未能保存，请稍后重试。"
            }
            SyncWorker.enqueue(context)
        }
    }

    // ===== 麦克风可选模块（默认关；二次确认 + 权限证据闭环） =====
    var showMicConfirm by remember { mutableStateOf(false) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 结果无需处理：授权与否只影响通知可见性 */ }
    fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            if (granted) {
                container.preferences.setMicEnabled(true)
                try {
                    container.consentRepository.saveVoiceFeaturesConsent(true)
                } catch (_: Exception) {
                    // consent 证据落库失败不阻断 UI（outbox 尽力；后续可重试）
                }
                SyncWorker.enqueue(context)
                message = "麦克风已开启（仅端侧处理，不会上传录音）。"
            } else {
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

    // ===== 感知总开关（与感知状态同一领域路径） =====
    fun toggleSensing(enabled: Boolean) {
        scope.launch {
            if (enabled) {
                requestNotifPermissionIfNeeded()
                com.yunjue.echo.mind.data.ServiceRevocationCoordinator.reEnablePassiveSensing(
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

    fun toggleMic(checked: Boolean) {
        if (checked) {
            showMicConfirm = true
        } else {
            scope.launch {
                container.preferences.setMicEnabled(false)
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

    val runtime = MeRuntimeUi(
        message = message,
        onMessage = { message = it },
        reEnabling = reEnabling,
        passiveSensingEnabled = passiveSensingEnabled,
        micEnabled = micEnabled,
        syncLabel = syncLabel,
        lastCollectionTs = lastCollectionTs,
        lastSyncTs = lastSyncTs,
        lastPartialSyncTs = lastPartialSyncTs,
        lastPersistenceFailureTs = lastPersistenceFailureTs,
        consecutiveFailures = consecutiveFailures,
    )

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

    Page("Me · 我的控制权") {
        CrisisCard(context)
        SubscriptionSection(container, context, scope)
        SupportSection(
            container = container,
            escalations = escalations,
            onRequestSupport = { showSupportConfirm = true },
        )
        DataAndSensingSection(
            container = container,
            context = context,
            scope = scope,
            runtime = runtime,
            onToggleSensing = { toggleSensing(it) },
            onToggleMic = { toggleMic(it) },
            requestNotifPermissionIfNeeded = { requestNotifPermissionIfNeeded() },
        )
        PresenceSettingsSection(container, context)
        IntelligenceSettingsSection(container, scope)
        WhatEchoKnowsSection(container, scope)
    }
}

/** 危机入口卡（安全资源常驻可达；Me 内也保留）。 */
@Composable
private fun CrisisCard(context: android.content.Context) {
    val crisis12356Desc = stringResource(R.string.crisis_call_12356_desc)
    val crisis110Desc = stringResource(R.string.crisis_call_110_desc)
    val crisis120Desc = stringResource(R.string.crisis_call_120_desc)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.crisis_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.crisis_not_emergency_service))
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
}
