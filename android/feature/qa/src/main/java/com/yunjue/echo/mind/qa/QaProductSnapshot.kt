package com.yunjue.echo.mind.qa
import com.yunjue.echo.mind.model.EchoMaturity

import com.yunjue.echo.mind.presence.SurfaceMode
import kotlin.math.abs

/**
 * ERA 19 §4 — 完整产品快照（一个用户在某一天的所见）。
 *
 * 六面：ECHO Scene / Why / Journey / Me / Wallpaper / Dream + Ask ECHO 示例回答。
 * 全部确定性；供开发者阅读（markdown 报告）与测试断言共用。
 */
object QaProductSnapshot {

    data class SceneSection(
        val headline: QaHeadline,
        val maturity: EchoMaturity,
        val maturityName: String,
        val ambientState: String,
        val visual: String,
    )

    data class WhySection(val facts: List<String>, val sourceCount: Int)

    data class JourneySection(
        val calendarDays: Int,
        val baselineDays: Int,
        val landmarks: List<String>,
        val recentChanges: List<String>,
        val seasonLine: String,
    )

    data class MeSection(val knows: List<String>)

    data class WallpaperSection(
        val flowSpeed: Float,
        val coherence: Float,
        val turbulence: Float,
        val pulsePeriod: Float,
        val particleCount: Int,
        val lockSafeNote: String,
    )

    data class DreamSection(val line: String)

    data class ProductSnapshot(
        val profileId: String,
        val dayIndex: Int,
        val date: String,
        val scene: SceneSection,
        val why: WhySection,
        val journey: JourneySection,
        val me: MeSection,
        val wallpaper: WallpaperSection,
        val dream: DreamSection,
        val askEcho: List<QaAskEcho.QaAnswer>,
    )

    fun snapshot(timeline: QaTimeline, dayIndex: Int): ProductSnapshot {
        val snap = timeline.snapshotAt(dayIndex)
        val profile = timeline.profile
        return ProductSnapshot(
            profileId = profile.id,
            dayIndex = dayIndex,
            date = snap.date.toString(),
            scene = scene(snap, timeline),
            why = why(snap),
            journey = journey(snap, timeline),
            me = me(snap),
            wallpaper = wallpaper(snap),
            dream = dream(snap),
            askEcho = QaAskEcho.CANONICAL_QUESTIONS.map { QaAskEcho.answer(timeline, dayIndex, it) },
        )
    }

    private fun scene(snap: QaDaySnapshot, timeline: QaTimeline): SceneSection {
        val headline = QaHeadlineEngine.headlineFor(snap, timeline)
        val params = snap.appVisual
        return SceneSection(
            headline = headline,
            maturity = snap.presence.maturity,
            maturityName = snap.presence.maturity.name,
            ambientState = snap.ambient.state.name,
            visual = "flow=${"%.2f".format(params.flowSpeed)} coherence=${"%.2f".format(params.coherence)} " +
                "openness=${"%.2f".format(params.coreOpenness)} turbulence=${"%.2f".format(params.turbulence)} " +
                "pulse=${"%.1f".format(params.pulsePeriodSeconds)}s",
        )
    }

    private fun why(snap: QaDaySnapshot): WhySection {
        val facts = snap.portrait?.facts?.map {
            "${it.label}：今天 ${it.todayText} · 通常 ${it.baselineText}（${it.deltaText}）"
        } ?: emptyList()
        return WhySection(facts = facts.take(4), sourceCount = facts.size)
    }

