package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.visual.motion.MotionEvaluator
import com.yunjue.echo.mind.visual.render.EchoInteractionSpec
import com.yunjue.echo.mind.visual.render.EchoMotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3 §25–§29 — MotionEvaluator 测试：
 * 运动是时间的纯函数（全 Surface 共享同一时间基准）；呼吸/轨道/相位窗口；
 * 交互包络时间线；expApproach 参数趋近。
 */
class MotionEvaluatorTest {

    private fun motion(
        breathPeriod: Float = 8.4f,
        breathAmp: Float = 0.024f,
        orbitPeriod: Float = 2400f,
        filamentPeriod: Float = 40f,
    ) = EchoMotionSpec(
        breathPeriodSeconds = breathPeriod,
        breathAmplitude = breathAmp,
        brightnessPulse = 0.03f,
        orbitPeriodSeconds = orbitPeriod,
        filamentPhaseSeconds = filamentPeriod,
        particleVelocity = 1f,
        orbitVelocity = 1f,
        filamentPhaseScale = 1f,
    )

    @Test
    fun motionIsPureFunctionOfTime() {
        val a = MotionEvaluator.evaluate(motion(), 12.5f)
        val b = MotionEvaluator.evaluate(motion(), 12.5f)
        assertEquals(a, b)
    }

