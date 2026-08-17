package com.yunjue.echo.mind.presencevisual

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.yunjue.echo.mind.visual.render.DeviceRenderCapabilities
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.render.selectTier
import kotlinx.coroutines.delay

/**
 * EchoRenderEnvironment — V3 §9/§23/§31 设备渲染环境解析（Android 侧唯一事实源，纯函数）。
 *
 * - tier：API 级别 + RuntimeShader 实测可用性 + ULTRA 硬门（avp2025/benchmark/flag 默认否
 *   → ULTRA 永不自动启用，§97 ULTRA_DISABLED_BY_CAPABILITY 是允许的成功态）。
 * - quality：Power Save → 至少 CONSERVE；Thermal MODERATE → CONSERVE；SEVERE+ → MINIMAL。
 * - HDR（§T 诚实原则）：**生产恒 false**——见 [isHdrEligible] 的 BLOCKED 说明。
 *
 * 带缓存的环境快照入口在 [EchoRenderEnvironmentState]（进程级 5s TTL；
 * 任何渲染路径都不得逐帧查询系统服务）。
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

    /**
     * V3 §M：取两者中更保守的质量（ordinal 越大越保守：NORMAL < CONSERVE < MINIMAL）。
     * Surface 默认预算（defaultQualityFor）与环境实际质量（power/thermal）组合时取更差者。
     */
    fun worseOf(a: EchoRenderQuality, b: EchoRenderQuality): EchoRenderQuality =
        if (a.ordinal >= b.ordinal) a else b

    fun isPowerSave(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
    } catch (_: Throwable) {
        false
    }

    fun currentQuality(context: Context): EchoRenderQuality =
        qualityFor(isPowerSave(context), currentThermalStatus(context))

    /**
     * §23/§T HDR 诚实门——**生产恒 false（BLOCKED）**。
     *
     * BLOCKED 原因：此前用 `Display.isWideColorGamut` 冒充 HDR 能力属于伪造——WCG ≠ HDR。
     * 真实 HDR 能力需要：Display.HdrCapabilities 支持类型 + 窗口 headroom 协商 +
     * power/thermal 资格 + 真机视觉验证。以上能力就绪前 pipeline 绝不声称 HDR
     * （§23：不在此伪造能力；宽色域由 [EchoEnvironmentSnapshot.wideGamut] 独立跟踪，
     * 不用于 HDR 判定）。
     */
    fun isHdrEligible(context: Context, quality: EchoRenderQuality): Boolean {
        if (quality != EchoRenderQuality.NORMAL) return false // 降级态语义保留（当前恒 false）
        return false // BLOCKED：真实 Display.HdrCapabilities 能力存在前不启用（§T）
    }

    /** 宽色域独立跟踪（API 34+ display 链路；只作观测，不参与 HDR 判定，§T）。 */
    fun isWideColorGamut(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return try {
            context.display?.isWideColorGamut == true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * V3 §U/§BG — 设备渲染环境快照（一次采集、多处消费的廉价值对象）。
 */
data class EchoEnvironmentSnapshot(
    val quality: EchoRenderQuality,
    val powerSave: Boolean,
    val thermalSevereOrWorse: Boolean,
    val tier: EchoRenderTier,
    /** §T：HDR 生产恒 false（真实 HdrCapabilities 能力存在前 BLOCKED）。 */
    val hdrEligible: Boolean = false,
    /** 宽色域独立观测（不用于 HDR 判定）。 */
    val wideGamut: Boolean = false,
    /** RuntimeShader（AGSL）可用性（进程级缓存值）。 */
    val runtimeShader: Boolean = false,
)

/**
 * EchoRenderEnvironmentState — V3 §U 进程级缓存的环境快照（5s TTL）。
 *
 * 热态/省电/RuntimeShader 探测都是系统服务调用或着色器编译，
 * 绝不进入帧路径：所有消费者经 [current]（TTL 内零系统调用）或
 * [rememberEchoEnvironment]（Compose 轮询 5s）读取。
 */
object EchoRenderEnvironmentState {

    private const val SNAPSHOT_TTL_MS = 5_000L

    @Volatile
    private var cached: EchoEnvironmentSnapshot? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    /** 当前环境快照（TTL 内直接返回缓存；过期才重新采集系统服务）。 */
    fun current(context: Context): EchoEnvironmentSnapshot {
        val now = SystemClock.elapsedRealtime()
        val snapshot = cached
        if (snapshot != null && now - cachedAtMs in 0 until SNAPSHOT_TTL_MS) return snapshot
        return collect(context).also {
            cached = it
            cachedAtMs = now
        }
    }

    private fun collect(context: Context): EchoEnvironmentSnapshot {
        val powerSave = EchoRenderEnvironment.isPowerSave(context)
        val thermal = EchoRenderEnvironment.currentThermalStatus(context)
        return EchoEnvironmentSnapshot(
            quality = EchoRenderEnvironment.qualityFor(powerSave, thermal),
            powerSave = powerSave,
            thermalSevereOrWorse = thermal >= PowerManager.THERMAL_STATUS_SEVERE,
            tier = EchoRenderEnvironment.resolveTier(),
            hdrEligible = EchoRenderEnvironment.isHdrEligible(
                context,
                EchoRenderEnvironment.qualityFor(powerSave, thermal),
            ),
            wideGamut = EchoRenderEnvironment.isWideColorGamut(context),
            runtimeShader = AgslEchoBackend.isAvailable(),
        )
    }
}

/**
 * V3 §U/§BG — Compose 环境快照（compose 期间每 5s 轮询刷新，
 * quality/tier 变化对 UI 可观察；不逐帧查询系统服务）。
 */
@Composable
fun rememberEchoEnvironment(context: Context): State<EchoEnvironmentSnapshot> =
    produceState(EchoRenderEnvironmentState.current(context), context) {
        while (true) {
            delay(5_000L)
            value = EchoRenderEnvironmentState.current(context)
        }
    }
