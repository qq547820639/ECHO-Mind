package com.yunjue.echo.mind.qa
import com.yunjue.echo.mind.journey.JourneyMemoryAssemblyInputs
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.assembleJourneyMemoryState
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.buildSelfModel
import com.yunjue.echo.mind.memory.contextExceptionContent
import com.yunjue.echo.mind.memory.echoKnowsLines
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.RetentionClass
import com.yunjue.echo.mind.visual.surface.EchoSurface

import java.time.LocalDate

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
        /** §41 90 天测试（ERA 32 R06）：production 装配的期间故事（不再是 QA z 镜像）。 */
        val periodStory: String,
        /** §41 90 天测试：现在 vs 一个月前（production compareNowWithMonthAgo 输出）。 */
        val monthAgoLines: List<String>,
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
            me = me(snap, timeline),
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

    /** specialWindows → 生产 contextExceptions 输入（date → kind，仅已开始窗口；T5-P2-9）。 */
    private fun contextExceptionsFor(timeline: QaTimeline, dayIndex: Int): Map<String, String> =
        buildMap {
            for (w in timeline.profile.specialWindows) {
                if (w.fromDay > dayIndex) continue
                for (d in w.fromDay..minOf(w.toDay, dayIndex)) {
                    put(timeline.dateOf(d).toString(), w.label)
                }
            }
        }

    private fun journey(snap: QaDaySnapshot, timeline: QaTimeline): JourneySection {
        val dayIndex = snap.dayIndex
        val contextExceptions = contextExceptionsFor(timeline, dayIndex)
        // §41 地标改走 production buildLandmarks（T5-P2-9：真实算法 = 基线成熟首日/
        // 显著变化/持续特殊上下文/用户确认四类；QA 不再手写「第 N 天」成熟度镜像）
        val landmarks = com.yunjue.echo.mind.journey.buildLandmarks(
            days = com.yunjue.echo.mind.journey.buildJourneyDaysWithOrganism(
                timeline.allPortraitsUpTo(dayIndex),
                identitySeed = snap.identity.seed,
            ),
            contextPeriods = com.yunjue.echo.mind.journey.buildContextPeriods(contextExceptions),
        ).map { "${it.date}：${it.text}" }
        // ERA 32 R06：期间故事 + 现在 vs 一个月前改吃 production 装配
        // （§41 90 天测试同源）——QA 快照不再用自己的 z 分数镜像近似（QA 必须测 Production）。
        // T5-P2-9：specialWindows 以 contextExceptions 真实喂入（河流/periodStory/
        // monthAgo 不再在「无用户上下文例外」的空输入下计算）。
        val memoryState = assembleJourneyMemoryState(
            scale = JourneyScale.DAY,
            timeline = PortraitTimelineUiState(loading = false, portraits = timeline.allPortraitsUpTo(dayIndex)),
            memory = JourneyMemoryAssemblyInputs(contextExceptions = contextExceptions),
        )
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
            periodStory = memoryState.periodStory,
            monthAgoLines = memoryState.monthAgoLines,
            seasonLine = seasonLine,
        )
    }

    private fun me(snap: QaDaySnapshot, timeline: QaTimeline): MeSection {
        // ERA 32 R13：Me 快照改吃 production 装配（buildSelfModel + echoKnowsLines）——
        // 不再用 baseline 指标自造 production 并不产出的「你的工作日通常在…」行
        // （QA 必须测 Production；产品预览不得展示产品不存在的语句）。
        // 时间锚点固定（确定性快照，不受生成时刻影响）。
        val now = LocalDate.parse("2026-08-15").toEpochDay() * 86_400_000L
        val memories = timeline.profile.specialWindows.map { w ->
            EchoMemory(
                id = "ctx_${w.fromDay}_${w.toDay}",
                userId = "qa",
                type = MemoryType.CONTEXT,
                content = contextExceptionContent(w.label, "", timeline.dateOf(w.fromDay).toString()),
                source = "user-stated-context",
                confidence = 1f,
                createdAt = 0L,
                lastConfirmedAt = 0L,
                importance = 70,
                retentionClass = RetentionClass.USER_PINNED,
                provenance = "context-exception:v1",
                deleted = false,
            )
        }
        return MeSection(echoKnowsLines(buildSelfModel(memories, now)))
    }

    private fun wallpaper(snap: QaDaySnapshot): WallpaperSection {
        val lock = snap.lockVisual
        val frame = QaTimeline.computeFrame(snap, EchoSurface.LOCK_PUBLIC_SAFE)
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

    // ERA 32 R06：dimName/valueName 已删除——z 分数镜像退役后无消费方（§53 Delete Review）。
}
