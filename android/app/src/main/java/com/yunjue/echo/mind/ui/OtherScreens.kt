package com.yunjue.echo.mind.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.yunjue.echo.mind.data.EscalationEntity
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.data.ServiceRevocationCoordinator
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.data.isNetworkAvailable
import com.yunjue.echo.mind.data.mapSyncState
import com.yunjue.echo.mind.data.syncStateText
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.ProfileDisplay
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.dimensionTrendSymbol
import com.yunjue.echo.mind.model.portraitStabilitySummary
import com.yunjue.echo.mind.sensing.PassiveSensingService
import com.yunjue.echo.mind.sensing.SensingEventHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** T12.3：日记录入已停用提示文案（同时作为单测的不变量锚点）。 */
internal const val JOURNAL_DEPRECATION_NOTICE = "日记录入已停用，历史记录只读查看；新增内容请通过被动感知自动生成。"

/** T12.6：量表录入已停用提示文案（QuestionnaireScreen 已删除，常量保留供单测锚点）。 */
internal const val QUESTIONNAIRE_DEPRECATION_NOTICE = "量表录入已停用，筛查提示改由能力卡片驱动。"

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

/** T12.3：练习打卡已停用提示文案（同时作为单测的不变量锚点）。 */
internal const val PRACTICE_DEPRECATION_NOTICE = "练习打卡已停用，练习改由「能力」标签下发的 Skill 卡片驱动。"

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
 * 趋势页 NO_DATA 细分原因（T02 七态细化）：
 * 区分「新用户无窗口 / 权限未授权 / 用户关闭感知 / 系统限制后台 / 部分 source 缺失 /
 * 本地持久化失败 / 等待上传」，避免统一显示"暂无数据"。
 *
 * v0.6.1（P1-8）：每个枚举必须可真实到达——CLOSED/PERMISSION 由真实 consent/权限
 * 输入推导；SERVER_UNAVAILABLE 不可达（网络失败归 ERROR 态）已删除。
 */
enum class TrendNoDataReason {
    NEW_USER,
    PERMISSION,
    CLOSED,
    SYSTEM_BACKGROUND,
    SOURCE_GAPS,
    PERSISTENCE_FAILURE,
    AWAITING_UPLOAD,
    UNKNOWN
}

/**
 * 七态解析纯函数（契约点 8；Milestone G 后 items 为画像/叙事数据集合，语义不变）：
 * - API 失败 → ERROR（区别于真无数据的 NO_DATA）；
 * - 感知关闭/权限被撤 → PERMISSION_DISABLED；
 * - 缓存兜底 → OFFLINE_CACHED；有数据缺窗口 → PARTIAL；否则 FRESH。
 */
internal fun resolveTrendState(
    loading: Boolean,
    loadFailed: Boolean,
    offlineCached: Boolean,
    items: Collection<*>?,
    permissionEnabled: Boolean,
    isPartial: Boolean
): TrendUiState = when {
    loading -> TrendUiState.LOADING
    !permissionEnabled -> TrendUiState.PERMISSION_DISABLED
    loadFailed -> TrendUiState.ERROR
    items.isNullOrEmpty() -> TrendUiState.NO_DATA
    offlineCached -> TrendUiState.OFFLINE_CACHED
    isPartial -> TrendUiState.PARTIAL
    else -> TrendUiState.FRESH
}

/**
 * NO_DATA 原因解析纯函数（T02 七态细化；v0.6.1 全部输入可真实到达）：
 * - consent 关闭（consentEnabled=false）→ CLOSED
 * - 权限未授权（permissionGranted=false）→ PERMISSION
 * - observationDays == 0 → 新用户尚无窗口
 * - 系统限制后台（systemBackgroundRestricted）→ SYSTEM_BACKGROUND
 * - 本地持久化失败（persistenceFailedRecently）→ PERSISTENCE_FAILURE
 * - 有待上传特征（pendingUploadCount > 0）→ AWAITING_UPLOAD
 * - 部分核心 source 缺失（missingSources 非空）→ SOURCE_GAPS
 * - 其余 → UNKNOWN
 */
