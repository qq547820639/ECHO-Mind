package com.yunjue.echo.mind.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Portrait 纯逻辑核心（Milestone F/G/H）。
 *
 * 本文件**只包含纯 Kotlin 逻辑**（不依赖 Android framework / org.json / Room / Compose），
 * 供 ui / data 两层共用，并可直接被纯 JVM 单测覆盖：
 * - 九态状态机 [resolveTodayPortraitState]（含服务端 status → 端侧状态映射 [mapServerStatus]）
 * - 各态用户文案 [todayPortraitStateText]（单测锚点，禁止 UI 层另行硬编码）
 * - 维度中文标签 / 取值映射 / 7 日趋势符号
 * - 28 日稳定性综述（确定性计算：SIMILAR 比例最高 = 最稳定，非 SIMILAR 最多 = 变化较明显）
 * - 时区安全日期（timezone 修改后的 date 处理：缓存键按端侧本地日期计算）
 */

/** 端侧 Today Portrait 九态（spec：禁止统一显示"暂无数据"）。 */
enum class PortraitStatus {
    /** 加载中：spinner（首帧无缓存/无数据时）。 */
    LOADING,

    /** 服务端 WARMING_UP：基线尚未成型，显示固定文案。 */
    WARMING_UP,

    /** 服务端 EARLY_BASELINE：当天事实（服务端 summary）。 */
    EARLY_BASELINE,

    /** 服务端 READY：完整画像。 */
    READY,

    /** 服务端 PARTIAL_DATA：画像前加「数据不完整」提示。 */
    PARTIAL_DATA,

    /** 服务端 LOW_CONFIDENCE：显示服务端 summary（数据不够完整文案由服务端给出）。 */
    LOW_CONFIDENCE,

    /** 离线/网络失败走 Room 缓存：显示最近一次画像 + 离线提示。 */
    OFFLINE_CACHED,

    /** 被动感知关闭：提示 + 重新开启按钮。 */
    SENSING_DISABLED,

    /** 加载失败（无缓存可用）：错误 + 重试按钮。 */
    ERROR
}

/**
 * 状态机输入（纯数据，便于构造与测试）。
 *
 * - [sensingEnabled]：被动感知总开关（PassiveSensingPrefs.passiveSensingEnabled）
 * - [loading]：网络请求进行中（且尚未产出结果）
 * - [hasCache]：Room 中是否有当天画像缓存
 * - [networkAvailable]：当前是否有可用网络
 * - [fetchFailed]：最近一次网络请求失败（异常 / 非 2xx / 解析失败）
 * - [serverStatus]：服务端返回的画像 status（仅请求成功时非 null）
 */
data class PortraitStateInputs(
    val sensingEnabled: Boolean,
    val loading: Boolean = false,
    val hasCache: Boolean = false,
    val networkAvailable: Boolean = true,
    val fetchFailed: Boolean = false,
    val serverStatus: String? = null
)

/**
 * 九态状态机（Milestone F/H，纯函数）。
 *
 * 判定顺序（spec）：
 * 1. 感知开关关闭 → SENSING_DISABLED；
 * 2. 加载中 → 有缓存显示缓存（OFFLINE_CACHED）否则 LOADING；
 * 3. 无网络 / 请求失败 → 有缓存 OFFLINE_CACHED，无缓存 ERROR；
 * 4. 服务端 status → READY / WARMING_UP / EARLY_BASELINE / PARTIAL_DATA / LOW_CONFIDENCE；
 * 5. 未知 status / 无 status → ERROR（fail-closed，绝不伪装成功）。
 */
fun resolveTodayPortraitState(inputs: PortraitStateInputs): PortraitStatus = when {
    !inputs.sensingEnabled -> PortraitStatus.SENSING_DISABLED
    inputs.loading -> if (inputs.hasCache) PortraitStatus.OFFLINE_CACHED else PortraitStatus.LOADING
    !inputs.networkAvailable -> if (inputs.hasCache) PortraitStatus.OFFLINE_CACHED else PortraitStatus.ERROR
    inputs.fetchFailed -> if (inputs.hasCache) PortraitStatus.OFFLINE_CACHED else PortraitStatus.ERROR
    inputs.serverStatus == null -> PortraitStatus.ERROR
    else -> mapServerStatus(inputs.serverStatus) ?: PortraitStatus.ERROR
}

/**
 * 服务端画像 status → 端侧状态（纯函数）。
 * 未知 / 空 status 返回 null（由调用方 fail-closed 到 ERROR）。
 *
 * 覆盖服务端五态：WARMING_UP / EARLY_BASELINE / READY / PARTIAL_DATA / LOW_CONFIDENCE。
 * 冷启动映射：第 1 天（基线未成型）→ WARMING_UP；第 8 天（基线成型）→ READY。
 */
