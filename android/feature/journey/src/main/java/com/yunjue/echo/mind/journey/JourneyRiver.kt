package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.presence.EchoVisualParameters
import kotlin.math.sqrt

/**
 * ERA 16 §85 — Visual Memory River。
 *
 * 把一年（或任一窗口）的每日视觉记忆压成若干「河段」：用户一眼能看到
 * 平稳时期 / 密集时期 / 节律漂移 / 特殊阶段 / 长期转变——而不是 365 个点。
 *
 * 全部确定性纯函数：同输入同分段；分段只依赖视觉参数距离与上下文例外日期，
 * 不做任何情绪/健康推断。
 */

/** 河段类型（中性词表；§57 禁医学/心理结论）。 */
enum class RiverSegmentKind { STABLE, DENSE, DRIFT, SPECIAL, TRANSITION }

/** 视觉记忆河流的一段（连续日期 + 分类 + 聚合视觉 + 强度）。 */
data class JourneyRiverSegment(
    val startDate: String,
    val endDate: String,
    val kind: RiverSegmentKind,
    /** 0..1 分类强度（稳定度 / 密集度 / 漂移幅度 / 转变幅度）。 */
    val intensity: Float,
    /** 河段聚合视觉（null = 无画像段，渲染弥散占位）。 */
    val visualParams: EchoVisualParameters?,
    /** 用户可读中性标签。 */
    val label: String,
)

/** 河段类型 → 用户可读标签（中性词汇，UI 与测试共用）。 */
fun riverKindLabel(kind: RiverSegmentKind): String = when (kind) {
    RiverSegmentKind.STABLE -> "平稳时期"
    RiverSegmentKind.DENSE -> "密集时期"
    RiverSegmentKind.DRIFT -> "节律漂移"
    RiverSegmentKind.SPECIAL -> "特殊阶段"
    RiverSegmentKind.TRANSITION -> "长期转变"
}

/**
 * ERA 31 R27：某天所属河段的种类标签（含端点）。
 * 用于把「平稳时期/节律漂移/…」直接标注到主河流的聚合格上——
 * 一条河流同时是时间线（第 N 周）与故事（什么阶段），不再需要第二条河流。
 */
fun journeySegmentKindLabel(date: String, segments: List<JourneyRiverSegment>): String? =
    segments.firstOrNull { date >= it.startDate && date <= it.endDate }?.label

/** 相邻段视为「转变」的视觉距离阈值（12 维归一化欧氏距离）。 */
const val RIVER_TRANSITION_DISTANCE = 0.30f

/** 漂移判定：单步最小移动。 */
const val RIVER_DRIFT_STEP = 0.05f

/** 密集判定：段平均流动速度阈值。 */
const val RIVER_DENSE_ACTIVITY = 0.55f

/** 两视觉参数间的归一化欧氏距离（0..1；12 维逐维差值平方均值开方）。 */
fun visualDistance(a: EchoVisualParameters, b: EchoVisualParameters): Float {
    val diffs = listOf(
        a.flowSpeed - b.flowSpeed,
        a.coherence - b.coherence,
        a.turbulence - b.turbulence,
        a.particleDensity - b.particleDensity,
        a.coreOpenness - b.coreOpenness,
        a.dispersion - b.dispersion,
        a.pulsePeriodSeconds - b.pulsePeriodSeconds,
        a.depth - b.depth,
        a.brightness - b.brightness,
        a.contrast - b.contrast,
        a.accentIntensity - b.accentIntensity,
        a.structureComplexity - b.structureComplexity,
    )
    return sqrt(diffs.map { it * it }.sum() / diffs.size)
}

private data class RiverChunk(
    val startDate: String,
    val endDate: String,
    val params: EchoVisualParameters?,
    val activity: Float,
    val special: Boolean,
)

/**
 * 构建视觉记忆河流（确定性）。
 *
 * @param days 按日期升序的每日视觉记忆单元（可为任意窗口）
 * @param contextExceptions date → kind（用户自述特殊日期；命中即 SPECIAL 段）
 * @param chunkDays 分段粒度（默认 7 天；YEAR 尺度调用方可用 30 天）
 */
