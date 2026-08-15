package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.qa.QaPortraitMirror.minuteText
import kotlin.math.abs

/**
 * ERA 20 §9 — Headline Engine（三层区分）：
 *
 * - Public headline：一句状态表达（ECHO 口吻，克制）；
 * - Evidence：数字对照（10:14 · 通常 09:21）；
 * - AI layer：只有真正带来增量理解才出现（最近是否反复出现），否则为 null（Restraint）。
 *
 * 确定性：同一天同一 profile 恒同文案；不调用任何 Provider。
 */
data class QaHeadline(
    val public: String,
    val evidence: String,
    val aiLayer: String?,
)

object QaHeadlineEngine {

    fun headlineFor(snap: QaDaySnapshot, timeline: QaTimeline): QaHeadline {
        val portrait = snap.portrait
        if (portrait == null || portrait.dimensions.none { it.key != "STABILITY" }) {
            return learningPhase(snap)
        }
        val diffDims = portrait.dimensions.filter { it.value.value != "SIMILAR" && it.key != "STABILITY" }
        if (diffDims.isEmpty()) {
            return QaHeadline(
                public = "今天和你的节奏很接近。",
                evidence = "与通常的差异很小（${portrait.dimensionValue("STABILITY")}）",
                aiLayer = null,
            )
        }
        // 最强维度（|z| 最大）驱动一句话
        val top = diffDims.maxByOrNull { abs(it.value.z ?: 0.0) }!!
        val public = publicVoice(top.key, top.value.value)
        val evidence = evidenceLine(top.key, snap, timeline)
        val aiLayer = aiLayerFor(top.key, top.value.value, snap, timeline)
        return QaHeadline(public = public, evidence = evidence, aiLayer = aiLayer)
    }

    /** 学习期文案（ERA 20 §10 + ERA 31 与 production learningPhaseHeadline 逐字对齐）：绝不展示「数据不足」。 */
    private fun learningPhase(snap: QaDaySnapshot): QaHeadline {
        val observedMinutes = snap.aggregate.validWindowCount * 5L
        return when (snap.presence.maturity) {
            EchoMaturity.SEED -> QaHeadline(
                public = "初见。",
                evidence = "正在了解今天的节律 · 已观察 $observedMinutes 分钟",
                aiLayer = null,
            )
            EchoMaturity.DISCOVERING, EchoMaturity.EMERGING -> QaHeadline(
                public = "我开始看到一些属于你的节奏。",
                evidence = "已观察 $observedMinutes 分钟 · 基线第 ${snap.baseline?.validDays ?: 0} 天",
                aiLayer = null,
            )
            EchoMaturity.KNOWN -> QaHeadline(
                public = "我开始认识通常的你了。",
                evidence = "已观察 $observedMinutes 分钟",
                aiLayer = null,
            )
            EchoMaturity.MATURE -> QaHeadline(
                public = "ECHO 还在了解今天。",
                evidence = "已观察 $observedMinutes 分钟",
                aiLayer = null,
            )
        }
    }

    /** 公共一句话：口语、克制、不喊口号。 */
    private fun publicVoice(dim: String, value: String): String = when (dim to value) {
        "RHYTHM" to "LATER" -> "今天开始得比通常慢一些。"
        "RHYTHM" to "EARLIER" -> "今天开始得比平时早。"
        "MOVEMENT" to "MORE" -> "今天动得比平时多一些。"
        "MOVEMENT" to "LESS" -> "今天动得比平时少一些。"
        "SCREEN_AMOUNT" to "MORE" -> "今天屏幕时间比平时长。"
        "SCREEN_AMOUNT" to "LESS" -> "今天屏幕时间比平时短。"
        "SCREEN_TIMING" to "LATER" -> "今晚比平时晚一些才安静下来。"
        "SCREEN_TIMING" to "EARLIER" -> "今晚比平时更早安静下来。"
        "DAY_STRUCTURE" to "MORE_FRAGMENTED" -> "今天比平时零散一些。"
        "DAY_STRUCTURE" to "MORE_CONCENTRATED" -> "今天比平时集中。"
        else -> "今天和你的节奏很接近。"
    }

    private fun evidenceLine(dim: String, snap: QaDaySnapshot, timeline: QaTimeline): String {
        val baseline = snap.baseline ?: return "基线尚未形成"
        val agg = snap.aggregate
        return when (dim) {
            "RHYTHM" -> {
                val today = agg.activeStartMinute?.toDouble()?.let(::minuteText) ?: "—"
                val usual = baseline.metrics["active_start_minute"]?.median?.let(::minuteText) ?: "—"
                "$today · 通常 $usual"
            }
            "MOVEMENT" -> {
                val today = agg.movementIndex?.let { "%.2f".format(it) } ?: "—"
                val usual = baseline.metrics["movement_index"]?.median?.let { "%.2f".format(it) } ?: "—"
                "活动量 $today · 通常 $usual"
            }
            "SCREEN_AMOUNT" -> {
                val usual = baseline.metrics["screen_on_minutes"]?.median?.let { "${it.toInt()} 分钟" } ?: "—"
                "${agg.screenOnMinutes.toInt()} 分钟 · 通常 $usual"
            }
            "SCREEN_TIMING" -> {
                val usual = baseline.metrics["late_screen_minutes"]?.median?.let { "${it.toInt()} 分钟" } ?: "—"
                "晚间屏幕 ${agg.lateScreenMinutes.toInt()} 分钟 · 通常 $usual"
            }
            "DAY_STRUCTURE" -> {
                val today = agg.activeHourSpread?.let { "${(it * 100).toInt()}%" } ?: "—"
                val usual = baseline.metrics["active_hour_spread"]?.median?.let { "${(it * 100).toInt()}%" } ?: "—"
                "活跃时段占比 $today · 通常 $usual"
            }
            else -> ""
        }
    }

    /**
     * AI layer（克制）：只有过去 7 天里同方向变化 ≥2 次才说「反复出现」；
     * 罕见变化说「在留意」；否则 null（不加戏）。
     */
    private fun aiLayerFor(dim: String, value: String, snap: QaDaySnapshot, timeline: QaTimeline): String? {
        val d = snap.dayIndex
        if (d < 7) return null
        val sameDirection = (d - 6..d).count { day ->
            val p = timeline.portraitFor(day) ?: return@count false
            p.dimensionValue(dim) == value
        }
        return when {
            sameDirection >= 3 -> "这种变化最近几个工作日也出现过。"
            sameDirection == 2 -> "这种变化最近也出现过。"
            sameDirection == 1 -> "最近不太常见，我在留意。"
            else -> null
        }
    }
}