internal fun resolveTrendNoDataReason(
    observationDays: Int,
    systemBackgroundRestricted: Boolean,
    persistenceFailedRecently: Boolean,
    pendingUploadCount: Int,
    missingSources: List<String>,
    consentEnabled: Boolean = true,
    permissionGranted: Boolean = true
): TrendNoDataReason = when {
    !consentEnabled -> TrendNoDataReason.CLOSED
    !permissionGranted -> TrendNoDataReason.PERMISSION
    observationDays <= 0 -> TrendNoDataReason.NEW_USER
    systemBackgroundRestricted -> TrendNoDataReason.SYSTEM_BACKGROUND
    persistenceFailedRecently -> TrendNoDataReason.PERSISTENCE_FAILURE
    pendingUploadCount > 0 -> TrendNoDataReason.AWAITING_UPLOAD
    missingSources.isNotEmpty() -> TrendNoDataReason.SOURCE_GAPS
    else -> TrendNoDataReason.UNKNOWN
}

/** NO_DATA 原因 → 用户可读文案（不暴露工程术语/HTTP 码）。 */
internal fun trendNoDataReasonText(reason: TrendNoDataReason): String = when (reason) {
    TrendNoDataReason.NEW_USER -> "刚开始使用，还没有生成足够的感知窗口。"
    TrendNoDataReason.PERMISSION -> "感知权限未开启，开启后会自动开始记录。"
    TrendNoDataReason.CLOSED -> "你已关闭被动感知，可随时在「支持」页重新开启。"
    TrendNoDataReason.SYSTEM_BACKGROUND -> "系统限制了后台活动，近期没有新的感知数据。"
    TrendNoDataReason.SOURCE_GAPS -> "部分信号源暂未覆盖，数据仍在收集中。"
    TrendNoDataReason.PERSISTENCE_FAILURE -> "本地保存暂时遇到问题，数据会在恢复后自动补录。"
    TrendNoDataReason.AWAITING_UPLOAD -> "数据已保存在本机，正在等待网络恢复后上传。"
    TrendNoDataReason.UNKNOWN -> "暂无趋势数据。"
}

/**
 * 内部 feature/source code → 人类可读名称（v0.6.1，P1-9）。
 * 普通用户界面只显示可读名；内部 source code 仅出现在 debug/developer 界面。
 */
internal val SOURCE_DISPLAY_NAMES: Map<String, String> = mapOf(
    "accel" to "加速度传感",
    "gyro" to "陀螺仪传感",
    "screen" to "屏幕互动",
    "notification" to "通知使用",
    "app_activity" to "App 活跃",
    "mic_opt" to "麦克风特征",
    "health" to "健康数据"
)

/** source code → 可读名（未知 code 返回兜底文案，不泄露内部码）。 */
internal fun sourceDisplayName(code: String): String = SOURCE_DISPLAY_NAMES[code] ?: "设备活动"

/** 期望覆盖的核心信号源集合（与后端 SOURCES_PRESENT_VALUES / gap_finder 对齐）。 */
internal val EXPECTED_CORE_SOURCES: Set<String> = setOf(
    "accel", "gyro", "screen", "notification", "app_activity"
)

/** 打开本应用系统设置页（修复权限用 deep link，Settings.ACTION_APPLICATION_DETAILS_SETTINGS）。 */
internal fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/**
 * v0.6.2（A4）：电池优化设置页（无需特殊权限，直接打开系统"忽略电池优化"列表）。
 * 用于 SYSTEM_BACKGROUND NO_DATA 态的"前往系统设置"CTA。
 */
internal fun batteryOptimizationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

internal fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "暂无"
    return runCatching {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("暂无")
}

/** 本地持久化失败判定回看窗口（24h）：窗口内发生过失败则视为"持久化失败"原因。 */
internal const val PERSISTENCE_FAILURE_LOOKBACK_MS = 24 * 60 * 60 * 1000L

/** collector heartbeat 新鲜度阈值（3 天）：超过则视为后台受限/采集停滞。 */
internal const val COLLECTOR_HEARTBEAT_STALE_MS = 3 * 24 * 60 * 60 * 1000L

