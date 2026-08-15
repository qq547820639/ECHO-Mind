package com.yunjue.echo.mind.localportrait
import com.yunjue.echo.mind.model.echoMaturity
import com.yunjue.echo.mind.model.EchoMaturity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ERA 79（ADR-067 收官）——基线传导锚点：
 * confidenceFor 与 backend baseline/confidence.py 逐边界镜像；
 * 基线 validDays → 状态机 → ECHO 视觉成熟度（echoMaturity）阶梯传导。
 */
class BaselineConductionTest {

    @Test
    fun confidenceForMirrorsBackendBoundaries() {
        // HIGH：coverage >= 0.7 且 validDays >= 7 且无缺失源
        assertEquals("HIGH", LocalPortraitEngine.confidenceFor(0.7, 7, emptyList()))
        assertEquals("HIGH", LocalPortraitEngine.confidenceFor(0.9, 28, emptyList()))
        // coverage 不足 0.7 → MEDIUM（validDays 充足）
        assertEquals("MEDIUM", LocalPortraitEngine.confidenceFor(0.69, 7, emptyList()))
        // 缺失源存在 → HIGH 降级 MEDIUM
        assertEquals("MEDIUM", LocalPortraitEngine.confidenceFor(0.9, 28, listOf("gyro")))
        // MEDIUM：coverage >= 0.3 且 validDays >= 3
        assertEquals("MEDIUM", LocalPortraitEngine.confidenceFor(0.3, 3, listOf("gyro")))
        // 边界以下 → LOW
        assertEquals("LOW", LocalPortraitEngine.confidenceFor(0.29, 3, emptyList()))
        assertEquals("LOW", LocalPortraitEngine.confidenceFor(0.9, 2, emptyList()))
    }

    @Test
    fun validDaysLadderConductsToVisualMaturity() {
        // 状态机阶梯 ↔ 视觉成熟度阶梯（基线是 ECHO 视觉成长的唯一事实基础）
        assertEquals(EchoMaturity.SEED, echoMaturity(0))
        assertEquals(EchoMaturity.DISCOVERING, echoMaturity(1))
        assertEquals(EchoMaturity.DISCOVERING, echoMaturity(2))
        assertEquals(EchoMaturity.EMERGING, echoMaturity(3))
        assertEquals(EchoMaturity.EMERGING, echoMaturity(6))
        assertEquals(EchoMaturity.KNOWN, echoMaturity(7))
        assertEquals(EchoMaturity.MATURE, echoMaturity(28))
        // BASELINE_READY（>=7）↔ 至少 KNOWN：同一天内状态机与视觉成熟度不可能背离
        assertEquals(
            "BASELINE_READY",
            LocalBaselineCalculator.baselineState(7),
        )
        assert(EchoMaturity.KNOWN.ordinal <= EchoMaturity.MATURE.ordinal)
    }
}
