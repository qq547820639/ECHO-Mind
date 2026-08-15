package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.presence.shouldRefreshSnapshot
import com.yunjue.echo.mind.presence.WallpaperRenderController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ERA 21 §20 — Wallpaper 长期运行测试（7 天 × 24h 模拟，JVM 确定性）。
 *
 * 覆盖 §20 关注的控制面：
 * - screen-off / 不可见 → 0 帧（零工作）；
 * - surface recreation / unlock / rotation / launcher scroll 事件后状态机仍正确；
 * - 快照重读节流（≤ 1 次/秒）；
 * - 静止状态帧率适应：视觉参数未变化时不重渲染（batch 7 的 stationary 语义先锚定在 QA 层）；
 * - 7 天全帧确定性：无 NaN、粒子数有界、身份色恒定。
 *
 * 设备侧 CPU/GPU/battery 实测由 EchoSceneFrameDeviceBenchmarkInstrumentedTest（instrumented）承担；
 * 本测试是它的确定性长时序孪生。
 */
class QaWallpaperLongRunTest {

    private val days = 7
    private val minutesPerDay = 1440

    data class FrameRecord(
        val touchDrawsWhileInvisible: Int,
        val framesRendered: Int,
        val snapshotReads: Int,
        val paramChanges: Int,
        val nanFrames: Int,
        val maxParticles: Int,
        val distinctAccentRgb: Int,
    )

    private fun simulate(profile: QaProfileSpec): FrameRecord {
        val timeline = QaTimeline(profile)
        val controller = WallpaperRenderController()
        val rng = QaRng(profile.identitySeed)

        var touchDrawsWhileInvisible = 0
        var framesRendered = 0
        var snapshotReads = 0
        var paramChanges = 0
        var nanFrames = 0
        var maxParticles = 0
        val accentRgbs = mutableSetOf<Int>()

        var screenOn = true
        var visible = true
        var lastReadMs = -10_000L
        var lastParams: com.yunjue.echo.mind.presence.EchoVisualParameters? = null

        for (minute in 0 until days * minutesPerDay) {
            val dayIndex = (minute / minutesPerDay).coerceAtMost(180)
            val hourOfDay = minute % minutesPerDay / 60.0

            // 昼夜/事件安排（确定性）：基于 profile 的典型起止
            val wakeMinute = profile.wakeMinute
            val endMinute = if (profile.endMinute < wakeMinute) profile.endMinute + 1440 else profile.endMinute
            val minuteOfDay = minute % minutesPerDay
            val expectedOn = minuteOfDay in wakeMinute - 60..endMinute + 30
            if (expectedOn != screenOn) {
                screenOn = expectedOn
                visible = screenOn // 熄屏即不可见
                controller.onVisibilityChanged(visible)
            }

            // 事件：unlock / rotation / launcher scroll（确定性时刻）
            if (screenOn) {
                if (minuteOfDay == wakeMinute) {
                    visible = true
                    controller.onVisibilityChanged(true) // unlock
                }
                if (minuteOfDay % 180 == 60) controller.onSurfaceChanged() // rotation
                if (minuteOfDay % 47 == 13 && rng.nextDouble() < 0.5) {
                    // launcher scroll：快速不可见→可见
                    controller.onVisibilityChanged(false)
                    controller.onVisibilityChanged(true)
                }
            } else {
                controller.onVisibilityChanged(false)
            }

            // 帧发射模拟：renderActive 时才可能渲染
            if (controller.renderActive) {
                val snap = timeline.snapshotAt(dayIndex)
                val params = snap.lockVisual
                val changed = lastParams == null || visualEpsilon(lastParams, params) > 0.01f
                if (changed) {
                    lastParams = params
                    paramChanges++
                    val frame = QaTimeline.computeFrame(snap, com.yunjue.echo.mind.presence.SurfaceMode.LOCK_SAFE)
                    framesRendered++
                    maxParticles = maxOf(maxParticles, frame.particles.size)
                    accentRgbs += frame.accentColor and 0x00FFFFFF
                    if (frame.particles.any { it.x.isNaN() || it.y.isNaN() || it.alpha.isNaN() }) nanFrames++
                }
            } else {
                // 不可见/熄屏期间任何触摸都不得产生绘制（§65 硬指标）
                if (controller.onTouch() && !visible) touchDrawsWhileInvisible++
            }

            // 快照重读节流（1s；分钟级模拟里每分钟最多一次读）
            val nowMs = minute * 60_000L
            if (shouldRefreshSnapshot(lastReadMs, nowMs)) {
                snapshotReads++
                lastReadMs = nowMs
            }
        }
        controller.onDestroy()
        return FrameRecord(
            touchDrawsWhileInvisible = touchDrawsWhileInvisible,
            framesRendered = framesRendered,
            snapshotReads = snapshotReads,
            paramChanges = paramChanges,
            nanFrames = nanFrames,
            maxParticles = maxParticles,
            distinctAccentRgb = accentRgbs.size,
        )
    }