fun mapServerStatus(status: String?): PortraitStatus? = when (status) {
    "WARMING_UP" -> PortraitStatus.WARMING_UP
    "EARLY_BASELINE" -> PortraitStatus.EARLY_BASELINE
    "READY" -> PortraitStatus.READY
    "PARTIAL_DATA" -> PortraitStatus.PARTIAL_DATA
    "LOW_CONFIDENCE" -> PortraitStatus.LOW_CONFIDENCE
    else -> null
}

// ===== 九态文案（单测锚点；与 spec 一一对应，UI 层不得另行硬编码） =====

internal const val PORTRAIT_COPY_WARMING_UP =
    "ECHO 正在慢慢了解你的日常节奏。再积累几天，就能开始比较“今天”和“平常的你”。"
internal const val PORTRAIT_COPY_PARTIAL_BANNER = "今天的数据还不完整，以下画像仅反映已经采集到的部分。"
internal const val PORTRAIT_COPY_OFFLINE_BANNER = "当前离线，显示最近一次生成的画像。"
internal const val PORTRAIT_COPY_SENSING_DISABLED = "被动感知已关闭。"
internal const val PORTRAIT_COPY_LOAD_FAILED = "加载失败"
internal const val PORTRAIT_COPY_RETRY = "重试"
internal const val PORTRAIT_COPY_REENABLE = "重新开启"
internal const val PORTRAIT_COPY_FEEDBACK_QUESTION = "这个描述像今天的你吗？"
internal const val PORTRAIT_COPY_FEEDBACK_LIKE = "挺像"
internal const val PORTRAIT_COPY_FEEDBACK_NOT_LIKE = "不太像"
internal const val PORTRAIT_COPY_FEEDBACK_SAVED = "已记录，感谢反馈。"
internal const val PORTRAIT_COPY_GO_TREND = "过去 7 天 →"
internal const val PORTRAIT_COPY_SECTION_WHY = "为什么这么说？"
internal const val PORTRAIT_COPY_SECTION_ACTION = "想做点什么？"
internal const val PORTRAIT_COPY_GO_SKILLS = "前往「能力」页"
internal const val PORTRAIT_COPY_DIMENSIONS_TITLE = "和你的平常相比"

/**
 * 状态 → 用户文案（纯函数；LOADING 为 spinner、EARLY_BASELINE/LOW_CONFIDENCE/READY 为内容驱动，返回空串）。
 */
fun todayPortraitStateText(status: PortraitStatus): String = when (status) {
    PortraitStatus.WARMING_UP -> PORTRAIT_COPY_WARMING_UP
    PortraitStatus.PARTIAL_DATA -> PORTRAIT_COPY_PARTIAL_BANNER
    PortraitStatus.OFFLINE_CACHED -> PORTRAIT_COPY_OFFLINE_BANNER
    PortraitStatus.SENSING_DISABLED -> PORTRAIT_COPY_SENSING_DISABLED
    PortraitStatus.ERROR -> PORTRAIT_COPY_LOAD_FAILED
    PortraitStatus.LOADING,
    PortraitStatus.EARLY_BASELINE,
    PortraitStatus.LOW_CONFIDENCE,
    PortraitStatus.READY -> ""
}

// ===== 维度展示（Milestone F/G） =====

/**
 * Today 画像「和你的平常相比」对照表维度顺序（STABILITY 之外的五个维度 + 整体节律）。
 * 7 日趋势视图只取前三个（节律/移动/屏幕互动，spec：不做精确数字强调）。
 */
internal val PORTRAIT_DIMENSIONS: List<String> = listOf(
    "RHYTHM", "MOVEMENT", "SCREEN_PATTERN", "DAY_STRUCTURE", "STABILITY"
)

/** 7 日趋势矩阵渲染的维度子集（spec：节律/移动/屏幕）。 */
internal val PORTRAIT_TREND_DIMENSIONS: List<String> = listOf(
    "RHYTHM", "MOVEMENT", "SCREEN_PATTERN"
)

/** 维度键 → 中文标签（spec 映射；未知键原样返回，不泄露内部码）。 */
fun dimensionDisplayName(key: String): String = when (key) {
    "RHYTHM" -> "作息"
    "MOVEMENT" -> "移动"
    "SCREEN_PATTERN" -> "屏幕互动"
    "DAY_STRUCTURE" -> "行为分布"
    "STABILITY" -> "整体节律"
    else -> key
}