fun buildVisualMemoryRiver(
    days: List<JourneyDay>,
    contextExceptions: Map<String, String> = emptyMap(),
    chunkDays: Int = 7,
): List<JourneyRiverSegment> {
    val sorted = days.sortedBy { it.date }
    if (sorted.isEmpty() || chunkDays <= 0) return emptyList()
    val chunks = sorted.chunked(chunkDays).map { chunk ->
        val params = journeyAggregateOfDays(chunk)
        RiverChunk(
            startDate = chunk.first().date,
            endDate = chunk.last().date,
            params = params,
            activity = chunk.mapNotNull { it.visualParams?.flowSpeed }.averageOrNull() ?: 0f,
            special = chunk.any { contextExceptions.containsKey(it.date) },
        )
    }
    if (chunks.isEmpty()) return emptyList()

    // 分类：SPECIAL > TRANSITION > DRIFT > DENSE > STABLE
    val classified = chunks.mapIndexed { index, chunk ->
        val prev = chunks.getOrNull(index - 1)
        val next = chunks.getOrNull(index + 1)
        val prevParams = prev?.params
        val nextParams = next?.params
        val stepIn = if (prevParams != null && chunk.params != null) {
            visualDistance(prevParams, chunk.params)
        } else 0f
        val stepOut = if (chunk.params != null && nextParams != null) {
            visualDistance(chunk.params, nextParams)
        } else 0f
        val kind = when {
            chunk.special -> RiverSegmentKind.SPECIAL
            stepIn >= RIVER_TRANSITION_DISTANCE || stepOut >= RIVER_TRANSITION_DISTANCE ->
                RiverSegmentKind.TRANSITION
            isDriftChain(prev, chunk, next) -> RiverSegmentKind.DRIFT
            chunk.activity >= RIVER_DENSE_ACTIVITY -> RiverSegmentKind.DENSE
            else -> RiverSegmentKind.STABLE
        }
        val intensity = when (kind) {
            RiverSegmentKind.SPECIAL -> 1f
            RiverSegmentKind.TRANSITION -> maxOf(stepIn, stepOut).coerceIn(0f, 1f)
            RiverSegmentKind.DRIFT -> (stepIn + stepOut).coerceIn(0f, 1f)
            RiverSegmentKind.DENSE -> chunk.activity.coerceIn(0f, 1f)
            RiverSegmentKind.STABLE -> (1f - (stepIn + stepOut) / 2f).coerceIn(0f, 1f)
        }
        ClassifiedChunk(chunk = chunk, kind = kind, intensity = intensity)
    }

    // 合并相邻同类型段（TRANSITION 不合并：每次转变单独可见）
    val merged = mutableListOf<ClassifiedChunk>()
    for (item in classified) {
        val last = merged.lastOrNull()
        if (last != null && last.kind == item.kind && item.kind != RiverSegmentKind.TRANSITION) {
            val combined = last.chunk.copy(
                endDate = item.chunk.endDate,
                params = aggregateParams(listOfNotNull(last.chunk.params, item.chunk.params)),
                activity = (last.chunk.activity + item.chunk.activity) / 2f,
                special = last.chunk.special || item.chunk.special,
            )
            merged[merged.size - 1] = ClassifiedChunk(
                chunk = combined,
                kind = item.kind,
                intensity = maxOf(last.intensity, item.intensity),
            )
        } else {
            merged.add(item)
        }
    }

    return merged.map { item ->
        JourneyRiverSegment(
            startDate = item.chunk.startDate,
            endDate = item.chunk.endDate,
            kind = item.kind,
            intensity = item.intensity,
            visualParams = item.chunk.params,
            label = riverKindLabel(item.kind),
        )
    }
}

private data class ClassifiedChunk(
    val chunk: RiverChunk,
    val kind: RiverSegmentKind,
    val intensity: Float,
)

private fun List<Float>.averageOrNull(): Float? = if (isEmpty()) null else average().toFloat()

private fun aggregateParams(params: List<EchoVisualParameters>): EchoVisualParameters? {
    if (params.isEmpty()) return null
    val avg = { f: (EchoVisualParameters) -> Float -> params.map(f).average().toFloat() }
    return EchoVisualParameters(
        flowSpeed = avg { it.flowSpeed },
        coherence = avg { it.coherence },
        turbulence = avg { it.turbulence },
        particleDensity = avg { it.particleDensity },
        coreOpenness = avg { it.coreOpenness },
        dispersion = avg { it.dispersion },
        pulsePeriodSeconds = avg { it.pulsePeriodSeconds },
        depth = avg { it.depth },
        brightness = avg { it.brightness },
        contrast = avg { it.contrast },
        accentIntensity = avg { it.accentIntensity },
        structureComplexity = avg { it.structureComplexity },
    )
}

/**
 * ERA 24 修复 — 漂移链判定：连续两步都在漂移区间**且方向一致**（12 维 delta 点积 > 0）。
 * 旧 isDriftStep 只看单步无符号距离——稳定用户每周聚合的随机晃动（方向来回）
 * 会被误判成 DRIFT（A fixture 实测 5 段中仅 2 段 STABLE）。
 */
private fun isDriftChain(prev: RiverChunk?, chunk: RiverChunk?, next: RiverChunk?): Boolean {
    val pa = prev?.params ?: return false
    val pc = chunk?.params ?: return false
    val pn = next?.params ?: return false
    val d1 = visualDistance(pa, pc)
    val d2 = visualDistance(pc, pn)
    if (d1 < RIVER_DRIFT_STEP || d1 >= RIVER_TRANSITION_DISTANCE) return false
    if (d2 < RIVER_DRIFT_STEP || d2 >= RIVER_TRANSITION_DISTANCE) return false
    // 方向一致性：12 维 delta 点积
    val dims1 = listOf(
        pc.flowSpeed - pa.flowSpeed, pc.coherence - pa.coherence, pc.turbulence - pa.turbulence,
        pc.particleDensity - pa.particleDensity, pc.coreOpenness - pa.coreOpenness,
        pc.dispersion - pa.dispersion, pc.pulsePeriodSeconds - pa.pulsePeriodSeconds,
        pc.depth - pa.depth, pc.brightness - pa.brightness, pc.contrast - pa.contrast,
        pc.accentIntensity - pa.accentIntensity, pc.structureComplexity - pa.structureComplexity,
    )
    val dims2 = listOf(
        pn.flowSpeed - pc.flowSpeed, pn.coherence - pc.coherence, pn.turbulence - pc.turbulence,
        pn.particleDensity - pc.particleDensity, pn.coreOpenness - pc.coreOpenness,
        pn.dispersion - pc.dispersion, pn.pulsePeriodSeconds - pc.pulsePeriodSeconds,
        pn.depth - pc.depth, pn.brightness - pc.brightness, pn.contrast - pc.contrast,
        pn.accentIntensity - pc.accentIntensity, pn.structureComplexity - pc.structureComplexity,
    )
    var dot = 0f
    for (i in dims1.indices) dot += dims1[i] * dims2[i]
    return dot > 0f
}
