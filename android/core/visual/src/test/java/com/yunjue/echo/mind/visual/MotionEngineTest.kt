package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.visual.motion.MotionEngine
import com.yunjue.echo.mind.visual.motion.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MotionEngine 测试（ECHO_SURFACE_POLICY §四）：
 * - 确定性相位；
 * - Reduced Motion 非静态（保留低频呼吸 + 轻微亮度漂移，但大幅降粒子/轨道）；
 * - 帧钟与 presence 更新分离（本引擎只读 clock，不推理状态）。
 */
class MotionEngineTest {

    @Test
    fun phaseIsDeterministic() {
        val a = MotionEngine.phaseAt(10f, 4f, 0.5f, MotionPolicy.FULL)
        val b = MotionEngine.phaseAt(10f, 4f, 0.5f, MotionPolicy.FULL)
        assertEquals(a, b)
    }

    @Test
    fun reducedMotionIsNotStatic() {
        val t0 = MotionEngine.phaseAt(0f, 4f, 0.5f, MotionPolicy.REDUCED_MOTION)
        val t2 = MotionEngine.phaseAt(2f, 4f, 0.5f, MotionPolicy.REDUCED_MOTION)
        // 呼吸仍随时间变化（非静态截图）
        assertTrue("Reduced Motion 呼吸仍应变化", t0.breathing != t2.breathing)
        // 亮度漂移仍存在
        assertTrue(t0.luminanceDrift != t2.luminanceDrift)
    }

    @Test
    fun reducedMotionSuppressesMovementButKeepsBreathing() {
        val full = MotionEngine.phaseAt(3f, 4f, 0.8f, MotionPolicy.FULL)
        val reduced = MotionEngine.phaseAt(3f, 4f, 0.8f, MotionPolicy.REDUCED_MOTION)
        // 粒子/轨道/漂移大幅降低
        assertTrue(Math.abs(reduced.particleMigration) < Math.abs(full.particleMigration) + 1e-6f)
        assertTrue(Math.abs(reduced.orbital) < Math.abs(full.orbital) + 1e-6f)
        assertTrue(Math.abs(reduced.drift) <= Math.abs(full.drift) + 1e-6f)
        // 呼吸保留（幅度减半但非零）
        assertTrue(Math.abs(reduced.breathing) > 0f)
    }

    @Test
    fun lowPowerReducesButDoesNotEliminate() {
        val full = MotionEngine.phaseAt(3f, 4f, 0.8f, MotionPolicy.FULL)
        val low = MotionEngine.phaseAt(3f, 4f, 0.8f, MotionPolicy.LOW_POWER)
        assertTrue(Math.abs(low.particleMigration) < Math.abs(full.particleMigration) + 1e-6f)
        assertTrue(Math.abs(low.particleMigration) > Math.abs(
            MotionEngine.phaseAt(3f, 4f, 0.8f, MotionPolicy.REDUCED_MOTION).particleMigration) - 1e-6f)
    }
}
