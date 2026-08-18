package com.yunjue.echo.mind.presencevisual

import android.os.PowerManager
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.defaultQualityFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8-P2-8 补缺：EchoRenderEnvironment 降级群纯函数测试（V3 §31 质量门）。
 *
 * qualityFor(powerSave, thermal)：thermal SEVERE+ → MINIMAL；MODERATE → CONSERVE；
 * powerSave → CONSERVE；否则 NORMAL。worseOf 取更保守者（Surface 预算 × 环境实际组合）。
 * 纯逻辑入口，不依赖系统服务（系统服务采集路径由 EchoRenderEnvironmentState TTL 缓存隔离）。
 */
class EchoRenderEnvironmentTest {

    @Test
    fun qualityForFullPowerAndThermalMatrix() {
        assertEquals(EchoRenderQuality.NORMAL, EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_NONE))
        // 轻度热态不降级（< MODERATE）
        assertEquals(EchoRenderQuality.NORMAL, EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_LIGHT))
        // 省电模式 → 至少 CONSERVE
        assertEquals(EchoRenderQuality.CONSERVE, EchoRenderEnvironment.qualityFor(true, PowerManager.THERMAL_STATUS_NONE))
        // 热态 MODERATE → CONSERVE（无省电也降）
        assertEquals(EchoRenderQuality.CONSERVE, EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(EchoRenderQuality.CONSERVE, EchoRenderEnvironment.qualityFor(true, PowerManager.THERMAL_STATUS_MODERATE))
        // 热态 SEVERE+ → MINIMAL（比 powerSave 更差者胜出）
        assertEquals(EchoRenderQuality.MINIMAL, EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(EchoRenderQuality.MINIMAL, EchoRenderEnvironment.qualityFor(true, PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(EchoRenderQuality.MINIMAL, EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_EMERGENCY))
    }

    @Test
    fun worseOfTakesTheMoreConservativeQuality() {
        assertEquals(EchoRenderQuality.CONSERVE, EchoRenderEnvironment.worseOf(EchoRenderQuality.NORMAL, EchoRenderQuality.CONSERVE))
        assertEquals(EchoRenderQuality.CONSERVE, EchoRenderEnvironment.worseOf(EchoRenderQuality.CONSERVE, EchoRenderQuality.NORMAL))
        assertEquals(EchoRenderQuality.MINIMAL, EchoRenderEnvironment.worseOf(EchoRenderQuality.CONSERVE, EchoRenderQuality.MINIMAL))
        assertEquals(EchoRenderQuality.NORMAL, EchoRenderEnvironment.worseOf(EchoRenderQuality.NORMAL, EchoRenderQuality.NORMAL))
        assertEquals(EchoRenderQuality.MINIMAL, EchoRenderEnvironment.worseOf(EchoRenderQuality.MINIMAL, EchoRenderQuality.NORMAL))
    }

    @Test
    fun surfaceBudgetComposedWithEnvironmentNeverUpgrades() {
        // 生产组合形态（EchoRendererFacade L118-120）：base = defaultQualityFor(surface)，
        // 环境降级取 worseOf——结果只会持平或更保守，绝不因环境变好而升级 surface 预算
        val powerSaveQuality = EchoRenderEnvironment.qualityFor(true, PowerManager.THERMAL_STATUS_NONE)
        for (surface in EchoSurface.entries) {
            val composed = EchoRenderEnvironment.worseOf(defaultQualityFor(surface), powerSaveQuality)
            assertTrue(
                "${surface} 组合质量不得优于 surface 默认预算",
                composed.ordinal >= defaultQualityFor(surface).ordinal,
            )
        }
        // 例：腕上 MINIMAL 预算 × 环境正常 → 仍 MINIMAL；APP NORMAL × 省电 → CONSERVE
        assertEquals(
            EchoRenderQuality.MINIMAL,
            EchoRenderEnvironment.worseOf(defaultQualityFor(EchoSurface.WRIST_PUBLIC_SAFE), powerSaveQuality),
        )
        assertEquals(
            EchoRenderQuality.CONSERVE,
            EchoRenderEnvironment.worseOf(defaultQualityFor(EchoSurface.APP_PRIVATE), powerSaveQuality),
        )
    }
}
