package com.yunjue.echo.mind.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.data.mapSyncState
import com.yunjue.echo.mind.data.syncStateText
import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.NarrativeFetchResult
import com.yunjue.echo.mind.model.ProfileDisplay
import com.yunjue.echo.mind.sensing.PassiveSensingService
import com.yunjue.echo.mind.sensing.SensingEventHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** T12.3：日记录入已停用提示文案（同时作为单测的不变量锚点）。 */
internal const val JOURNAL_DEPRECATION_NOTICE = "日记录入已停用，历史记录只读查看；新增内容请通过被动感知自动生成。"

@Composable
fun RecordScreen(repository: LocalRepository) {
    // T12.3：移除日记输入区（OutlinedTextField + saveJournal + 同步按钮），保留历史日记只读列表。
    val journals by repository.observeJournals().collectAsState(initial = emptyList())
    Page("记录") {
        Text(JOURNAL_DEPRECATION_NOTICE)
        HorizontalDivider()
        Text("最近记录", style = MaterialTheme.typography.titleMedium)
        if (journals.isEmpty()) {
            Text("暂无历史记录。")
        }
        journals.take(20).forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(repository.decryptJournal(row))
                    Text("本地修订 ${row.revision}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** T12.3：练习打卡已改为 Skill 驱动提示文案（同时作为单测的不变量锚点）。 */
internal const val PRACTICE_DEPRECATION_NOTICE = "练习打卡已改为能力卡片驱动，请在「能力」标签查看下发的 Skill。"

@Composable
fun PracticeScreen(repository: LocalRepository) {
    // T12.3：移除硬编码练习列表与打卡逻辑（PracticeRunner + recordPractice 调用）。
    // 练习改由 T11 下发的 Skill 卡片驱动，此处仅保留骨架提示，不再提供主动打卡入口。
    Page("练习") {
        Text(PRACTICE_DEPRECATION_NOTICE)
        Text("任何不适都可以立即停止；出现明显不适时请寻求专业帮助。")
    }
}

// ===== 趋势页（PRD v0.6 契约点 2 / 8） =====

/** 趋势页固定免责文案（契约点 2，作为单测锚点）。 */
internal const val TREND_DISCLAIMER = "这些趋势来自设备上的行为派生特征，不能知道或判断你的真实情绪。"

/** 趋势页七态（契约点 8）：loading / offline cached / fresh / partial / no data / error / permission disabled。 */
enum class TrendUiState { LOADING, OFFLINE_CACHED, FRESH, PARTIAL, NO_DATA, ERROR, PERMISSION_DISABLED }

/**
 * 七态解析纯函数（契约点 8）：
 * - API 失败 → ERROR（区别于真无数据的 NO_DATA）；
 * - 感知关闭/权限被撤 → PERMISSION_DISABLED；
 * - 缓存兜底 → OFFLINE_CACHED；有数据缺窗口 → PARTIAL；否则 FRESH。
 */
internal fun resolveTrendState(
    loading: Boolean,
    loadFailed: Boolean,
    offlineCached: Boolean,
    narratives: List<NarrativeDisplay>?,
    permissionEnabled: Boolean,
    isPartial: Boolean
): TrendUiState = when {
    loading -> TrendUiState.LOADING
    !permissionEnabled -> TrendUiState.PERMISSION_DISABLED
    loadFailed -> TrendUiState.ERROR
    narratives.isNullOrEmpty() -> TrendUiState.NO_DATA
    offlineCached -> TrendUiState.OFFLINE_CACHED
    isPartial -> TrendUiState.PARTIAL
    else -> TrendUiState.FRESH
}

/** 活动节律定性摘要（非诊断）。 */
internal fun activityRhythmSummary(narratives: List<NarrativeDisplay>): String {
    val daysWithActivity = narratives.count { n -> n.events.any { it.source == "accel" || it.source == "gyro" } }
    return if (daysWithActivity == 0) {
        "暂无足够的活动节律数据。"
    } else {
        "近 ${narratives.size} 天中 ${daysWithActivity} 天有活动信号，用于观察活动量高低的时间分布（非诊断）。"
    }
}

/** 行为模式定性摘要（非诊断）。 */
internal fun behaviorPatternSummary(narratives: List<NarrativeDisplay>): String {
    val screenDays = narratives.count { n -> n.events.any { it.source == "screen" } }
    val appDays = narratives.count { n -> n.events.any { it.source == "app_activity" } }
    return if (screenDays == 0 && appDays == 0) {
        "暂无屏幕互动数据。"
    } else {
        "近 ${narratives.size} 天中 ${screenDays} 天有屏幕事件、${appDays} 天有 App 活跃记录，仅用于观察数字互动节律（非诊断）。"
    }
}

/** 基线稳定性定性摘要（非诊断）。 */
internal fun baselineStabilitySummary(narratives: List<NarrativeDisplay>, coverage: Float): String {
    return if (narratives.isEmpty()) {
        "暂无足够数据评估基线稳定性。"
    } else if (coverage >= 0.5f) {
        "数据覆盖较稳定，可用于观察相对个人基线的变化。"
    } else {
        "数据覆盖不足，暂不足以评估基线稳定性。"
    }
}

internal fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "暂无"
    return runCatching {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("暂无")
}

@Composable
fun TrendScreen(repository: LocalRepository) {
    var narrativeResult by remember { mutableStateOf<NarrativeFetchResult?>(null) }
    var profile by remember { mutableStateOf<ProfileDisplay?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retryKey by remember { mutableStateOf(0) }

    // 被动感知 consent + 租户 flag：任一关闭 → permission_disabled 态
    val consent by repository.passiveSensingConsentFlow().collectAsState(initial = false)
    val flags by repository.featureFlagsFlow.collectAsState(initial = emptyMap())
    val permissionEnabled = consent && (flags["passive_sensing_enabled"] ?: false)

    LaunchedEffect(retryKey) {
        loading = true
        withContext(Dispatchers.IO) {
            narrativeResult = runCatching { repository.fetchNarratives(7) }.getOrNull()
            profile = runCatching { repository.fetchProfile() }.getOrNull()
        }
        loading = false
    }

    val state = resolveTrendState(
        loading = loading,
        loadFailed = narrativeResult?.loadFailed == true || profile?.loadFailed == true,
        offlineCached = narrativeResult?.fromCache == true,
        narratives = narrativeResult?.narratives,
        permissionEnabled = permissionEnabled,
        isPartial = narrativeResult?.isPartial == true
    )

    val lastSyncTs = repository.lastSyncTimestamp()
    val lastCollectionTs = repository.lastCollectionTimestamp()

    Page("趋势") {
        // 契约点 2 固定免责文案（单测锚点）
        Text(TREND_DISCLAIMER)
        HorizontalDivider()
        when (state) {
            TrendUiState.LOADING -> {
                CircularProgressIndicator()
                Text("趋势加载中…")
            }
            TrendUiState.PERMISSION_DISABLED -> Text("被动感知已关闭或权限被撤，无法获取新的趋势数据。")
            TrendUiState.ERROR -> {
                Text("趋势加载失败")
                Button(onClick = { retryKey++ }) { Text("重试") }
            }
            TrendUiState.NO_DATA -> Text("暂无趋势数据。")
            TrendUiState.OFFLINE_CACHED -> {
                Text("当前离线，以下为缓存的趋势数据。")
                TrendContent(narrativeResult, lastCollectionTs, lastSyncTs)
            }
            TrendUiState.FRESH, TrendUiState.PARTIAL -> TrendContent(narrativeResult, lastCollectionTs, lastSyncTs)
        }
    }
}

@Composable
private fun TrendContent(result: NarrativeFetchResult?, lastCollectionTs: Long, lastSyncTs: Long) {
    val narratives = result?.narratives.orEmpty()

    // 日期轴 + 数据覆盖度（7 天覆盖条）
    Text("数据覆盖度：${((result?.dataCoverage ?: 0f) * 100).toInt()}%（近 7 天）", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val days = (0L until 7L).map { LocalDate.now(ZoneOffset.UTC).minusDays(it) }
        days.reversed().forEach { day ->
            val hasData = narratives.any { it.date == day.toString() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(if (hasData) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                )
                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    // missing window 标注
    if (result?.isPartial == true) {
        Text("缺失窗口：${result.missingDates.joinToString("、").ifEmpty { "无" }}")
    }

    HorizontalDivider()
    Text("最近状态线索", style = MaterialTheme.typography.titleMedium)
    if (narratives.isEmpty()) {
        Text("暂无状态线索。")
    } else {
        narratives.takeLast(3).forEach { n ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(n.date, style = MaterialTheme.typography.labelMedium)
                    n.events.take(3).forEach { e ->
                        Text("${e.source}：${e.summary}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (n.events.isEmpty()) {
                        Text("无事件摘要", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    HorizontalDivider()
    Text("活动节律", style = MaterialTheme.typography.titleMedium)
    Text(activityRhythmSummary(narratives))
    Text("行为模式", style = MaterialTheme.typography.titleMedium)
    Text(behaviorPatternSummary(narratives))
    Text("基线稳定性", style = MaterialTheme.typography.titleMedium)
    Text(baselineStabilitySummary(narratives, result?.dataCoverage ?: 0f))

    HorizontalDivider()
    Text("最近成功采集：${formatTimestamp(lastCollectionTs)}")
    Text("最近成功同步：${formatTimestamp(lastSyncTs)}")
}

// ===== 支持与设置（PRD v0.6 契约点 3：统一"数据与感知" consent 中心） =====

/**
 * 关闭被动感知总开关的**原子本地流程**（网络不可用不阻塞）：
 * 1. 本地 consent=false（DataStore 持久化）
 * 2. 停止服务（PassiveSensingService.stop + 各 Collector 停止）
 * 3. 清空原始 buffer（SensingEventHub 内存缓冲）
 * 4. 写 revoke evidence（consent_type=passive_sensing, granted=false + SHA-256 证据哈希）入 outbox
 * 5. 入 outbox 触发 SyncWorker（网络恢复后上传撤回事件）
 * 6. 后续零新特征：服务已停止 + flag 不覆盖用户 consent
 */
internal suspend fun performPassiveSensingStop(
    context: Context,
    preferences: AppPreferences,
    repository: LocalRepository
) {
    preferences.setPassiveSensingEnabled(false)
    preferences.setMicEnabled(false)
    PassiveSensingService.stop(context)
    SensingEventHub.getInstance().clearAll()
    val userId = preferences.userId
    val evidence = MessageDigest.getInstance("SHA-256")
        .digest("passive-sensing-consent-2026.07:$userId:false".toByteArray())
        .joinToString("") { "%02x".format(it) }
    repository.saveConsent(
        granted = false,
        evidenceHash = evidence,
        consentType = "passive_sensing",
        version = "passive-sensing-consent-2026.07",
        priority = 600
    )
    SyncWorker.enqueue(context)
}

@Composable
fun SupportScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = container.repository
    val pending by repository.observePendingCount().collectAsState(initial = 0)
    var message by remember { mutableStateOf<String?>(null) }

    // ===== 数据与感知状态 =====
    val passiveSensingEnabled by container.preferences.passiveSensingEnabledFlow().collectAsState(initial = false)
    val micEnabled by container.preferences.micEnabledFlow().collectAsState(initial = false)
    val sensingActive by container.preferences.sensingActiveFlow.collectAsState(initial = false)

    val lastCollectionTs = container.preferences.lastCollectionTimestamp
    val lastSyncTs = container.preferences.lastSyncTimestamp
    val syncState = mapSyncState(
        pendingCount = pending,
        lastHttpCode = container.preferences.lastSyncHttpCode,
        networkAvailable = isNetworkAvailable(context),
        deadLetterCount = container.preferences.deadLetterCount()
    )
    val syncLabel = syncStateText(syncState, pending)

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
                runCatching { container.repository.saveVoiceFeaturesConsent(true) }
                SyncWorker.enqueue(context)
                message = "麦克风已开启（仅端侧处理，不会上传录音）。"
            } else {
                // 权限拒绝：micEnabled 仍为 false，Switch 自动回弹
                // P1.4：权限拒绝时写 voice_features consent（granted=false）作为撤销证据
                runCatching { container.repository.saveVoiceFeaturesConsent(false) }
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

    Page("支持与设置") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("危机入口", style = MaterialTheme.typography.titleMedium)
                Text("只有收到服务端确认后，应用才会显示人工已连接。")
                Button(onClick = { context.startActivity(dialIntent("12356")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 12356") }
                OutlinedButton(onClick = { context.startActivity(dialIntent("110")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 110") }
                OutlinedButton(onClick = { context.startActivity(dialIntent("120")) }, modifier = Modifier.fillMaxWidth()) { Text("拨打 120") }
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
                    Text("被动感知")
                    Switch(
                        checked = passiveSensingEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                if (enabled) {
                                    container.preferences.setPassiveSensingEnabled(true)
                                    runCatching { container.repository.fetchFeatureFlags() }
                                    PassiveSensingService.start(context)
                                    message = "被动感知已开启。"
                                } else {
                                    performPassiveSensingStop(context, container.preferences, container.repository)
                                    message = "已停止"
                                }
                            }
                        }
                    )
                }
                // 2-6. 各采集状态
                Text("传感器（加速度/陀螺仪）：${if (sensingActive) "采集中" else "已停止"}")
                Text("屏幕事件：${if (sensingActive) "采集中" else "已停止"}")
                Text("通知使用权：${if (PassiveSensingService.hasNotificationAccess(context)) "已授权" else "未授权"}")
                Text("使用情况访问：${if (PassiveSensingService.hasUsageAccess(context)) "已授权" else "未授权"}")
                Text("麦克风：${if (micEnabled) "开启（仅端侧处理）" else "关闭"}")
                // 7-10. 采集/同步时间、离线、待同步
                Text("最近成功采集：${formatTimestamp(lastCollectionTs)}")
                Text("最近成功同步：${formatTimestamp(lastSyncTs)}")
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
                            runCatching { container.repository.saveVoiceFeaturesConsent(false) }
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
            scope.launch { container.repository.requestDataAction("export"); SyncWorker.enqueue(context); message = "已创建数据导出请求。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("申请导出数据") }
        OutlinedButton(onClick = {
            scope.launch { container.repository.requestDataAction("delete"); SyncWorker.enqueue(context); message = "已创建删除请求；依法需保留的数据可能不立即删除。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("申请删除数据") }
        OutlinedButton(onClick = {
            scope.launch { container.repository.saveConsent(false, "mobile-revocation-evidence"); container.repository.requestDataAction("revoke_service"); SyncWorker.enqueue(context); message = "已创建撤回服务请求。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("撤回同意并停止服务") }
        message?.let { Text(it) }
        HorizontalDivider()

        Text("机构：${container.preferences.institutionCode.ifBlank { "未配置" }}")
        Text("同步身份：${container.preferences.userId}")
        Text("AI 身份提示：ECHO Mind 是支持性工具，不是医生。")
        Text("迫近危险时优先联系紧急服务和身边可信任的人。")
    }
}
