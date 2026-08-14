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

@Composable
fun TrendScreen(
    portraitRepository: PortraitRepository,
    syncStateRepository: SyncStateRepository,
    featureFlagRepository: FeatureFlagRepository,
    memoryRepository: MemoryRepository,
    aiNarrativeService: AiNarrativeService,
    contextRetriever: EchoContextRetriever,
    preferences: AppPreferences,
    onGoToSupport: () -> Unit = {}
) {
    val context = LocalContext.current
    // ERA 8：Journey 时间尺度（Day/Week/Month → 7/28 天画像窗口）
    var scale by remember { mutableStateOf(JourneyScale.DAY) }
    var windowDays by remember { mutableStateOf(journeyWindowDays(JourneyScale.DAY)) }
    // Phase 6.5.3：Trend 脱离 legacy Profile——画像可用性（baseline_days / missingSources）来自
    // GET /v1/me/baseline/status + 本地能力判定，不再 fetchProfile() / ProfileDisplay
    var availability by remember { mutableStateOf<PortraitAvailability?>(null) }
    var diagnostics by remember { mutableStateOf<SensingDiagnostics?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    val timeline by portraitRepository.observePortraits(windowDays).collectAsStateWithLifecycle()

    // ERA 8：长期叙事（v2 §42：证据由 Context Retriever 按 FIND_LONGITUDINAL_PATTERN 策略真实检索）
    var narrative by remember { mutableStateOf<AiNarrativeService.NarrativeResult?>(null) }
    var showEvidence by remember { mutableStateOf(false) }

    // 被动感知 consent + 租户 flag：任一关闭 → permission_disabled 态
    val consent by syncStateRepository.passiveSensingConsentFlow().collectAsStateWithLifecycle(initialValue = false)
    val flags by featureFlagRepository.featureFlagsFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
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

    // ERA 8：长期叙事（画像时间线就绪后组装）
    LaunchedEffect(timeline.portraits, scale) {
        val evidence = contextRetriever.retrieve(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN)
        narrative = aiNarrativeService.longitudinalNarrative(
            evidence = evidence,
            deterministicText = portraitStabilitySummary(timeline.portraits),
        )
    }

    // NO_DATA 细分原因（Phase 6.5.3：PortraitAvailability + SensingDiagnostics，语义不变）
    val currentAvailability = availability ?: PortraitAvailability()
    val currentDiagnostics = diagnostics ?: SensingDiagnostics(sensingActive = consent)
    val noDataReason = resolveTrendNoDataReason(currentAvailability, currentDiagnostics)
    val lastCollectionTs = currentAvailability.lastCollectedAt
    val lastSyncTs = currentAvailability.lastSyncedAt

    Page("旅程 · 我的时间") {
        // 契约点 2 固定免责文案（单测锚点）
        Text(TREND_DISCLAIMER)
        HorizontalDivider()

        // ERA 8：Journey 时间尺度（Day/Week/Month/Season/Year）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            JourneyScale.entries.forEach { s ->
                FilterChip(
                    selected = scale == s,
                    onClick = {
                        scale = s
                        windowDays = journeyWindowDays(s)
                    },
                    label = {
                        Text(
                            when (s) {
                                JourneyScale.DAY -> "天"
                                JourneyScale.WEEK -> "周"
                                JourneyScale.MONTH -> "月"
                                JourneyScale.SEASON -> "季"
                                JourneyScale.YEAR -> "年"
                            }
                        )
                    }
                )
            }
        }

        when (state) {
            TrendUiState.LOADING -> {
                CircularProgressIndicator()
                Text("旅程加载中…")
            }
            TrendUiState.PERMISSION_DISABLED -> {
                Text("被动感知已关闭或权限被撤，无法获取新的旅程数据。")
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(appSettingsIntent(context)) }
                }) { Text("前往系统设置修复权限") }
            }
            TrendUiState.ERROR -> {
                Text("旅程加载失败")
                Button(onClick = { retryKey++ }) { Text("重试") }
            }
            TrendUiState.NO_DATA -> {
                Text(trendNoDataReasonText(noDataReason))
                Text("最近成功采集：${formatTimestamp(lastCollectionTs)}")
                Text("最近成功同步：${formatTimestamp(lastSyncTs)}")
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
                Text("当前离线，以下为缓存的旅程。")
                JourneyContent(
                    scale = scale,
                    timeline = timeline,
                    narrative = narrative,
                    showEvidence = showEvidence,
                    onToggleEvidence = { showEvidence = !showEvidence },
                    seed = journeySeed(preferences),
                    lastCollectionTs = lastCollectionTs,
                    lastSyncTs = lastSyncTs,
                    portraitFeedback = portraitRepository::portraitFeedback,
                )
            }
            TrendUiState.FRESH, TrendUiState.PARTIAL ->
                JourneyContent(
                    scale = scale,
                    timeline = timeline,
                    narrative = narrative,
                    showEvidence = showEvidence,
                    onToggleEvidence = { showEvidence = !showEvidence },
                    seed = journeySeed(preferences),
                    lastCollectionTs = lastCollectionTs,
                    lastSyncTs = lastSyncTs,
                    portraitFeedback = portraitRepository::portraitFeedback,
                )
        }
    }
}

