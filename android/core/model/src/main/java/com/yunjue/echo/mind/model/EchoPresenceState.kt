package com.yunjue.echo.mind.model

import java.time.Instant

/**
 * ERA 1/2 — ECHO Presence 领域模型（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 全系统只有一个 Current ECHO State（Master Prompt PART 52/53）：
 * Today UI / Wallpaper / Dream 都读取同一个 [EchoStateStore]，
 * 视觉和语言可以因 surface 不同而变化，底层认知不能矛盾。
 */

/**
 * ECHO 成长成熟度（Master Prompt PART 66）：
 * SEED → DISCOVERING → EMERGING → KNOWN → MATURE。
 *
 * 单一定义（Organism Quality §34 语义统一）：**自苏醒锚点（awakenedAtEpochMs）起的
 * 日历天数**驱动的确定性阶梯——「认识你多久」，与数据丰富度无关：
 * - Day 0：SEED（苏醒当天，必有画报，但不伪造观察）
 * - 1-2 天：DISCOVERING（出现真实观察，不与 baseline 比较）
 * - 3-6 天：EMERGING（tentative patterns，与后端 EARLY_BASELINE 同构）
 * - 7-27 天：KNOWN（baseline 成熟，「今天 vs 通常的你」）
 * - ≥28 天：MATURE（完整基线窗口，「这是我的 ECHO」）
 *
 * 注意：`baseline.validDays`（28 天滚动窗口内的分桶有效日，weekday 桶 ≤20）是**另一个钟**，
 * 只用于置信度/状态机，不得喂给本函数（稀疏数据用户永远到不了 MATURE 的旧缺陷即源于此）。
 * 历史 Journey 重建无苏醒锚点时使用显式代理（见 JourneyVisuals.portraitMaturityProxy）。
 */
enum class EchoMaturity { SEED, DISCOVERING, EMERGING, KNOWN, MATURE }

/** 成熟度纯函数：自苏醒起的日历天数 → 阶段（负数按 0 处理）。 */
fun echoMaturity(calendarDaysSinceAwakening: Int): EchoMaturity = when {
    calendarDaysSinceAwakening >= 28 -> EchoMaturity.MATURE
    calendarDaysSinceAwakening >= 7 -> EchoMaturity.KNOWN
    calendarDaysSinceAwakening >= 3 -> EchoMaturity.EMERGING
    calendarDaysSinceAwakening >= 1 -> EchoMaturity.DISCOVERING
    else -> EchoMaturity.SEED
}

/**
 * ERA 20 §10 — 学习期一句话（production 唯一文案源；:app 与 :feature:qa 共用，
 * 禁止各自再写副本——QA mirror 收敛后的单点）。禁「数据不足」文案。
 */
fun learningPhaseHeadline(maturity: EchoMaturity): String = when (maturity) {
    EchoMaturity.SEED -> "初见。"
    EchoMaturity.DISCOVERING, EchoMaturity.EMERGING -> "我开始看到一些属于你的节奏。"
    EchoMaturity.KNOWN -> "我开始认识通常的你了。"
    EchoMaturity.MATURE -> "ECHO 还在了解今天。"
}

/**
 * 节律状态（RhythmModel 的当前快照；ERA 2 由 AmbientEngine 填充，ERA 1 为占位默认值）。
 * 全部为中性连续值，禁止评价性命名（见 PERSONAL_INTELLIGENCE_CONTRACT §4）。
 */
data class RhythmState(
    val activityLevel: Float = 0f,
    val rhythmDelta: Float = 0f,
    val regularity: Float = 0f,
    val coverage: Float = 0f,
)

/** 行为状态快照（事件密度 / 与 baseline 的偏差；中性值）。 */
data class BehaviorState(
    val density: Float = 0f,
    val deviation: Float = 0f,
)

/**
 * 可选情绪智能信号（ERA 10 才允许非空；PART 19：连续 latent state，禁 HAPPY/SAD 标签）。
 * 每个信号独立携带 confidence / source / updatedAt；状态表达必须支持「不确定」。
 */
data class AffectiveSignal(
    val value: Float,
    val confidence: Float,
    val source: String,
    val updatedAt: Instant,
)

data class AffectiveState(
    val activation: AffectiveSignal,
    val pleasantness: AffectiveSignal,
    val tension: AffectiveSignal,
    val mentalLoad: AffectiveSignal,
    val socialLoad: AffectiveSignal,
    val recoveryNeed: AffectiveSignal,
    val certainty: AffectiveSignal,
)

/**
 * ERA 14 §53 — 视觉身份基因组（Day 1 / Day 30 / Day 180 同一个 ECHO）。
 *
 * 由 [deriveIdentityGenome] 从 installation random seed + 长期基线 + 视觉偏好确定性派生；
 * 来源禁止 IMEI / Android ID / 手机号 / 用户名 hash（§54）。
 */
