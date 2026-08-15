package com.yunjue.echo.mind.memory

/**
 * ERA 23 §33/§34/§35 — Stable Pattern 生命周期（Observation → Candidate → Confirmed，
 * 以及 WEAKENING / CONFLICTING / OUTDATED 矛盾处理）。
 *
 * §33：不是「出现三次就永久真理」——确认只是开始，之后仍持续被新证据、
 * 用户纠正、时间推移重新评估。
 * §34：confidence = f(次数, 时间跨度, 一致性, 上下文例外, 用户纠正, 新鲜度)。
 * §35：新证据/纠正与旧 Pattern 冲突时**标记**（WEAKENING/CONFLICTING/OUTDATED），
 * 绝不静默覆盖；等待更多证据。
 */
enum class PatternState {
    /** 首次观察到（1 次）。 */
    OBSERVED,

    /** 重复观察到（2 次，候选）。 */
    CANDIDATE,

    /** 足够证据确认（≥3 次 + 置信达标）。 */
    CONFIRMED,

    /** 出现矛盾信号（纠正/冲突证据），置信下调，仍保留。 */
    WEAKENING,

    /** 矛盾持续，不再作为可靠模式使用。 */
    CONFLICTING,

    /** 长期无新证据支持且曾有矛盾 → 过期（保留历史，不删除）。 */
    OUTDATED,
}

data class StablePattern(
    /** 归一化键（内容去空白）。 */
    val key: String,
    val content: String,
    val state: PatternState,
    val confidence: Float,
    val occurrenceCount: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** 命中的矛盾信号数（纠正原文包含该模式内容）。 */
    val contradictionCount: Int = 0,
)

data class PatternPromotionConfig(
    val candidateAtOccurrences: Int = 2,
    val confirmAtOccurrences: Int = 3,
    /** 矛盾数达到该值时 → WEAKENING。 */
    val weakenAtContradictions: Int = 1,
    /** 矛盾数达到该值时 → CONFLICTING。 */
    val conflictAtContradictions: Int = 2,
    /** 超过该天数无新证据且曾有矛盾 → OUTDATED。 */
    val outdatedAfterDays: Long = 60,
    /** 时间跨度（天）达到该值时给跨度加分。 */
    val spanBonusDays: Long = 14,
)

/**
 * §33/§34/§35 — 模式晋升与矛盾处理（确定性）。
 *
 * @param observations 全部 OBSERVATION 记忆（含历史）
 * @param existing 上一轮模式状态（首次调用传空列表）
 * @param corrections 用户纠正记忆（矛盾信号源）
 * @param contexts 上下文例外记忆（时间段内的模式置信打折：可能是特殊时期造成的）
 */
