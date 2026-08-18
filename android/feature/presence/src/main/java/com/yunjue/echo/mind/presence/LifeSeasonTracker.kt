package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoLifeSeason

import java.time.LocalDate
import kotlin.math.min

/**
 * ERA 21 §16/§17 — Life Season 稳定性层（慢变化状态机）。
 *
 * 直接消费 [computeLifeSeason] 的原始输出，保证：
 * - §16 一天异常不改变 Season：新签名必须**连续确认** [confirmationDays] 个新日期
 *   才会提交（minimum persistence / hysteresis）；中途回弹即重置。
 * - §17 变化不是瞬切：提交后进入 transition，drift 在 [TRANSITION_DAYS] 天内
 *   从旧值平滑过渡到新值；[isTransitioning] 期间 UI 可表达「正在变化」。
 * - 稳定期 drift 每日最多向新值走近 [DRIFT_FOLLOW_ALPHA]（drift-only 变化同样缓慢）。
 * - confidence = 一致性占比 × 数据充分度（早期低、持续一致才高）。
 *
 * 日级语义：同一 LocalDate 多次喂入只更新候选值、不累计天数
 * （生产侧 PresenceRepository 为分钟级 refresh，必须按日去重）。
 */
data class LifeSeasonTracker(
    /** 已提交目标 Season（transition 的目的地 / 稳定期的当前值）。 */
    val target: EchoLifeSeason,
    /** transition 前的旧 Season（稳定期恒为 null）。 */
    val from: EchoLifeSeason? = null,
    /** 0..1；1 = 已完全到达 target（稳定）。 */
    val transitionProgress: Float = 1f,
    /** 稳定期 drift 平滑跟随值（每日最多走近 [DRIFT_FOLLOW_ALPHA]）。 */
    val smoothDrift: Float = 0f,
    /** 与 target 签名不同的待确认候选。 */
    val candidate: EchoLifeSeason? = null,
    /** 候选在**新日期**上连续出现的天数。 */
    val candidateStreak: Int = 0,
    /** §16 minimum persistence：连续确认天数阈值。 */
    val confirmationDays: Int = DEFAULT_CONFIRMATION_DAYS,
    /** 已处理的新日期总数（数据天数）。 */
    val computedDays: Int = 1,
    /** 自上次提交以来签名与 target 一致的新日期数。 */
    val agreeDays: Int = 1,
    /** 上次提交至今的新日期数。 */
    val daysSinceCommit: Int = 0,
    /** 最近一次喂入的日期（同日去重）。 */
    val lastDate: LocalDate? = null,
) {
    companion object {
        const val DEFAULT_CONFIRMATION_DAYS = 3
        const val TRANSITION_DAYS = 5

        /** 稳定期 drift 每日跟随系数（0..1）：一天最多走近 40%。 */
        const val DRIFT_FOLLOW_ALPHA = 0.4f

        /** 数据充分度饱和天数：小于此值时 confidence 按比例折减。 */
        const val DATA_CONFIDENCE_DAYS = 21

        /** 首个 Season 直接采纳（无历史可比较）。 */
        fun start(first: EchoLifeSeason, date: LocalDate? = null): LifeSeasonTracker =
            LifeSeasonTracker(target = first, smoothDrift = first.drift, lastDate = date)
    }

    val isTransitioning: Boolean get() = transitionProgress < 1f

    /**
     * 置信度（0..1）= 一致性占比 × 数据充分度。
     * - 一致性占比：agreeDays / 自上次提交以来的新日期数（候选日计入分母、不计入分子；
     *   刚提交 = 0，持续一致 → 1）；
     * - 数据充分度：computedDays / [DATA_CONFIDENCE_DAYS]（数据少时不虚高）。
     */
    val confidence: Float
        get() {
            val agreement = (agreeDays.toFloat() / daysSinceCommit.coerceAtLeast(1)).coerceIn(0f, 1f)
            val sufficiency = (computedDays.toFloat() / DATA_CONFIDENCE_DAYS).coerceIn(0f, 1f)
            return agreement * sufficiency
        }

    /**
     * 对外生效的 Season：分类字段取 target；drift 的取值规则：
     * - transition 期间：from.drift → target.drift 按 progress 线性插值（§17 数天过渡）；
     * - 稳定期：smoothDrift（每日最多 40% 跟随，drift 不瞬跳）。
     */
    val effective: EchoLifeSeason
        get() {
            val drift = if (isTransitioning && from != null) {
                from.drift + (target.drift - from.drift) * transitionProgress.coerceIn(0f, 1f)
            } else {
                smoothDrift
            }
            return target.copy(drift = drift)
        }

    /** 当日喂入（同日可重复调用：只更新候选，不累计天数）。 */
    fun update(fresh: EchoLifeSeason, date: LocalDate): LifeSeasonTracker {
        val newDay = date != lastDate
        val base = copy(lastDate = date, computedDays = computedDays + if (newDay) 1 else 0)
        val sig = fresh.seasonSignature()
        return if (sig == base.target.seasonSignature()) {
            // 与已提交目标一致：重置候选；transition 按天推进；drift 平滑跟随
            val progress = if (newDay && base.transitionProgress < 1f) {
                min(1f, base.transitionProgress + 1f / TRANSITION_DAYS)
            } else base.transitionProgress
            val followed = if (newDay) {
                base.smoothDrift + (fresh.drift - base.smoothDrift) * DRIFT_FOLLOW_ALPHA
            } else base.smoothDrift
            // transition 完成当日：平滑值直接落到目标（线性插值最后一步）
            val settled = if (progress >= 1f && base.transitionProgress < 1f) target.drift else followed
            base.copy(
                candidate = null,
                candidateStreak = 0,
                agreeDays = base.agreeDays + if (newDay) 1 else 0,
                daysSinceCommit = base.daysSinceCommit + if (newDay) 1 else 0,
                transitionProgress = progress,
                smoothDrift = settled,
            )
        } else {
            // 与 target 不同：按「与 target 的差异字段」判定候选持久性。
            // 次要字段（activityVariability 等）会逐日抖动——要求**全签名**连续一致
            // 会让任何真实变化永远无法提交。正确语义：候选创建时的差异字段
            // 保持同一方向即可（其余字段抖动不重置）。
            val candidateDelta = base.candidate?.let { deltaFieldsOf(base.target, it) }.orEmpty()
            val sameCandidate = candidateDelta.isNotEmpty() &&
                candidateDelta.all { fieldValue(base.candidate!!, it) == fieldValue(fresh, it) }
            val streak = if (newDay) {
                if (sameCandidate) base.candidateStreak + 1 else 1
            } else {
                base.candidateStreak.coerceAtLeast(1)
            }
            if (newDay && streak >= base.confirmationDays) {
                // 提交：从当前生效值出发，向新目标过渡
                base.copy(
                    from = base.effective,
                    target = fresh,
                    candidate = null,
                    candidateStreak = 0,
                    transitionProgress = 0f,
                    agreeDays = 0,
                    daysSinceCommit = 0,
                )
            } else {
                base.copy(
                    candidate = if (sameCandidate) base.candidate else fresh,
                    candidateStreak = streak,
                    // 候选日同样计入 daysSinceCommit（字段 KDoc「上次提交至今的新日期数」）：
                    // 与 target 不一致的天数进入 confidence 分母（agreeDays 不动 → 一致性
                    // 占比如实下降），连续多日候选漂移不再造成 confidence 虚高。
                    daysSinceCommit = base.daysSinceCommit + if (newDay) 1 else 0,
                )
            }
        }
    }
}

/** Season 分类签名（决定「是不是同一个 Season」；drift 不参与签名）。 */
internal fun EchoLifeSeason.seasonSignature(): List<Any> = listOf(
    phaseIndex, rhythmShift, screenFragmentation, activityVariability, mobilityTrend, regularityTrend,
)

/** 两 Season 之间取值不同的分类字段名集合。 */
private fun deltaFieldsOf(a: EchoLifeSeason, b: EchoLifeSeason): Set<String> =
    SEASON_FIELDS.filter { fieldValue(a, it) != fieldValue(b, it) }.toSet()

private fun fieldValue(season: EchoLifeSeason, field: String): Any = when (field) {
    "phaseIndex" -> season.phaseIndex
    "rhythmShift" -> season.rhythmShift
    "screenFragmentation" -> season.screenFragmentation
    "activityVariability" -> season.activityVariability
    "mobilityTrend" -> season.mobilityTrend
    "regularityTrend" -> season.regularityTrend
    else -> ""
}

private val SEASON_FIELDS = listOf(
    "phaseIndex", "rhythmShift", "screenFragmentation", "activityVariability", "mobilityTrend", "regularityTrend",
)