@Composable
fun TrendScreen(repository: LocalRepository, onGoToSupport: () -> Unit = {}) {
    val context = LocalContext.current
    // 7 日 / 28 日窗口（Milestone G：Portrait Timeline）
    var windowDays by remember { mutableStateOf(7) }
    var profile by remember { mutableStateOf<ProfileDisplay?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    val timeline by repository.observePortraits(windowDays).collectAsState()

    // 被动感知 consent + 租户 flag：任一关闭 → permission_disabled 态
    val consent by repository.passiveSensingConsentFlow().collectAsState(initial = false)
    val flags by repository.featureFlagsFlow.collectAsState(initial = emptyMap())
    val permissionEnabled = consent && (flags["passive_sensing_enabled"] ?: false)

    LaunchedEffect(retryKey, windowDays) {
        repository.refreshPortraits(windowDays)
        // profile 仅用于 NO_DATA 细分原因（observation_days / sources_present_union）
        profile = withContext(Dispatchers.IO) {
            runCatching { repository.fetchProfile() }.getOrNull()
        }
    }

    val state = resolveTrendState(
        loading = timeline.loading,
        loadFailed = timeline.loadFailed,
        offlineCached = timeline.fromCache,
        items = if (timeline.portraits.isEmpty()) null else timeline.portraits,
        permissionEnabled = permissionEnabled,
        isPartial = timeline.isPartial
    )

    // NO_DATA 细分原因（T02 七态细化；v0.6.1 全部输入真实接入）：
    // - 系统后台限制：电池优化被豁免失败（isIgnoringBatteryOptimizations=false）
    //   → 系统可限制后台；或 collector heartbeat 超过 3 天无新采集
    // - source gaps：profile.sources_present_union 与期望核心源对比（服务端已下推）
    val lastSyncTs = repository.lastSyncTimestamp()
    val lastCollectionTs = repository.lastCollectionTimestamp()
    // 电池优化被豁免失败（isIgnoringBatteryOptimizations=false）→ 系统可能限制后台活动
    val batteryRestricted = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == false
    }.getOrDefault(true)
    val heartbeatStale = lastCollectionTs > 0L &&
        System.currentTimeMillis() - lastCollectionTs > COLLECTOR_HEARTBEAT_STALE_MS
    val systemBackgroundRestricted = batteryRestricted || heartbeatStale
    val missingSources = EXPECTED_CORE_SOURCES.filterNot { it in (profile?.sourcesPresentUnion.orEmpty()) }
    val noDataReason = resolveTrendNoDataReason(
        observationDays = profile?.observationDays ?: 0,
        systemBackgroundRestricted = systemBackgroundRestricted,
        persistenceFailedRecently = repository.lastPersistenceFailure()?.let {
            System.currentTimeMillis() - it < PERSISTENCE_FAILURE_LOOKBACK_MS
        } ?: false,
        pendingUploadCount = repository.pendingUploadCount(),
        missingSources = missingSources,
        consentEnabled = consent,
        permissionGranted = permissionEnabled
    )

    Page("趋势") {
        // 契约点 2 固定免责文案（单测锚点）
        Text(TREND_DISCLAIMER)
        HorizontalDivider()

        // 7 日 / 28 日窗口切换
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = windowDays == 7,
                onClick = { windowDays = 7 },
                label = { Text("近 7 天") }
            )
            FilterChip(
                selected = windowDays == 28,
                onClick = { windowDays = 28 },
                label = { Text("近 28 天") }
            )
        }

        when (state) {
            TrendUiState.LOADING -> {
                CircularProgressIndicator()
                Text("趋势加载中…")
            }
            TrendUiState.PERMISSION_DISABLED -> {
                Text("被动感知已关闭或权限被撤，无法获取新的趋势数据。")
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(appSettingsIntent(context)) }
                }) { Text("前往系统设置修复权限") }
            }
            TrendUiState.ERROR -> {
                Text("趋势加载失败")
                Button(onClick = { retryKey++ }) { Text("重试") }
            }
            TrendUiState.NO_DATA -> {
                Text(trendNoDataReasonText(noDataReason))
                // v0.6.2（A4）：NO_DATA 态补充展示最近采集 / 最近成功同步时间；
                // 文案只陈述事实，不焦虑不诊断（trendNoDataReasonText 语义保持）
                Text("最近成功采集：${formatTimestamp(lastCollectionTs)}")
                Text("最近成功同步：${formatTimestamp(lastSyncTs)}")
                // 仅「可一键修复」的原因提供 CTA：
                // - CLOSED：支持页可重新开启
                // - SYSTEM_BACKGROUND：系统设置可调整电池/后台限制
                // NEW_USER / SOURCE_GAPS / AWAITING_UPLOAD / PERSISTENCE_FAILURE /
                // PERMISSION / UNKNOWN 不可一键修复 → 无 CTA 只保留文案
                when (noDataReason) {
                    TrendNoDataReason.CLOSED -> OutlinedButton(onClick = onGoToSupport) {
                        Text("前往支持页重新开启")
                    }
                    TrendNoDataReason.SYSTEM_BACKGROUND -> OutlinedButton(onClick = {
                        runCatching { context.startActivity(batteryOptimizationSettingsIntent(context)) }
                    }) {
                        Text("前往系统设置")
                    }
                    else -> Unit
                }
            }
            TrendUiState.OFFLINE_CACHED -> {
                Text("当前离线，以下为缓存的趋势数据。")
                PortraitTimelineContent(timeline, lastCollectionTs, lastSyncTs)
            }
            TrendUiState.FRESH, TrendUiState.PARTIAL -> PortraitTimelineContent(timeline, lastCollectionTs, lastSyncTs)
        }
    }
}

