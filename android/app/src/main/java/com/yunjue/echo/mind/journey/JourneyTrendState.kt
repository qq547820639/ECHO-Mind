package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitAvailability
import com.yunjue.echo.mind.model.SensingDiagnostics
import com.yunjue.echo.mind.sensing.CapabilityState
import com.yunjue.echo.mind.sensing.SensingCapability
import java.time.LocalDate

/**
 * ERA 13 — Journey 趋势状态纯逻辑（自 ui/JourneyState.kt 迁入 journey 领域包）。
 *
 * 应用层（JourneyViewModel/JourneyRepository）与 UI 共用同一套七态语义；
 * Screen 不再自行推导状态（Master Prompt §24/§27）。
 */

/** 趋势页七态（契约点 8）：loading / offline cached / fresh / partial / no data / error / permission disabled。 */
enum class TrendUiState { LOADING, OFFLINE_CACHED, FRESH, PARTIAL, NO_DATA, ERROR, PERMISSION_DISABLED }

/**
 * 趋势页 NO_DATA 细分原因（T02 七态细化）：
 * 区分「新用户无窗口 / 权限未授权 / 用户关闭感知 / 系统限制后台 / 部分 source 缺失 /
 * 本地持久化失败 / 等待上传」，避免统一显示"暂无数据"。
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
fun resolveTrendState(
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
 * NO_DATA 原因解析纯函数（T02 七态细化；输入为 [PortraitAvailability] + [SensingDiagnostics]）：
 * - sensing 未激活（consent/总开关关闭）→ CLOSED
 * - SENSOR 能力被拒 → PERMISSION
 * - baselineDays == 0 → 新用户尚无窗口
 * - 无近期采集（从未采集或 heartbeat 超过 3 天）→ SYSTEM_BACKGROUND
 * - 连续持久化失败 > 0 → PERSISTENCE_FAILURE
 * - 有待上传特征（pendingUploadCount > 0）→ AWAITING_UPLOAD
 * - 部分核心 source 缺失（missingSources 非空）→ SOURCE_GAPS
 * - 其余 → UNKNOWN
 */
fun resolveTrendNoDataReason(
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
 * → 视为系统后台受限 / 采集停滞。
 */
fun hasRecentCollection(lastCollectionAt: Long): Boolean =
    lastCollectionAt > 0L && System.currentTimeMillis() - lastCollectionAt <= COLLECTOR_HEARTBEAT_STALE_MS

/**
 * source code → 对应能力（missingSources 补集推导用）。
 * mic_opt 不在此表：麦克风永远可选，缺失不影响核心趋势（避免假 SOURCE_GAPS）。
 */
val SOURCE_CAPABILITY: Map<String, SensingCapability> = mapOf(
    "accel" to SensingCapability.SENSOR,
    "gyro" to SensingCapability.SENSOR,
    "screen" to SensingCapability.SCREEN,
    "notification" to SensingCapability.NOTIFICATION,
    "app_activity" to SensingCapability.USAGE
)

/**
 * 由本地能力状态推导缺失 source 补集（Trend 脱离 legacy Profile 后，
 * 替代 profile.sources_present_union 与期望核心源的差集）。
 */
fun missingSourcesFromCapabilities(capabilities: Map<SensingCapability, CapabilityState>): List<String> =
    EXPECTED_CORE_SOURCES.toList().filter { code ->
        val capability = SOURCE_CAPABILITY[code] ?: return@filter false
        (capabilities[capability] ?: CapabilityState.UNAVAILABLE) != CapabilityState.AVAILABLE
    }

/** NO_DATA 原因 → 用户可读文案（不暴露工程术语/HTTP 码）。 */
fun trendNoDataReasonText(reason: TrendNoDataReason): String = when (reason) {
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
val EXPECTED_CORE_SOURCES: Set<String> = setOf(
    "accel", "gyro", "screen", "notification", "app_activity"
)

/** collector heartbeat 新鲜度阈值（3 天）：超过则视为后台受限/采集停滞。 */
const val COLLECTOR_HEARTBEAT_STALE_MS = 3 * 24 * 60 * 60 * 1000L

/** 数据覆盖度（0..100 整数百分比）：窗口内有画像的天数 / 窗口天数。 */
fun coveragePercent(portraits: List<DailyPortraitDto>, days: Int): Int {
    if (days <= 0) return 0
    val today = LocalDate.now()
    val window = (0 until days).map { today.minusDays((days - 1 - it).toLong()).toString() }.toSet()
    val present = portraits.map { it.date }.filter { it in window }.toSet()
    return (present.size * 100 / days).coerceIn(0, 100)
}