/** Journey 视觉种子：userId 稳定派生（与 Identity Genome 同源，保证跨天视觉血缘）。 */
internal fun journeySeed(preferences: AppPreferences): Long =
    preferences.userId.fold(0L) { acc, c -> acc * 31L + c.code }

/**
 * ERA 8 — Journey 主体（Master Prompt PART 69/70/71）：
 * 视觉记忆河流（每天一个确定性视觉单元）→ 长期叙事（AI → 确定性综述 fallback）→
 * 「查看依据」Evidence Layer（定量图表降级到第二层，不在第一视觉）。
 */
@Composable
private fun JourneyContent(
    scale: JourneyScale,
    timeline: PortraitTimelineUiState,
    narrative: AiNarrativeService.NarrativeResult?,
    showEvidence: Boolean,
    onToggleEvidence: () -> Unit,
    seed: Long,
    lastCollectionTs: Long,
    lastSyncTs: Long,
    portraitFeedback: (String) -> Boolean?,
) {
    val portraits = timeline.portraits

    // 1. 视觉记忆河流（第一视觉）
    VisualMemoryRiver(scale = scale, portraits = portraits, seed = seed, portraitFeedback = portraitFeedback)

    // 2. 长期叙事（变化发生在叙事里，图表只是依据）
    narrative?.let { n ->
        Text(
            if (n.text.isBlank()) portraitStabilitySummary(portraits) else n.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp)
        )
        if (!n.usedSources.isNullOrEmpty()) {
            Text(
                "依据：${n.usedSources.joinToString("、") { dataSourceLabelForJourney(it) }}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    // 3. Evidence Layer：查看依据（定量证据，非第一视觉）
    HorizontalDivider()
    TextButton(onClick = onToggleEvidence) { Text(if (showEvidence) "收起依据" else "查看依据") }
    if (showEvidence) {
        PortraitTimelineContent(
            timeline = timeline,
            lastCollectionTs = lastCollectionTs,
            lastSyncTs = lastSyncTs,
            portraitFeedback = portraitFeedback,
        )
    }
}

/** 依据数据源 → 用户可读标签（Journey 版；与 Today 的 dataSourceLabel 语义一致）。 */
private fun dataSourceLabelForJourney(category: com.yunjue.echo.mind.intelligence.DataSourceCategory): String = when (category) {
    com.yunjue.echo.mind.intelligence.DataSourceCategory.PORTRAIT_HISTORY -> "历史画像"
    com.yunjue.echo.mind.intelligence.DataSourceCategory.BASELINE -> "个人基线"
    com.yunjue.echo.mind.intelligence.DataSourceCategory.CONTEXT_EXCEPTIONS -> "你告诉我的特殊日期"
    com.yunjue.echo.mind.intelligence.DataSourceCategory.USER_CORRECTIONS -> "你纠正过我的"
    else -> "其他"
}

/** 视觉记忆河流：DAY=逐日 7 帧；WEEK=按周聚合；MONTH=按月聚合；SEASON/YEAR=按 30 天聚合。 */
@Composable
private fun VisualMemoryRiver(
    scale: JourneyScale,
    portraits: List<DailyPortraitDto>,
    seed: Long,
    portraitFeedback: (String) -> Boolean?,
) {
    when (scale) {
        JourneyScale.DAY -> {
            val days = (0 until 7).map { LocalDate.now().minusDays((7 - 1 - it).toLong()) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                days.forEach { day ->
                    val portrait = portraits.firstOrNull { it.date == day.toString() }
                    JourneyThumbCell(
                        portrait = portrait,
                        seed = seed,
                        label = "${day.monthValue}/${day.dayOfMonth}",
                        mark = portraitFeedback(day.toString())?.let { if (it) "✓" else "✗" } ?: " ",
                    )
                }
            }
            Text("✓ 你觉得像 · ✗ 你觉得不太像", style = MaterialTheme.typography.labelSmall)
        }
        JourneyScale.WEEK -> {
            val groups = journeyWeekGroups(portraits)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                groups.forEachIndexed { index, group ->
                    JourneyAggregateCell(
                        portraits = group,
                        seed = seed,
                        label = "第 ${index + 1} 周",
                    )
                }
            }
        }
        JourneyScale.MONTH, JourneyScale.SEASON, JourneyScale.YEAR -> {
            val groups = journeyGroups(portraits, journeyChunkDays(scale))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                groups.forEachIndexed { index, group ->
                    val span = group.firstOrNull()?.date?.take(7) ?: ""
                    val last = group.lastOrNull()?.date?.take(7) ?: ""
                    JourneyAggregateCell(
                        portraits = group,
                        seed = seed,
                        label = if (span.isNotBlank()) "${span}…${last}" else "第 ${index + 1} 段",
                        large = scale == JourneyScale.MONTH && groups.size == 1,
                    )
                }
            }
        }
    }
}

