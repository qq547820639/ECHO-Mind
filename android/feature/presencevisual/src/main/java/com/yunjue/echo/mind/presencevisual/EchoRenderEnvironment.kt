package com.yunjue.echo.mind.presencevisual

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.yunjue.echo.mind.visual.render.DeviceRenderCapabilities
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.render.selectTier

/**
 * EchoRenderEnvironment — V3 §9/§23/§31 设备渲染环境解析（Android 侧唯一事实源）。
 *
 * - tier：API 级别 + RuntimeShader 实测可用性 + ULTRA 硬门（avp2025/benchmark/flag 默认否
 *   → ULTRA 永不自动启用，§97 ULTRA_DISABLED_BY_CAPABILITY 是允许的成功态）。
 * - quality：Power Save → 至少 CONSERVE；Thermal MODERATE → CONSERVE；SEVERE+ → MINIMAL。
 * - HDR：API≥34 且 display 链路支持且非省电且热态 < MODERATE（Wallpaper 另由 surface 门关闭）。
 */
object EchoRenderEnvironment {

    fun resolveTier(
        ultraFlag: Boolean = false,
        ultraBenchmarkPassed: Boolean = false,
        avp2025: Boolean = false,
    ): EchoRenderTier = selectTier(
        DeviceRenderCapabilities(
            api = Build.VERSION.SDK_INT,
            runtimeShader = AgslEchoBackend.isAvailable(),
            avp2025 = avp2025,
            ultraBenchmarkPassed = ultraBenchmarkPassed,
            ultraFlag = ultraFlag,
        ),
    )

    /** §31 质量门（纯函数，可测）。 */
    fun qualityFor(powerSave: Boolean, thermalStatus: Int): EchoRenderQuality = when {
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> EchoRenderQuality.MINIMAL
        thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> EchoRenderQuality.CONSERVE
        powerSave -> EchoRenderQuality.CONSERVE
        else -> EchoRenderQuality.NORMAL
    }

    /** 当前热态（API 29+；低版本/读不到按 NONE——不伪造热态）。 */
    fun currentThermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return PowerManager.THERMAL_STATUS_NONE
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } catch (_: Throwable) {
            PowerManager.THERMAL_STATUS_NONE
        }
    }

    fun isPowerSave(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    } catch (_: Throwable) {
        false
    }

    fun currentQuality(context: Context): EchoRenderQuality =
        qualityFor(isPowerSave(context), currentThermalStatus(context))

    /**
     * §23 HDR 硬门（Wallpaper 的关闭由 surface 侧另行保证——packet.material.hdrAllowed）。
     * display 链路真实支持 = 宽色域且（API 34+）；不在此伪造能力。
     */
    fun isHdrEligible(context: Context, quality: EchoRenderQuality): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        if (quality != EchoRenderQuality.NORMAL) return false // 省电/热态 → HDR OFF
        return try {
            val display = context.display
            display?.isWideColorGamut == true
        } catch (_: Throwable) {
            false
        }
    }
}
