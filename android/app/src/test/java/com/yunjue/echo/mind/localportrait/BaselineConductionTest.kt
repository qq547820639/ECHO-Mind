package com.yunjue.echo.mind.localportrait
import com.yunjue.echo.mind.model.echoMaturity
import com.yunjue.echo.mind.model.EchoMaturity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 79（ADR-067 收官）——传导锚点：
 * confidenceFor 与 backend baseline/confidence.py 逐边界镜像；
 * 状态机阶梯（LocalBaselineCalculator.baselineState，输入 = validDays）与
 * 视觉成熟度阶梯（echoMaturity，输入 = 自苏醒起的**日历天数**——ERA 21 后的唯一语义）。
 *
 * T8-P2-2 修复：原版注释/测试名宣讲「基线 validDays → 视觉成熟度传导」（过时心智——
 * 两把钟输入不同，validDays 喂 echoMaturity 是已被 ERA 21 禁止的旧缺陷路径），
 * 且断言含枚举序数比较（编译期常量，永真）。现改为真实界点耦合检查。
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
    fun maturityLadderBoundariesFollowCalendarDaysSinceAwakening() {
        // echoMaturity 阶梯边界（输入 = 日历天数，不是 baseline.validDays）
        assertEquals(EchoMaturity.SEED, echoMaturity(0))
        assertEquals(EchoMaturity.DISCOVERING, echoMaturity(1))
        assertEquals(EchoMaturity.DISCOVERING, echoMaturity(2))
        assertEquals(EchoMaturity.EMERGING, echoMaturity(3))
        assertEquals(EchoMaturity.EMERGING, echoMaturity(6))
        assertEquals(EchoMaturity.KNOWN, echoMaturity(7))
        assertEquals(EchoMaturity.MATURE, echoMaturity(28))
    }

    @Test
    fun baselineReadyBoundaryAlignsWithKnownMaturityBoundary() {
        // 两把钟界点对齐（真实耦合检查，替换原永真的枚举序数比较）：
        // validDays ≥ 7 → BASELINE_READY；日历天数 ≥ 7 → 至少 KNOWN。
        // 同一用户第 7 天起「基线可用」与「视觉已被认识」必须同时成立，之后不再背离。
        assertEquals("BASELINE_READY", LocalBaselineCalculator.baselineState(7))
        assertEquals(EchoMaturity.KNOWN, echoMaturity(7))
        for (d in 0..40) {
            val ready = LocalBaselineCalculator.baselineState(d) == "BASELINE_READY"
            val knownOrBeyond = echoMaturity(d) >= EchoMaturity.KNOWN
            assertTrue(
                "第 $d 天两把钟界点背离：BASELINE_READY=$ready / ≥KNOWN=$knownOrBeyond（均应以 7 为界）",
                ready == knownOrBeyond,
            )
        }
    }
}
