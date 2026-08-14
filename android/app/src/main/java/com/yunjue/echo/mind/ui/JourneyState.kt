package com.yunjue.echo.mind.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.FeatureFlagRepository
import com.yunjue.echo.mind.data.MemoryRepository
import com.yunjue.echo.mind.data.PortraitRepository
import com.yunjue.echo.mind.data.SyncStateRepository
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.EchoContextRetriever
import com.yunjue.echo.mind.intelligence.ReasoningTaskId
import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.journeyAggregateParams
import com.yunjue.echo.mind.journey.journeyChunkDays
import com.yunjue.echo.mind.journey.journeyGroups
import com.yunjue.echo.mind.journey.journeyThumbnailFrame
import com.yunjue.echo.mind.journey.journeyWeekGroups
import com.yunjue.echo.mind.journey.journeyWindowDays
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.model.PortraitAvailability
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.SensingDiagnostics
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.dimensionTrendSymbol
import com.yunjue.echo.mind.model.portraitStabilitySummary
import com.yunjue.echo.mind.presence.computeEchoSceneFrame
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

/** 数据覆盖度（0..100 整数百分比）：窗口内有画像的天数 / 窗口天数。 */
internal fun coveragePercent(portraits: List<DailyPortraitDto>, days: Int): Int {
    if (days <= 0) return 0
    val today = LocalDate.now()
    val window = (0 until days).map { today.minusDays((days - 1 - it).toLong()).toString() }.toSet()
    val present = portraits.map { it.date }.filter { it in window }.toSet()
    return (present.size * 100 / days).coerceIn(0, 100)
}