data class EchoIdentityGenome(
    /** 视觉种子（installation random seed）。 */
    val seed: Long = 0L,
    /** 主色相 0..1。 */
    val accentHue: Float = 0f,
    /** 颜色族 0..4。 */
    val colorFamily: Int = 0,
    /** 纹理族 0..3。 */
    val textureFamily: Int = 0,
    /** 核心拓扑 0..1（1 = 高度对称）。 */
    val coreTopology: Float = 0f,
    /** 对称倾向 0..1。 */
    val symmetryTendency: Float = 0f,
    /** 轨道几何 0..1（0 = 环状，1 = 弥散）。 */
    val orbitGeometry: Float = 0f,
    /** 运动人格 0..1（0 = 安静，1 = 活跃）。 */
    val motionPersonality: Float = 0f,
)

/**
 * ERA 14 §56/§57 — 人生阶段视觉层（数周/数月变化）。
 *
 * 只允许中性描述（later rhythm / more fragmented / more variable / less mobile / more regular）；
 * 禁止自动推断医学/心理结论（depressed/anxious/burned out 永不出现在字段与文案）。
 */
data class EchoLifeSeason(
    /** 阶段桶：0 = <7 天基线；1 = 7-30；2 = 30-90；3 = 90+。 */
    val phaseIndex: Int = 0,
    /** 跨日节律漂移幅度 0..1（真实计算，ERA 14 起非 0）。 */
    val drift: Float = 0f,
    /** 节律漂移方向：later / earlier / stable。 */
    val rhythmShift: String = "stable",
    /** 屏幕碎片化：more_fragmented / stable / more_concentrated。 */
    val screenFragmentation: String = "stable",
    /** 活动变异性：more_variable / stable / more_regular。 */
    val activityVariability: String = "stable",
    /** 移动趋势：more_mobile / stable / less_mobile。 */
    val mobilityTrend: String = "stable",
    /** 规律性：more_regular / stable / less_regular。 */
    val regularityTrend: String = "stable",
)

/** 每日构图视觉层（一天级）。 */
data class EchoDailyComposition(
    val flowSpeed: Float = 0f,
    val coherence: Float = 0f,
    val turbulence: Float = 0f,
    val particleDensity: Float = 0f,
    val coreOpenness: Float = 0f,
    val dispersion: Float = 0f,
    val pulsePeriod: Float = 0f,
    val depth: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val accentIntensity: Float = 0f,
    val structureComplexity: Float = 0f,
)

/** 当下调制视觉层（分钟/小时级）。 */
data class EchoMomentState(
    val breathingPeriod: Float = 0f,
    val noiseScale: Float = 0f,
)

/**
 * 全系统唯一当前状态（Master Prompt PART 52）。
 *
 * - 低频更新：分钟级重算；渲染帧率与状态更新率是两个时间尺度。
 * - [publicNarrative] 只允许 PUBLIC_SAFE 内容（锁屏/壁纸）；[privateNarrative] 仅应用内。
 * - [affectiveState] 在 AFFECTIVE_CONTRACT 与 opt-in 完成前必须恒为 null。
 */
data class EchoPresenceState(
    val updatedAt: Instant = Instant.EPOCH,
    val sensingStatus: SensingRuntimeStatus = SensingRuntimeStatus.NOT_AUTHORIZED,
    val maturity: EchoMaturity = EchoMaturity.SEED,
    val rhythmState: RhythmState = RhythmState(),
    val behaviorState: BehaviorState = BehaviorState(),
    val affectiveState: AffectiveState? = null,
    val confidence: Float = 0f,
    val identityGenome: EchoIdentityGenome = EchoIdentityGenome(),
    val lifeSeason: EchoLifeSeason = EchoLifeSeason(),
    val dailyComposition: EchoDailyComposition = EchoDailyComposition(),
    val momentState: EchoMomentState = EchoMomentState(),
    val publicNarrative: String? = null,
    val privateNarrative: String? = null,
)

/**
 * 单一状态存储（进程内 StateFlow 单例语义，由 AppContainer 注入）。
 *
 * ERA 1：内存态 + publish/clear；ERA 2：挂接 AmbientEngine 写入，
 * 并落盘最近一版快照（进程死亡后 Wallpaper 恢复用）。
 */
// ===== Day-0 SEED 文案（单测锚点；UI 层不得另行硬编码） =====

const val PRESENCE_COPY_SEED_TITLE = "初见"
const val PRESENCE_COPY_SEED_BODY = "今天是 ECHO 开始了解你的第一天。"

/**
 * Day-0 存在性状态句（「已观察 N 分钟」，分钟数 clamp ≥ 0）。
 * SEED 是陪伴表达，不是画像输出——绝不伪造个性判断。
 */
fun presenceSeedRuntimeText(observedMinutes: Long): String {
    val minutes = observedMinutes.coerceAtLeast(0L)
    return "正在了解今天的节律 · 已观察 $minutes 分钟"
}
