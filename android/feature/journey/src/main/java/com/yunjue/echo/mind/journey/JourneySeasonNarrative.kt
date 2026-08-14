package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.presence.EchoLifeSeason
import com.yunjue.echo.mind.presence.EchoVisualParameters

/**
 * ERA 16 §87 — Life Season × Journey。
 *
 * Journey 能解释「为什么某阶段 ECHO 的视觉慢慢变化」：
 * - [changedVisualAspects]：比较前后视觉参数 → 变化维度（纯数值比较，无推断）；
 * - [explainVisualAspects]：变化维度 → 中性中文解释；
 * - [explainLifeSeasonVisual]：Life Season 趋势标签 → 视觉变化解释（§57 词表，
 *   禁止医学/心理结论）；
 * - [explainPeriodChange]：一段时期前后视觉对比 → 完整解释行。
 */

/** 单个视觉参数变化超过阈值才计入「变化维度」。 */
const val ASPECT_CHANGE_THRESHOLD = 0.12f

/** 视觉参数 → 中性维度名（解释文案键）。 */
fun changedVisualAspects(
    before: EchoVisualParameters?,
    after: EchoVisualParameters?,
    threshold: Float = ASPECT_CHANGE_THRESHOLD,
): List<String> {
    if (before == null || after == null) return emptyList()
    val changed = mutableListOf<String>()
    fun add(key: String, diff: Float) {
        if (kotlin.math.abs(diff) >= threshold) changed.add(key)
    }
    add("flow", after.flowSpeed - before.flowSpeed)
    add("coherence", after.coherence - before.coherence)
    add("turbulence", after.turbulence - before.turbulence)
    add("density", after.particleDensity - before.particleDensity)
    add("openness", after.coreOpenness - before.coreOpenness)
    add("dispersion", after.dispersion - before.dispersion)
    add("pulse", after.pulsePeriodSeconds - before.pulsePeriodSeconds)
    add("depth", after.depth - before.depth)
    add("brightness", after.brightness - before.brightness)
    add("contrast", after.contrast - before.contrast)
    add("accent", after.accentIntensity - before.accentIntensity)
    add("complexity", after.structureComplexity - before.structureComplexity)
    return changed
}

/**
 * 变化维度 → 用户可读解释行（方向由 [direction] 决定：+1 = 参数上升，-1 = 下降）。
 * 全部中性词汇：描述视觉事实，不解释情绪、不推断健康。
 */
fun explainVisualAspect(aspect: String, direction: Int): String {
    val up = direction >= 0
    return when (aspect) {
        "flow" -> if (up) "流动速度上升" else "流动速度下降"
        "coherence" -> if (up) "形态更凝聚" else "形态更弥散"
        "turbulence" -> if (up) "湍流增强" else "湍流减弱"
        "density" -> if (up) "粒子更密集" else "粒子更稀疏"
        "openness" -> if (up) "核心更开放" else "核心更收敛"
        "dispersion" -> if (up) "分布更扩散" else "分布更集中"
        "pulse" -> if (up) "呼吸更慢" else "呼吸更快"
        "depth" -> if (up) "层次更深" else "层次更浅"
        "brightness" -> if (up) "更明亮" else "更沉静"
        "contrast" -> if (up) "对比更强烈" else "对比更柔和"
        "accent" -> if (up) "强调色更突出" else "强调色更收敛"
        "complexity" -> if (up) "结构更复杂" else "结构更简洁"
        else -> "视觉变化"
    }
}

/**
 * §87 — Life Season 趋势 → 「为什么 ECHO 的视觉在这个阶段慢慢变化」的中性解释。
 *
 * 只翻译已计算的趋势标签（later rhythm / more fragmented / less mobile…）；
 * drift 阈值触发「缓慢变化」总述。禁止出现医学/心理结论词（§57，测试强制）。
 */
fun explainLifeSeasonVisual(season: EchoLifeSeason): List<String> {
    val lines = mutableListOf<String>()
    when (season.rhythmShift) {
        "later" -> lines.add("这个阶段的活跃起点整体更晚，ECHO 的流动变得更慢、更深。")
        "earlier" -> lines.add("这个阶段的活跃起点整体更早，ECHO 的流动更早开始。")
    }
    when (season.screenFragmentation) {
        "more_fragmented" -> lines.add("屏幕使用更碎片化，ECHO 的粒子更分散。")
        "more_concentrated" -> lines.add("屏幕使用更集中，ECHO 的粒子更凝聚。")
    }
    when (season.activityVariability) {
        "more_variable" -> lines.add("活动变化更大，ECHO 的形态更多变。")
        "more_regular" -> lines.add("生活更规律，ECHO 的构图更稳定。")
    }
    when (season.mobilityTrend) {
        "less_mobile" -> lines.add("移动变少，ECHO 的运动更收敛。")
        "more_mobile" -> lines.add("移动更多，ECHO 的运动更舒展。")
    }
    when (season.regularityTrend) {
        "less_regular" -> lines.add("规律性降低，ECHO 的构图波动更多。")
        "more_regular" -> lines.add("规律性提高，ECHO 的构图更稳定。")
    }
    if (season.drift >= 0.35f) {
        lines.add("这段时间你的节奏在缓慢变化，ECHO 的视觉也在慢慢跟随。")
    }
    return lines
}

/**
 * §87 — 一段时期前后视觉对比解释（用于长期转变点/选中日期）：
 * 返回「日期：维度1，维度2…」风格的中性解释行；无显著变化 → null。
 */
fun explainPeriodChange(
    before: EchoVisualParameters?,
    after: EchoVisualParameters?,
    beforeDate: String?,
    afterDate: String?,
): List<String> {
    if (before == null || after == null) return emptyList()
    val aspects = changedVisualAspects(before, after)
    if (aspects.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    for (aspect in aspects) {
        val direction = aspectDirection(aspect, before, after)
        lines.add(explainVisualAspect(aspect, direction))
    }
    val range = listOfNotNull(beforeDate, afterDate).takeIf { it.size == 2 }?.joinToString(" → ")
    return if (range != null) listOf("$range：${lines.joinToString("，")}") else lines
}

private fun aspectDirection(aspect: String, before: EchoVisualParameters, after: EchoVisualParameters): Int {
    val diff: Float = when (aspect) {
        "flow" -> after.flowSpeed - before.flowSpeed
        "coherence" -> after.coherence - before.coherence
        "turbulence" -> after.turbulence - before.turbulence
        "density" -> after.particleDensity - before.particleDensity
        "openness" -> after.coreOpenness - before.coreOpenness
        "dispersion" -> after.dispersion - before.dispersion
        "pulse" -> after.pulsePeriodSeconds - before.pulsePeriodSeconds
        "depth" -> after.depth - before.depth
        "brightness" -> after.brightness - before.brightness
        "contrast" -> after.contrast - before.contrast
        "accent" -> after.accentIntensity - before.accentIntensity
        "complexity" -> after.structureComplexity - before.structureComplexity
        else -> 0f
    }
    return if (diff >= 0f) 1 else -1
}
