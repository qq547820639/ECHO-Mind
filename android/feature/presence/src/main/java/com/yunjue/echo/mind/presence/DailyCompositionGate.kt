package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoDailyComposition

import java.time.LocalDate

/**
 * ERA 21 §18 — Daily Composition 日级稳定门。
 *
 * 生产侧 PresenceRepository 为分钟级 refresh：today 聚合随窗口累积变化，
 * 若每次 refresh 都用当日 Ambient 向量重算 Daily Composition，核心构图会
 * 在一天内不断漂移——违反 §18「一天内核心构图不能不断变化」。
 *
 * 本门保证：同一 LocalDate 只计算一次日构图（当日首次 refresh 固化）；
 * Moment 层不受影响（分钟级呼吸由 buildMomentState 继续消费当日向量）。
 */
class DailyCompositionGate {

    private var forDate: LocalDate? = null
    private var cached: EchoDailyComposition? = null

    /** 该日期是否已固化（日期须显式传入：跨日后缓存中的昨日记录不再误报「今日已固化」）。 */
    fun hasCompositionFor(date: LocalDate): Boolean = cached != null && forDate == date

    /** 该日构图（同日恒返回同一实例；跨日才重算）。 */
    fun compositionFor(date: LocalDate, compute: () -> EchoDailyComposition): EchoDailyComposition {
        val existing = cached
        if (forDate == date && existing != null) return existing
        val fresh = compute()
        forDate = date
        cached = fresh
        return fresh
    }
}
