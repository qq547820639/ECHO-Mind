package com.yunjue.echo.mind.memory

/**
 * ERA 15.5 §75/§77 — Memory Maturity 纯函数层。
 *
 * - [rankMemories]：检索排序（§75 优先级：USER_CONFIRMED/CORRECTION/CONTEXT 先于
 *   relevance/recency/confidence/importance）；
 * - [derivePatterns]：派生模式记忆（§77：重复 + 足够 evidence + 稳定 confidence 才成 pattern）。
 */

/** §75 检索排序：类型优先级 × 衰减分 × 重要度（确定性；删除项沉底）。 */
fun rankMemories(memories: List<EchoMemory>, now: Long): List<EchoMemory> =
    memories.sortedWith(
        compareByDescending<EchoMemory> { if (it.deleted) -1 else typePriority(it.type) }
            .thenByDescending { if (it.deleted) -1f else memoryDecayScore(it, now) }
            .thenByDescending { it.importance }
            .thenByDescending { it.lastConfirmedAt }
    )

/** §75 类型优先级（USER_CONFIRMED/CORRECTION/CONTEXT 最高）。 */
fun typePriority(type: MemoryType): Int = when (type) {
    MemoryType.USER_CONFIRMED -> 6
    MemoryType.CORRECTION -> 6
    MemoryType.CONTEXT -> 5
    MemoryType.PREFERENCE -> 4
    MemoryType.DERIVED_PATTERN -> 3
    MemoryType.OBSERVATION -> 2
    MemoryType.TEMPORARY_INTERPRETATION -> 1
}

/** §77 派生模式结果（尚未写入）。 */
data class DerivedPattern(
    val content: String,
    val evidenceCount: Int,
    val confidence: Float,
)

/**
 * §77 — 派生模式记忆：只有「重复 + 足够 evidence（≥ [minOccurrences] 次同内容观察）
 * + 稳定 confidence」才形成长期 pattern。
 *
 * 归一化键 = 内容去空白（同一事实的不同空格写法归并）；空/过短内容不参与。
 */
fun derivePatterns(
    observations: List<EchoMemory>,
    minOccurrences: Int = 3,
): List<DerivedPattern> {
    val grouped = observations
        .filter { !it.deleted && it.type == MemoryType.OBSERVATION }
        .map { it to it.content.trim().replace(Regex("\\s+"), " ") }
        .filter { it.second.length >= 4 }  // 中文事实 4 字即可成 pattern
        .groupBy { it.second }
    return grouped.entries
        .filter { it.value.size >= minOccurrences }
        .map { (content, matches) ->
            val confidence = (0.4f + 0.1f * matches.size).coerceIn(0.5f, 0.95f)
            DerivedPattern(
                content = content,
                evidenceCount = matches.size,
                confidence = confidence,
            )
        }
        .sortedByDescending { it.evidenceCount }
}