    @Test
    fun breathIsSlowAndBounded() {
        val m = motion()
        // b(t) 全周期采样：breathScale 在 1±amplitude 内（不能像 loading spinner）
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0..100) {
            val s = MotionEvaluator.evaluate(m, i / 100f * m.breathPeriodSeconds).breathScale
            min = minOf(min, s)
            max = maxOf(max, s)
        }
        assertTrue(min >= 1f - m.breathAmplitude - 1e-4f)
        assertTrue(max <= 1f + m.breathAmplitude + 1e-4f)
    }

    @Test
    fun orbitIsExtremelySlow() {
        val m = motion(orbitPeriod = 2400f) // 40 分钟一圈
        val r0 = MotionEvaluator.evaluate(m, 0f).globalRotation
        val r60 = MotionEvaluator.evaluate(m, 60f).globalRotation
        // 60 秒只走 2π/40 rad —— 无快速旋转魔法球感（§28）
        assertTrue(kotlin.math.abs(r60 - r0) < 0.2f)
    }

    @Test
    fun interactionEnvelopeTimeline() {
        // §29：0–80 capture(0) → 80–180 rise → 180–600 peak/decay → 600–1150 return → 0
        assertEquals(0f, MotionEvaluator.interactionEnvelope(40L), 1e-4f)
        assertEquals(0.5f, MotionEvaluator.interactionEnvelope(130L), 1e-4f)
        assertEquals(1f, MotionEvaluator.interactionEnvelope(180L), 1e-4f)
        assertTrue(MotionEvaluator.interactionEnvelope(400L) in 0.6f..1.0f)
        assertTrue(MotionEvaluator.interactionEnvelope(900L) in 0.05f..0.65f)
        assertEquals(0f, MotionEvaluator.interactionEnvelope(1200L), 1e-4f)
    }

    @Test
    fun interactionEnvelopeComesFromSpecNotState() {
        val m = motion()
        val idle = MotionEvaluator.evaluate(m, 5f)
        assertEquals(0f, idle.interactionEnvelope, 1e-4f)
        val touching = MotionEvaluator.evaluate(
            m, 5f, EchoInteractionSpec(active = true, touchX = 0.5f, touchY = 0.4f, envelope = 0.8f),
        )
        assertEquals(0.8f, touching.interactionEnvelope, 1e-4f)
    }

    @Test
    fun expApproachConverges() {
        // t = tau 时约 63% 收敛；t=3tau 时约 95%
        val atTau = MotionEvaluator.expApproach(0f, 1f, 2.8f, 2.8f)
        assertEquals(0.632f, atTau, 0.01f)
        val late = MotionEvaluator.expApproach(0f, 1f, 16.5f, 5.5f)
        assertTrue(late > 0.94f)
        assertEquals(1f, MotionEvaluator.expApproach(1f, 1f, 0f, 2.8f), 1e-4f)
    }

    @Test
    fun longNanosPhaseStaysContinuousAtHugeUptime() {
        // §N P1-2：400 天 uptime（3.456e16 ns）下 Float 秒 ulp≈4s——相邻 16ms 帧差被完全吞掉；
        // Long-nanos 求值先 mod 周期再转 Float，相邻帧 breathScale 差保持理论值（16ms @ 8.4s × 2.4% ≈ 2.9e-4）
        val m = motion()
        val t400d = 400L * 24 * 3600 * 1_000_000_000L
        val a = MotionEvaluator.evaluate(m, t400d)
        val b = MotionEvaluator.evaluate(m, t400d + 16_000_000L)
        assertTrue("相邻 16ms 帧差异应为正且平滑", b.breathScale != a.breathScale)
        assertTrue(
            "相邻 16ms 帧差异 < 1e-3（实际 ${kotlin.math.abs(b.breathScale - a.breathScale)}）",
            kotlin.math.abs(b.breathScale - a.breathScale) < 1e-3f,
        )
        // 反例锁定：Float 绝对秒在同 uptime 下 16ms 差异被 ulp 吞掉（修复动机实证）
        val fa = MotionEvaluator.evaluate(m, t400d / 1_000_000_000f)
        val fb = MotionEvaluator.evaluate(m, (t400d + 16_000_000L) / 1_000_000_000f)
        assertEquals("相位 Float 秒下 16ms 帧差被 ulp 吞掉", fa, fb)
    }

    @Test
    fun longNanosMatchesPhaseReducedSmallT() {
        // 确定性：大 t 与（mod 周期后的）小 t 计算结果一致——同相位同状态
        val m = motion()
        val periodNanos = (m.breathPeriodSeconds * 1_000_000_000f).toLong()
        val t400d = 400L * 24 * 3600 * 1_000_000_000L
        val big = MotionEvaluator.evaluate(m, t400d)
        val small = MotionEvaluator.evaluate(m, t400d % periodNanos)
        assertEquals(small.breathScale, big.breathScale, 1e-6f)
        assertEquals(small.haloMultiplier, big.haloMultiplier, 1e-6f)
    }

    @Test
    fun correctionPulseAgeNanosDifferenceSurvivesHugeUptime() {
        // §45 P1-2：脉冲年龄 = Long nanos 差值（Float 大数相减 catastrophic cancellation
        // 会把 0.9s 脉冲量化到不可用）；ms 量化（Long 除法）精确无损
        val pulseStart = 400L * 24 * 3600 * 1_000_000_000L // ~400 天 uptime 时触发
        val age450ms = pulseStart + 450_000_000L - pulseStart // Long 差值精确 = 450ms
        assertEquals(450L, age450ms / 1_000_000L)
        val (haloDelta, _) = MotionEvaluator.correctionPulse(age450ms / 1_000_000L)
        assertEquals("450ms 处 halo 脉冲仍呈现峰值", -0.08f, haloDelta, 0.005f)
        // 反例锁定：Float 秒差值在该 uptime 下 ulp≈4s，450ms 无法表示
        val floatAge = pulseStart + 450_000_000L / 1_000_000_000f - pulseStart / 1_000_000_000f
        assertTrue("Float 差值无法表示 450ms（实际 $floatAge）", kotlin.math.abs(floatAge - 0.45f) > 0.05f)
    }

    @Test
    fun correctionPulseTimeline() {
        // §45：~900ms；halo -8% 单峰；phase 前 150ms 暂停、之后平滑收敛
        val (h0, p0) = MotionEvaluator.correctionPulse(0L)
        assertEquals(0f, h0, 1e-4f)
        assertEquals(0f, p0, 1e-4f)
        val (hMid, _) = MotionEvaluator.correctionPulse(450L)
        assertEquals(-0.08f, hMid, 0.005f) // 峰值附近约 -8%
        val (_, pEarly) = MotionEvaluator.correctionPulse(100L)
        assertEquals(0.10f, pEarly, 1e-3f) // 前 150ms 全停
        val (_, pLate) = MotionEvaluator.correctionPulse(800L)
        assertTrue("后段收敛", pLate < 0.05f)
        val (hEnd, pEnd) = MotionEvaluator.correctionPulse(950L)
        assertEquals(0f, hEnd, 1e-4f)
        assertEquals(0f, pEnd, 1e-4f)
    }
}
