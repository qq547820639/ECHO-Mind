package com.yunjue.echo.mind.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.data.FeatureFlagRepository
import com.yunjue.echo.mind.data.PortraitRepository
import com.yunjue.echo.mind.data.SyncStateRepository
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.model.PortraitAvailability
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.SensingDiagnostics
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.dimensionTrendSymbol
import com.yunjue.echo.mind.model.portraitStabilitySummary
import com.yunjue.echo.mind.sensing.CapabilityState
import com.yunjue.echo.mind.sensing.SensingCapability
import com.yunjue.echo.mind.sensing.capabilityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 * NO_DATA 原因解析纯函数（T02 七态细化；v0.6.1 全部输入可真实到达；
 * Phase 6.5.3 输入改为 [PortraitAvailability] + [SensingDiagnostics]，语义不变）：
 * - sensing 未激活（consent/总开关关闭）→ CLOSED
 * - SENSOR 能力被拒 → PERMISSION
 * - baselineDays == 0 → 新用户尚无窗口（替代 legacy observationDays）
 * - 无近期采集（从未采集或 heartbeat 超过 3 天）→ SYSTEM_BACKGROUND（电池/后台受限的纯函数代理）
 * - 连续持久化失败 > 0 → PERSISTENCE_FAILURE
 * - 有待上传特征（pendingUploadCount > 0）→ AWAITING_UPLOAD
 * - 部分核心 source 缺失（missingSources 非空）→ SOURCE_GAPS
 * - 其余 → UNKNOWN
 */
internal fun resolveTrendNoDataReason(
    availability: PortraitAvailability,
    diagnostics: SensingDiagnostics
): TrendNoDataReason = when {
    !diagnostics.sensingActive -> TrendNoDataReason.CLOSED
    diagnostics.capabilities[SensingCapability.SENSOR] == CapabilityState.DENIED -> TrendNoDataReason.PERMISSION
    availability.baselineDays <= 0 -> TrendNoDataReason.NEW_USER
    !hasRecentCollection(diagnostics.lastCollectionAt) -> TrendNoDataReason.SYSTEM_BACKGROUND
    diagnostics.consecutivePersistenceFailures > 0 -> TrendNoDataReason.PERSISTENCE_FAILURE
    diagnostics.pendingUploadCount > 0 -> TrendNoDataReason.AWAITING_UPLOAD
    availability.missingSources.isNotEmpty() -> TrendNoDataReason.SOURCE_GAPS
    else -> TrendNoDataReason.UNKNOWN
}

/**
 * 最近是否仍在新采集（纯函数）：无采集记录或超过 [COLLECTOR_HEARTBEAT_STALE_MS] 未采集
 * → 视为系统后台受限 / 采集停滞（替代旧 `isIgnoringBatteryOptimizations` + heartbeat 双判定的
 * 可测试纯函数代理；电池豁免检查折叠为「最近采集时间是否新鲜」）。
 */
internal fun hasRecentCollection(lastCollectionAt: Long): Boolean =
    lastCollectionAt > 0L && System.currentTimeMillis() - lastCollectionAt <= COLLECTOR_HEARTBEAT_STALE_MS

/**
 * source code → 对应能力（missingSources 补集推导用，Phase 6.5.3）。
 * mic_opt 不在此表：麦克风永远可选，缺失不影响核心趋势（避免假 SOURCE_GAPS）。
 */
internal val SOURCE_CAPABILITY: Map<String, SensingCapability> = mapOf(
    "accel" to SensingCapability.SENSOR,
    "gyro" to SensingCapability.SENSOR,
    "screen" to SensingCapability.SCREEN,
    "notification" to SensingCapability.NOTIFICATION,
    "app_activity" to SensingCapability.USAGE
)

/**
 * 由本地能力状态推导缺失 source 补集（Phase 6.5.3：Trend 脱离 legacy Profile 后，
 * 替代 profile.sources_present_union 与期望核心源的差集）。能力非 AVAILABLE（DENIED /
 * UNAVAILABLE / DISABLED）即视为该 source 缺失。
 */