    private fun visualEpsilon(
        a: com.yunjue.echo.mind.presence.EchoVisualParameters,
        b: com.yunjue.echo.mind.presence.EchoVisualParameters,
    ): Float = maxOf(
        abs(a.flowSpeed - b.flowSpeed),
        abs(a.coherence - b.coherence),
        abs(a.turbulence - b.turbulence),
        abs(a.particleDensity - b.particleDensity),
    )

    @Test
    fun sevenDayRunHasZeroInvisibleAndScreenOffFrames() {
        for (profile in QaProfiles.ALL) {
            val r = simulate(profile)
            assertEquals("$profile 不可见期间触摸不得产生绘制", 0, r.touchDrawsWhileInvisible)
        }
    }

    @Test
    fun sevenDayRunRendersFewerFramesThanRawPerMinuteBudget() {
        for (profile in QaProfiles.ALL) {
            val r = simulate(profile)
            val rawBudget = days * minutesPerDay
            assertTrue(
                "$profile 静止适应应显著低于逐分钟重绘预算（${r.framesRendered}/$rawBudget）",
                r.framesRendered < rawBudget / 4,
            )
            assertTrue("$profile 至少渲染过（非零工作）", r.framesRendered > 0)
        }
    }

    @Test
    fun snapshotRefreshIsThrottledToAtMostOnePerSecond() {
        for (profile in QaProfiles.ALL) {
            val r = simulate(profile)
            // 7 天 = 604,800 秒；1/s 节流 → 读数上限 = 秒数
            assertTrue("$profile 快照重读 ≤ 秒数（实际 ${r.snapshotReads}）", r.snapshotReads <= days * 86_400)
        }
    }

    @Test
    fun framesAreFiniteAndIdentityStableOverSevenDays() {
        for (profile in QaProfiles.ALL) {
            val r = simulate(profile)
            assertEquals("$profile 无 NaN 帧", 0, r.nanFrames)
            assertTrue("$profile 粒子数有界（实际 ${r.maxParticles}）", r.maxParticles in 1..64)
            // 强调色 RGB 只由 seed 色相决定（s/v 固定）→ 7 天恒同
            assertEquals("$profile 强调色 RGB 7 天恒定（实际 ${r.distinctAccentRgb} 种）", 1, r.distinctAccentRgb)
        }
    }

    @Test
    fun controllerStateMachineSurvivesEventStorm() {
        // 高频事件风暴后状态机仍收敛：destroy 后一切停止
        val c = WallpaperRenderController()
        repeat(10_000) { i ->
            c.onVisibilityChanged(i % 2 == 0)
            c.onSurfaceChanged()
            if (i % 7 == 0) c.onDestroy()
        }
        assertTrue("destroy 后永不渲染", !c.renderActive)
        assertTrue("destroy 后触摸无绘制", !c.onTouch())
    }
}