fun promotePatterns(
    observations: List<EchoMemory>,
    existing: List<StablePattern>,
    corrections: List<EchoMemory>,
    contexts: List<EchoMemory>,
    now: Long,
    config: PatternPromotionConfig = PatternPromotionConfig(),
): List<StablePattern> {
    val dayMs = 86_400_000L

    // 1. 按归一化键聚合观察
    val grouped = observations
        .filter { !it.deleted && it.type == MemoryType.OBSERVATION }
        .map { it to normalizeKey(it.content) }
        .filter { it.second.length >= 4 }
        .groupBy { it.second }

    // 2. 矛盾信号：纠正原文包含模式内容 → 矛盾（确定性文本包含）
    fun contradictionsOf(content: String): Int = corrections.count { c ->
        !c.deleted && (c.content.contains(content) || content.contains(c.content.take(8)))
    }

    val patterns = grouped.map { (key, matches) ->
        val content = matches.first().first.content.trim().replace(Regex("\\s+"), " ")
        val firstSeen = matches.minOf { it.first.createdAt }
        val lastSeen = matches.maxOf { it.first.lastConfirmedAt.coerceAtLeast(it.first.createdAt) }
        val count = matches.size
        val contradictions = contradictionsOf(content)

        // 时间跨度（天）
        val spanDays = ((lastSeen - firstSeen) / dayMs).coerceAtLeast(0)
        // 一致性：观察置信度的最低值（弱证据拖低模式置信）
        val consistency = matches.minOf { it.first.confidence.coerceIn(0f, 1f) }
        // 新鲜度：距最近一次观察的天数
        val stalenessDays = ((now - lastSeen) / dayMs).coerceAtLeast(0)
        // 上下文例外：特殊时期覆盖证据区间（按日历日粒度）→ 模式可能是特殊时期造成的
        val contextCovered = contexts.any { c ->
            contextExceptionInfo(c.content)?.date?.let { date ->
                runCatching {
                    val day = java.time.LocalDate.parse(date).toEpochDay()
                    val firstDay = firstSeen / dayMs
                    val lastDay = lastSeen / dayMs
                    day in firstDay..lastDay
                }.getOrDefault(false)
            } == true
        }

        // §34 置信合成
        var confidence = (0.35f + 0.12f * count).coerceIn(0f, 0.9f)
        if (spanDays >= config.spanBonusDays) confidence += 0.05f
        confidence = (confidence * (0.5f + 0.5f * consistency)).coerceIn(0f, 0.95f)
        if (contextCovered) confidence *= 0.8f
        confidence = (confidence * (1f - 0.05f * stalenessDays)).coerceIn(0f, 0.95f)
        if (contradictions > 0) confidence = (confidence * 0.5f).coerceIn(0f, 0.95f)

        // 状态机：晋升与矛盾（§33/§35）
        // 顺序语义：长期无支持且有矛盾史 → OUTDATED（最重）；否则按矛盾数标记；
        // 无矛盾时按次数晋升（确认不是永久真理——每轮重估）
        val state = when {
            stalenessDays > config.outdatedAfterDays && contradictions > 0 -> PatternState.OUTDATED
            contradictions >= config.conflictAtContradictions -> PatternState.CONFLICTING
            contradictions >= config.weakenAtContradictions -> PatternState.WEAKENING
            count >= config.confirmAtOccurrences -> PatternState.CONFIRMED
            count >= config.candidateAtOccurrences -> PatternState.CANDIDATE
            else -> PatternState.OBSERVED
        }

        StablePattern(
            key = key,
            content = content,
            state = state,
            confidence = confidence,
            occurrenceCount = count,
            firstSeenAt = firstSeen,
            lastSeenAt = lastSeen,
            contradictionCount = contradictions,
        )
    }

    // 3. 与既有模式的连续性：保留历史矛盾计数（合并旧状态，不静默覆盖 §35）；
    //    OUTDATED 后出现新鲜确认证据 → 复活并清零矛盾史（§35「等待更多证据」的出口）
    val oldByKey = existing.associateBy { it.key }
    return patterns.map { fresh ->
        val old = oldByKey[fresh.key] ?: return@map fresh
        val freshStaleness = ((now - fresh.lastSeenAt) / dayMs).coerceAtLeast(0)
        val revived = old.state == PatternState.OUTDATED &&
            freshStaleness == 0L &&
            fresh.occurrenceCount >= config.confirmAtOccurrences
        val mergedContradictions = if (revived) 0 else maxOf(fresh.contradictionCount, old.contradictionCount)
        val mergedState = when {
            revived -> PatternState.CONFIRMED
            freshStaleness > config.outdatedAfterDays && mergedContradictions > 0 -> PatternState.OUTDATED
            mergedContradictions >= config.conflictAtContradictions -> PatternState.CONFLICTING
            mergedContradictions >= config.weakenAtContradictions -> PatternState.WEAKENING
            fresh.state.ordinal >= old.state.ordinal -> fresh.state
            else -> old.state
        }
        fresh.copy(contradictionCount = mergedContradictions, state = mergedState)
    }.sortedWith(
        compareByDescending<StablePattern> { it.state == PatternState.CONFIRMED }
            .thenByDescending { it.confidence }
            .thenByDescending { it.occurrenceCount },
    )
}

/** 归一化键（§77 同源：去空白归并）。 */
fun normalizeKey(content: String): String = content.trim().replace(Regex("\\s+"), " ")