internal fun missingSourcesFromCapabilities(capabilities: Map<SensingCapability, CapabilityState>): List<String> =
    EXPECTED_CORE_SOURCES.toList().filter { code ->
        val capability = SOURCE_CAPABILITY[code] ?: return@filter false
        (capabilities[capability] ?: CapabilityState.UNAVAILABLE) != CapabilityState.AVAILABLE
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

/** collector heartbeat 新鲜度阈值（3 天）：超过则视为后台受限/采集停滞。 */
internal const val COLLECTOR_HEARTBEAT_STALE_MS = 3 * 24 * 60 * 60 * 1000L

@Composable
fun TrendScreen(
    portraitRepository: PortraitRepository,
    syncStateRepository: SyncStateRepository,
    featureFlagRepository: FeatureFlagRepository,
    onGoToSupport: () -> Unit = {}
) {
    val context = LocalContext.current
    // 7 日 / 28 日窗口（Milestone G：Portrait Timeline）
    var windowDays by remember { mutableStateOf(7) }
    // Phase 6.5.3：Trend 脱离 legacy Profile——画像可用性（baseline_days / missingSources）来自
    // GET /v1/me/baseline/status + 本地能力判定，不再 fetchProfile() / ProfileDisplay
    var availability by remember { mutableStateOf<PortraitAvailability?>(null) }
    var diagnostics by remember { mutableStateOf<SensingDiagnostics?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    val timeline by portraitRepository.observePortraits(windowDays).collectAsState()

    // 被动感知 consent + 租户 flag：任一关闭 → permission_disabled 态
    val consent by syncStateRepository.passiveSensingConsentFlow().collectAsState(initial = false)
    val flags by featureFlagRepository.featureFlagsFlow.collectAsState(initial = emptyMap())
    val permissionEnabled = consent && (flags["passive_sensing_enabled"] ?: false)

    LaunchedEffect(retryKey, windowDays, consent) {
        portraitRepository.refreshPortraits(windowDays)
        // 快照：能力状态 + 基线状态 + 本地采集/同步时间（一次 IO 内计算）
        val snapshot = withContext(Dispatchers.IO) {
            val caps = SensingCapability.entries.associateWith {
                capabilityState(context, it, consent)
            }
            val baseline = runCatching { portraitRepository.fetchBaselineStatus() }.getOrNull()
            val collectedAt = syncStateRepository.lastCollectionTimestamp()
            val syncedAt = syncStateRepository.lastSyncTimestamp()
            val avail = PortraitAvailability(
                baselineStatus = baseline?.status ?: "UNKNOWN",
                baselineDays = baseline?.baselineDays ?: 0,
                coverage = baseline?.todayCoverage?.toFloat() ?: 0f,
                // missingSources = 本地能力状态补集（替代 profile.sources_present_union 差集）
                missingSources = missingSourcesFromCapabilities(caps),
                lastCollectedAt = collectedAt,
                lastSyncedAt = syncedAt,
                materializationStatus = "none",
                capabilities = caps.values.toList()
            )
            val diag = SensingDiagnostics(
                capabilities = caps,
                sensingActive = consent,
                lastCollectionAt = collectedAt,
                consecutivePersistenceFailures = syncStateRepository.consecutivePersistenceFailures(),
                pendingUploadCount = syncStateRepository.pendingUploadCount()
            )
            avail to diag
        }
        availability = snapshot.first
        diagnostics = snapshot.second
    }

    val state = resolveTrendState(
        loading = timeline.loading,
        loadFailed = timeline.loadFailed,
        offlineCached = timeline.fromCache,
        items = if (timeline.portraits.isEmpty()) null else timeline.portraits,
        permissionEnabled = permissionEnabled,
        isPartial = timeline.isPartial
    )

    // NO_DATA 细分原因（Phase 6.5.3：PortraitAvailability + SensingDiagnostics，语义不变）
    val currentAvailability = availability ?: PortraitAvailability()
    val currentDiagnostics = diagnostics ?: SensingDiagnostics(sensingActive = consent)
    val noDataReason = resolveTrendNoDataReason(currentAvailability, currentDiagnostics)
    val lastCollectionTs = currentAvailability.lastCollectedAt
    val lastSyncTs = currentAvailability.lastSyncedAt

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
                    val value = byDate[day.toString()]?.dimensionValue(dim)
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
        val values = portraits.mapNotNull { it.dimensionValue(dim) }
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