/** 维度取值 → 中文文案（spec 映射；未知值原样返回）。 */
fun dimensionValueText(key: String, value: String): String = when (value) {
    "EARLIER" -> "偏早"
    "LATER" -> "偏晚"
    "SIMILAR" -> "接近"
    "IRREGULAR" -> "不规律"
    "LESS" -> "↓ 减少"
    "MORE" -> "↑ 增多"
    "MORE_CONCENTRATED" -> "更集中"
    "MORE_FRAGMENTED" -> "更零散"
    "VERY_SIMILAR" -> "非常接近"
    "SLIGHTLY_DIFFERENT" -> "稍有不同"
    "CLEARLY_DIFFERENT" -> "明显不同"
    else -> value
}

/**
 * 7 日趋势相对符号（spec：↑↓→，不做精确数字强调）：
 * EARLIER/LESS → ↓ 或 ↑ 的方向映射：EARLIER→↑、LATER→↓、LESS→↓、MORE→↑、SIMILAR→→；
 * IRREGULAR → ~（波动，无方向）；缺失/未知 → –。
 */
fun dimensionTrendSymbol(value: String?): String = when (value) {
    "EARLIER", "MORE", "MORE_CONCENTRATED", "CLEARLY_DIFFERENT" -> "↑"
    "LATER", "LESS", "MORE_FRAGMENTED" -> "↓"
    "SIMILAR", "VERY_SIMILAR", "SLIGHTLY_DIFFERENT" -> "→"
    "IRREGULAR" -> "~"
    else -> "–"
}

// ===== 28 日稳定性综述（Milestone G：客户端确定性计算，不做心理状态解释） =====

internal const val PORTRAIT_SUMMARY_NO_DATA = "暂无足够数据判断稳定性。"

/**
 * 28 日视图综述文字（纯函数，确定性）：
 * - 各维度统计 SIMILAR 比例，最高者 = 「最稳定」；
 * - 各维度统计非 SIMILAR 次数，最多者 = 「变化较明显」。
 * 无任何维度数据时返回 [PORTRAIT_SUMMARY_NO_DATA]。
 * 仅陈述数据事实，不做心理状态解释。
 */
fun portraitStabilitySummary(portraits: List<DailyPortraitDto>): String {
    data class DimStat(val similar: Int, val total: Int)
    val stats = PORTRAIT_DIMENSIONS.associateWith { dim ->
        val values = portraits.mapNotNull { it.dimensions[dim] }
        DimStat(similar = values.count { it == "SIMILAR" }, total = values.size)
    }.filterValues { it.total > 0 }
    if (stats.isEmpty()) return PORTRAIT_SUMMARY_NO_DATA

    val stable = stats.maxByOrNull { it.value.similar.toFloat() / it.value.total }!!.key
    val changed = stats.maxByOrNull { it.value.total - it.value.similar }!!.key
    return "最稳定：${dimensionDisplayName(stable)}；变化较明显：${dimensionDisplayName(changed)}"
}

// ===== 时区安全日期（Milestone H：timezone 修改后的 date 处理） =====

/**
 * 按指定时区把瞬时时间换算为本地日期字符串（ISO-8601，yyyy-MM-dd）。
 * 端侧「今天」缓存键以此为准：用户修改系统时区后，本地日期随之变化，
 * 旧缓存（旧时区的昨天/今天）不会与新「今天」匹配，从而触发重新拉取而非误用旧画像。
 */
fun todayLocalDateString(instant: Instant, zoneId: ZoneId): String =
    LocalDate.ofInstant(instant, zoneId).toString()

// ===== UI 状态容器（LocalRepository 发出，UI 消费；纯数据无 Android 依赖） =====

/**
 * Today 页 UI 状态（九态 + 当前画像 + 离线横幅标志）。
 *
 * [offline] 语义：仅 OFFLINE_CACHED 时有效——
 * - offline=true ：网络失败/无网络，显示「当前离线，显示最近一次生成的画像。」
 * - offline=false：缓存优先展示（后台刷新中），不显示离线横幅
 */
data class PortraitUiState(
    val status: PortraitStatus,
    val portrait: DailyPortraitDto? = null,
    val offline: Boolean = false
)

/**
 * 画像时间线 UI 状态（Milestone G：7/28 日窗口）。
 * 七态（LOADING/OFFLINE_CACHED/FRESH/PARTIAL/NO_DATA/ERROR/PERMISSION_DISABLED）
 * 由 [com.yunjue.echo.mind.ui.resolveTrendState] 基于本状态推导（保留既有趋势页七态语义）。
 */
data class PortraitTimelineUiState(
    val days: Int = 7,
    val portraits: List<DailyPortraitDto> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val fromCache: Boolean = false,
    val isPartial: Boolean = false,
    val missingDates: List<String> = emptyList()
)