/** 单日视觉记忆单元（CANONICAL_SNAPSHOT；无数据日 = 弥散占位，不编造）。 */
@Composable
private fun JourneyThumbCell(
    portrait: DailyPortraitDto?,
    seed: Long,
    label: String,
    mark: String,
) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(52.dp)) {
            val frame = journeyThumbnailFrame(portrait, seed, this.size.width, this.size.height)
            if (frame != null) {
                drawEchoFrame(frame)
            } else {
                // 无数据日：低亮度弥散占位（Journey 的「没有记录」也是视觉记忆）
                drawCircle(
                    color = placeholderColor,
                    radius = this.size.minDimension * 0.2f,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(mark, style = MaterialTheme.typography.labelSmall)
    }
}

/** 周/月聚合帧（视觉逐渐聚合，不是折线图）。 */
@Composable
private fun JourneyAggregateCell(
    portraits: List<DailyPortraitDto>,
    seed: Long,
    label: String,
    large: Boolean = false,
) {
    val cellSize = if (large) 140.dp else 72.dp
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(cellSize)) {
            val params = journeyAggregateParams(portraits)
            val frame = params?.let {
                computeEchoSceneFrame(it, seed, JOURNEY_CANONICAL_TIME_SECONDS, this.size.width, this.size.height)
            }
            if (frame != null) {
                drawEchoFrame(frame)
            } else {
                drawCircle(
                    color = placeholderColor,
                    radius = this.size.minDimension * 0.2f,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Portrait Timeline 内容（Milestone G）：
 * - 7 日视图：各维度（节律/移动/屏幕）相对趋势符号矩阵（↑↓→~，不做精确数字强调）
 * - 28 日视图：各维度概览 + 确定性综述（最稳定 / 变化较明显）
 * - 不做心理状态解释（TREND_DISCLAIMER 语义保持）
 */
@Composable
private fun PortraitTimelineContent(
    timeline: PortraitTimelineUiState,
    lastCollectionTs: Long,
    lastSyncTs: Long,
    portraitFeedback: (String) -> Boolean?
) {
    val portraits = timeline.portraits

    // 数据覆盖度（近 N 天窗口）
    Text("数据覆盖度：${coveragePercent(portraits, timeline.days)}%（近 ${timeline.days} 天）", style = MaterialTheme.typography.titleMedium)
    // 日期覆盖条（仅最近 7 天窗口展示单日格子；28 天不逐日铺开）
    if (timeline.days <= 7) {
        val days = (0 until 7).map { LocalDate.now().minusDays((7 - 1 - it).toLong()) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                val hasData = portraits.any { it.date == day.toString() }
                val feedback = portraitFeedback(day.toString())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(if (hasData) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
                    // v0.7 反馈标记：✓ 你觉得像 / ✗ 你觉得不太像（无反馈留空位保持对齐）
                    Text(
                        when (feedback) {
                            true -> "✓"
                            false -> "✗"
                            null -> " "
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Text("✓ 你觉得像 · ✗ 你觉得不太像", style = MaterialTheme.typography.labelSmall)
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