    private fun journey(snap: QaDaySnapshot, timeline: QaTimeline): JourneySection {
        val dayIndex = snap.dayIndex
        val landmarks = mutableListOf<String>()
        // 里程碑：成熟度跃迁（日历语义）
        for (d in listOf(3, 7, 28, 90)) {
            if (dayIndex >= d) {
                val name = when {
                    d == 3 -> "第 4 天：开始看到你的节奏（EMERGING）"
                    d == 7 -> "第 8 天：开始认识通常的你（KNOWN）"
                    d == 28 -> "第 29 天：ECHO 成熟（MATURE）"
                    else -> "第 91 天：长期阶段（phase 3）"
                }
                landmarks += name
            }
        }
        // 上下文窗口（出差/冲刺）
        for (w in timeline.profile.specialWindows) {
            if (w.toDay <= dayIndex) {
                landmarks += "${timeline.dateOf(w.fromDay)}~${timeline.dateOf(w.toDay)}：${w.label}"
            }
        }
        // 最近 30 天最显著的两个变化日（|z| 最大）
        val from = (dayIndex - 29).coerceAtLeast(0)
        val changes = (from..dayIndex).mapNotNull { d ->
            val p = timeline.portraitFor(d) ?: return@mapNotNull null
            val top = p.dimensions.filter { it.key != "STABILITY" }
                .maxByOrNull { abs(it.value.z ?: 0.0) }
            if (top == null || abs(top.value.z ?: 0.0) <= 0.7) return@mapNotNull null
            Triple(d, top.key, top.value.value)
        }.sortedByDescending { d -> timeline.portraitFor(d.first)?.dimensions?.get(d.second)?.z?.let { abs(it) } ?: 0.0 }
            .take(2)
            .map { (d, dim, value) ->
                // ERA 31 R40：镜像与 production 同口径——变化日证据说人话，
                // 不泄露 z 分数（R25 已把生产引擎证据人话化，此处同步）。
                "${timeline.dateOf(d)}：${dimName(dim)}${valueName(value)}"
            }
        val season = snap.season
        val seasonLine = when {
            season.rhythmShift == "later" -> "这段时间的整体节奏在逐渐后移。"
            season.rhythmShift == "earlier" -> "这段时间的整体节奏在逐渐前移。"
            else -> "整体节奏保持稳定。"
        }
        return JourneySection(
            calendarDays = dayIndex,
            baselineDays = snap.baseline?.validDays ?: 0,
            landmarks = landmarks,
            recentChanges = changes,
            seasonLine = seasonLine,
        )
    }

    private fun me(snap: QaDaySnapshot): MeSection {
        val knows = mutableListOf<String>()
        val baseline = snap.baseline
        if (baseline != null && baseline.validDays >= 2) {
            baseline.metrics["active_start_minute"]?.median?.let {
                knows += "你的工作日通常在 ${QaPortraitMirror.minuteText(it)} 左右明显开始。"
            }
            baseline.metrics["active_end_minute"]?.median?.let {
                knows += "工作日晚间通常在 ${QaPortraitMirror.minuteText(it)} 前后安静下来。"
            }
            baseline.metrics["screen_on_minutes"]?.median?.let {
                knows += "你平时每天屏幕约 ${it.toInt()} 分钟。"
            }
        }
        if (snap.dayIndex >= 7) {
            // 周末对比由 Ask ECHO 层回答（需要跨日聚合统计）
        }
        knows += "ECHO 认识你 ${snap.dayIndex} 天了。"
        return MeSection(knows)
    }

    private fun wallpaper(snap: QaDaySnapshot): WallpaperSection {
        val lock = snap.lockVisual
        val frame = QaTimeline.computeFrame(snap, SurfaceMode.LOCK_SAFE)
        return WallpaperSection(
            flowSpeed = lock.flowSpeed,
            coherence = lock.coherence,
            turbulence = lock.turbulence,
            pulsePeriod = lock.pulsePeriodSeconds,
            particleCount = frame.particles.size,
            lockSafeNote = "锁屏只表达 Presence：无心理推断、无习惯异常、无具体事件。",
        )
    }

    private fun dream(snap: QaDaySnapshot): DreamSection =
        DreamSection(line = "${snap.date} · ECHO")

    private fun dimName(dim: String): String = when (dim) {
        "RHYTHM" -> "活跃起点"
        "MOVEMENT" -> "活动量"
        "SCREEN_AMOUNT" -> "屏幕时间"
        "SCREEN_TIMING" -> "晚间屏幕"
        "DAY_STRUCTURE" -> "一天结构"
        else -> dim
    }

    private fun valueName(value: String): String = when (value) {
        "LATER" -> "后移"
        "EARLIER" -> "前移"
        "MORE" -> "增加"
        "LESS" -> "减少"
        "MORE_FRAGMENTED" -> "更零散"
        "MORE_CONCENTRATED" -> "更集中"
        else -> value
    }
}