/**
 * Portrait Timeline 内容（Milestone G）：
 * - 7 日视图：各维度（节律/移动/屏幕）相对趋势符号矩阵（↑↓→~，不做精确数字强调）
 * - 28 日视图：各维度概览 + 确定性综述（最稳定 / 变化较明显）
 * - 不做心理状态解释（TREND_DISCLAIMER 语义保持）
 */
@Composable
private fun PortraitTimelineContent(timeline: PortraitTimelineUiState, lastCollectionTs: Long, lastSyncTs: Long) {
    val portraits = timeline.portraits

    // 数据覆盖度（近 N 天窗口）
    Text("数据覆盖度：${coveragePercent(portraits, timeline.days)}%（近 ${timeline.days} 天）", style = MaterialTheme.typography.titleMedium)
    // 日期覆盖条（仅最近 7 天窗口展示单日格子；28 天不逐日铺开）
    if (timeline.days <= 7) {
        val days = (0 until 7).map { LocalDate.now().minusDays((7 - 1 - it).toLong()) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                val hasData = portraits.any { it.date == day.toString() }
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
    }

    // missing window 标注
    if (timeline.isPartial) {
        Text("缺失窗口：${timeline.missingDates.joinToString("、").ifEmpty { "无" }}")
    }

    HorizontalDivider()
    if (timeline.days <= 7) {
        SevenDayTrendMatrix(portraits, timeline.days)
    } else {
        TwentyEightDayOverview(portraits)
    }

    HorizontalDivider()
    Text("最近成功采集：${formatTimestamp(lastCollectionTs)}")
    Text("最近成功同步：${formatTimestamp(lastSyncTs)}")
}

/** 数据覆盖度（0..100 整数百分比）：窗口内有画像的天数 / 窗口天数。 */
internal fun coveragePercent(portraits: List<DailyPortraitDto>, days: Int): Int {
    if (days <= 0) return 0
    val today = LocalDate.now()
    val window = (0 until days).map { today.minusDays((days - 1 - it).toLong()).toString() }.toSet()
    val present = portraits.map { it.date }.filter { it in window }.toSet()
    return (present.size * 100 / days).coerceIn(0, 100)
}

/**
 * 7 日视图：维度（节律/移动/屏幕互动）× 最近 [days] 天相对趋势符号矩阵。
 * 符号来自 [dimensionTrendSymbol]（↑ 偏早/增多、↓ 偏晚/减少、→ 接近、~ 不规律/波动、– 缺失）。
 */
@Composable
private fun SevenDayTrendMatrix(portraits: List<DailyPortraitDto>, days: Int) {
    val dates = (0 until days).map { LocalDate.now().minusDays((days - 1 - it).toLong()) }
    val byDate = portraits.associateBy { it.date }
    Text("相对趋势（↑ 偏早/增多 · ↓ 偏晚/减少 · → 接近，仅观察不解释）", style = MaterialTheme.typography.bodySmall)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 表头：维度 + 日期
        Row {
            Box(Modifier.weight(1.6f)) { Text("维度", style = MaterialTheme.typography.labelSmall) }
            dates.forEach { day ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        PORTRAIT_TREND_DIMENSIONS.forEach { dim ->
            Row {
                Box(Modifier.weight(1.6f)) {
                    Text(dimensionDisplayName(dim), style = MaterialTheme.typography.bodySmall)
                }
                dates.forEach { day ->
                    val value = byDate[day.toString()]?.dimensions?.get(dim)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(dimensionTrendSymbol(value), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * 28 日视图：各维度概览（接近/偏早增多/偏晚减少天数）+ 确定性综述
 * （最稳定 = SIMILAR 比例最高；变化较明显 = 非 SIMILAR 最多）。
 */
@Composable
private fun TwentyEightDayOverview(portraits: List<DailyPortraitDto>) {
    Text("近 28 天各维度概览（仅观察，不解释）", style = MaterialTheme.typography.titleMedium)
    PORTRAIT_TREND_DIMENSIONS.forEach { dim ->
        val values = portraits.mapNotNull { it.dimensions[dim] }
        if (values.isEmpty()) {
            Text("${dimensionDisplayName(dim)}：暂无数据", style = MaterialTheme.typography.bodySmall)
        } else {
            val similar = values.count { it == "SIMILAR" }
            val up = values.count { dimensionTrendSymbol(it) == "↑" }
            val down = values.count { dimensionTrendSymbol(it) == "↓" }
            Text(
                "${dimensionDisplayName(dim)}：$similar 天接近 · $up 天偏早/增多 · $down 天偏晚/减少",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    Text(portraitStabilitySummary(portraits), style = MaterialTheme.typography.titleMedium)
}

// ===== 支持与设置（PRD v0.6 契约点 3：统一"数据与感知" consent 中心） =====

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
    repository: LocalRepository
) {
    ServiceRevocationCoordinator.disablePassiveSensingOnly(context, preferences, repository)
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
    val escalations by repository.observeEscalations().collectAsState(initial = emptyList())
    var showSupportConfirm by remember { mutableStateOf(false) }

    fun requestSupport() {
        scope.launch {
            try {
                val eventId = repository.requestHumanSupport()
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
                    container.repository.saveVoiceFeaturesConsent(true)
                } catch (_: Exception) {
                    // consent 证据落库失败不阻断 UI（outbox 尽力；后续可重试）
                }
                SyncWorker.enqueue(context)
                message = "麦克风已开启（仅端侧处理，不会上传录音）。"
            } else {
                // 权限拒绝：micEnabled 仍为 false，Switch 自动回弹
                // P1.4：权限拒绝时写 voice_features consent（granted=false）作为撤销证据
                try {
                    container.repository.saveVoiceFeaturesConsent(false)
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

        // ===== 机构人工支持（v0.6.1，P0-2） =====
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
                                        context, container.preferences, container.repository
                                    )
                                    reEnabling = container.preferences.consentSyncPending
                                    message = "被动感知已开启，等待授权同步…"
                                } else {
                                    performPassiveSensingStop(context, container.preferences, container.repository)
                                    reEnabling = false
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
                                container.repository.saveVoiceFeaturesConsent(false)
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
            scope.launch { container.repository.requestDataAction("export"); SyncWorker.enqueue(context); message = "已创建数据导出请求。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("申请导出数据") }
        OutlinedButton(onClick = {
            scope.launch { container.repository.requestDataAction("delete"); SyncWorker.enqueue(context); message = "已创建删除请求；依法需保留的数据可能不立即删除。" }
        }, modifier = Modifier.fillMaxWidth()) { Text("申请删除数据") }
        OutlinedButton(onClick = {
            scope.launch {
                // v0.6.1（P0-3）：撤回同意并停止服务 → 唯一领域操作（原子协调全部撤回）
                ServiceRevocationCoordinator.revokeService(context, container.preferences, container.repository)
                message = "已停止服务并提交撤回请求。"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("撤回同意并停止服务") }
        message?.let { Text(it) }
        HorizontalDivider()

        Text("机构：${container.preferences.institutionCode.ifBlank { "未配置" }}")
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
