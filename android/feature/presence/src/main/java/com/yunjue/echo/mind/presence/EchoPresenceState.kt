package com.yunjue.echo.mind.presence

import com.yunjue.echo.mind.sensing.SensingRuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * 数据驱动的确定性映射（不依赖时间锚点，复用既有 baseline_days）：
 * - 0 天：SEED（Day 0，必有画报，但不伪造观察）
 * - 1-2 天：DISCOVERING（出现真实观察，不与 baseline 比较）
 * - 3-6 天：EMERGING（tentative patterns，与后端 EARLY_BASELINE 同构）
 * - 7-27 天：KNOWN（baseline 成熟，「今天 vs 通常的你」）
 * - ≥28 天：MATURE（完整基线窗口，「这是我的 ECHO」）
 */
enum class EchoMaturity { SEED, DISCOVERING, EMERGING, KNOWN, MATURE }

/** 成熟度纯函数：baseline 有效天数 → 阶段（负数按 0 处理）。 */
fun echoMaturity(baselineDays: Int): EchoMaturity = when {
    baselineDays >= 28 -> EchoMaturity.MATURE
    baselineDays >= 7 -> EchoMaturity.KNOWN
    baselineDays >= 3 -> EchoMaturity.EMERGING
    baselineDays >= 1 -> EchoMaturity.DISCOVERING
    else -> EchoMaturity.SEED
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

/** 视觉身份基因组（数月/长期稳定；ERA 2 用 installationSeed + baseline digest 填充）。 */
data class EchoIdentityGenome(
    val seed: Long = 0L,
    val accentHue: Float = 0f,
)

/** 人生阶段视觉层（数周/数月；ERA 2+ 填充）。 */
data class EchoLifeSeason(
    val phaseIndex: Int = 0,
    val drift: Float = 0f,
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
