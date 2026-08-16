package com.yunjue.echo.mind.visual.render

import com.yunjue.echo.mind.visual.math.DeterministicRandom
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OrganismTopology — V3 §14–§18 稳定拓扑（identity 级缓存）。
 *
 * 三层结构（§14）：
 *  A. Structural Rings（约 20%：少量稳定，表达 identity skeleton）
 *  B. Long Filaments（约 45%：跨半球、3D orientation、不是每条完整圆）
 *  C. Local Filament Fragments（约 35%：短局部生命纹理，不大量穿过中心）
 *  + Fibonacci 球粒子基位置（§17）+ 稳定 core knots（§18）。
 *
 * 缓存纪律（§32）：拓扑只随 identity / quality / maturity / TOPOLOGY_VERSION 重建；
 * Moment 更新永不重建；render hot path 不分配新拓扑。
 */

/** 3D 向量（拓扑构建期使用；求值期投影到屏幕）。 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun normalized(): Vec3 {
        val len = sqrt(x * x + y * y + z * z)
        return if (len > 1e-6f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, 1f)
    }
}

/** filament 稳定平面基（u/v 张成平面，normal 为深度轴）。 */
data class FilamentPlane(val u: Vec3, val v: Vec3, val normal: Vec3)

/** 结构环（identity skeleton；稳定倾角 + lobe 谐波）。 */
data class StructuralRingTopo(
    val plane: FilamentPlane,
    val radiusRatio: Float,
    val lobeHarmonicAmp: Float,
    val phase: Float,
)

/** 长丝（跨半球弧；arcLength < 2π —— 不是完整圆）。 */
data class LongFilamentTopo(
    val plane: FilamentPlane,
    val arcStart: Float,
    val arcLength: Float,
    val baseRadiusRatio: Float,
    val freqOffset: Int,
    val phase: Float,
    val depthWarpAmp: Float,
)

/** 局部碎片（短弧；圆心偏置在壳层上，不穿过中心）。 */
data class LocalFragmentTopo(
    val center: Vec3,
    val plane: FilamentPlane,
    val arcStart: Float,
    val arcLength: Float,
    val radiusRatio: Float,
    val phase: Float,
)

/** 粒子基位置（Fibonacci 球方向 + 稳定壳层半径 + 分类）。 */
data class ParticleBase(
    val dir: Vec3,
    val shellRadius: Float,
    val kind: ParticleKind,
    val warm: Boolean,
    val sizeJitter: Float,
)

enum class ParticleKind { AMBIENT, BRIGHT, GLINT }

/** 核心结（hollow core 内的稳定亮点；不得每次启动换位置）。 */
data class CoreKnotTopo(
    val offset: Vec3,
    val radiusRatio: Float,
    val warm: Boolean,
)

/** 完整稳定拓扑。 */
data class OrganismTopology(
    val rings: List<StructuralRingTopo>,
    val longFilaments: List<LongFilamentTopo>,
    val fragments: List<LocalFragmentTopo>,
    val particles: List<ParticleBase>,
    val coreKnots: List<CoreKnotTopo>,
    val version: Int,
)

object OrganismTopologyBuilder {

    /** 拓扑结构版本（算法变更 → 缓存自然失效；golden 需显式审核）。 */
    const val TOPOLOGY_VERSION = 3

    private const val GOLDEN_ANGLE = 2.39996323f
    private const val MAX_PARTICLES = 220
    private const val MAX_CACHE = 8

    private data class TopoKey(
        val seed: Long,
        val quality: EchoRenderQuality,
        val maturityName: String,
        val version: Int,
    )

    private val cache = ConcurrentHashMap<TopoKey, OrganismTopology>()

    /** 获取（或确定性重建）稳定拓扑。Moment/Daily 更新永不触发重建。 */
    fun topologyFor(
        identity: EchoIdentitySpec,
        quality: EchoRenderQuality,
        maturityName: String,
    ): OrganismTopology {
        val key = TopoKey(identity.identitySeed, quality, maturityName, TOPOLOGY_VERSION)
        return cache.getOrPut(key) {
            if (cache.size > MAX_CACHE) cache.clear()
            build(identity, quality, maturityName)
        }
    }

    /** 测试钩子：清空缓存。 */
    fun clearCacheForTest() = cache.clear()

    private fun build(
        identity: EchoIdentitySpec,
        quality: EchoRenderQuality,
        maturityName: String,
    ): OrganismTopology {
        val seed = identity.identitySeed
        val profile = EchoSceneCompiler.qualityProfile(quality)
        val maturity = EchoSceneCompiler.maturityMultiplier(maturityName)
        val filScale = profile.filamentScale * maturity

        // ---- A. Structural Rings（~20%；2..4 个，倾角来自 identity） ----
        val ringCount = (2 + identity.lobeCount / 2).coerceIn(2, 4) // lobe 2..5 → 3..4
        val rings = ArrayList<StructuralRingTopo>(ringCount)
        for (i in 0 until ringCount) {
            val tilt = if (i == 0) {
                identity.primaryTilt
            } else {
                identity.secondaryTilt * (0.6f + 0.4f * DeterministicRandom.at(seed, 300 + i))
            }
            val roll = DeterministicRandom.range(seed, 310 + i, 0f, TWO_PI)
            rings += StructuralRingTopo(
                plane = planeFromTiltRoll(tilt, roll),
                radiusRatio = 0.62f + 0.042f * i + identity.orbitalBias * 0.4f,
                lobeHarmonicAmp = 0.015f + 0.02f * DeterministicRandom.at(seed, 320 + i),
                phase = identity.identityPhase * TWO_PI + i * 0.9f,
            )
        }

        // ---- B. Long Filaments（~45%；跨半球弧，非完整圆） ----
        val longCount = (9f * filScale).toInt().coerceIn(3, 14)
        val longs = ArrayList<LongFilamentTopo>(longCount)
        for (i in 0 until longCount) {
            val normal = fibDir(i, longCount, identity.identityPhase * TWO_PI)
            longs += LongFilamentTopo(
                plane = planeFromNormal(normal),
                arcStart = DeterministicRandom.range(seed, 400 + i * 7, 0f, TWO_PI),
                arcLength = DeterministicRandom.range(seed, 401 + i * 7, 1.15f * PI.toFloat(), 1.95f * PI.toFloat()),
                baseRadiusRatio = 0.64f + 0.14f * DeterministicRandom.at(seed, 402 + i * 7),
                freqOffset = (DeterministicRandom.at(seed, 403 + i * 7) * 2.99f).toInt(), // f + 0..2
                phase = DeterministicRandom.at(seed, 404 + i * 7) * TWO_PI,
                depthWarpAmp = 0.10f + 0.22f * DeterministicRandom.at(seed, 405 + i * 7),
            )
        }

        // ---- C. Local Fragments（~35%；短弧，壳层偏置圆心，不穿中心） ----
        val fragCount = (7f * filScale).toInt().coerceIn(2, 12)
        val frags = ArrayList<LocalFragmentTopo>(fragCount)
        for (i in 0 until fragCount) {
            val dir = fibDir(i * 2 + 1, fragCount * 2 + 1, identity.identityPhase * TWO_PI + 1.3f)
            val shell = 0.38f + 0.26f * DeterministicRandom.at(seed, 500 + i * 5)
            frags += LocalFragmentTopo(
                center = dir * shell,
                plane = planeFromNormal(fibDir(i + 40, fragCount + 41, identity.identityPhase)),
                arcStart = DeterministicRandom.range(seed, 501 + i * 5, 0f, TWO_PI),
                arcLength = DeterministicRandom.range(seed, 502 + i * 5, 0.5f, 1.4f),
                radiusRatio = 0.12f + 0.16f * DeterministicRandom.at(seed, 503 + i * 5),
                phase = DeterministicRandom.at(seed, 504 + i * 5) * TWO_PI,
            )
        }

        // ---- Fibonacci 球粒子基（§17；分类精确 78/16/6：hash 排名分层，避免阈值抽样漂移） ----
        val classRank = (0 until MAX_PARTICLES).sortedBy { DeterministicRandom.at(seed, 610 + it) }
        val kindByIndex = IntArray(MAX_PARTICLES) // 0 ambient / 1 bright / 2 glint
        classRank.forEachIndexed { rank, idx ->
            kindByIndex[idx] = when {
                rank < (MAX_PARTICLES * 0.78f).toInt() -> 0
                rank < (MAX_PARTICLES * 0.94f).toInt() -> 1
                else -> 2
            }
        }
        val warmRank = (0 until MAX_PARTICLES).sortedBy { DeterministicRandom.at(seed, 615 + it) }
        val warmByIndex = BooleanArray(MAX_PARTICLES)
        warmRank.take((MAX_PARTICLES * 0.04f).toInt()).forEach { warmByIndex[it] = true }
        val particles = ArrayList<ParticleBase>(MAX_PARTICLES)
        for (i in 0 until MAX_PARTICLES) {
            val dir = fibDir(i, MAX_PARTICLES, identity.identityPhase * GOLDEN_ANGLE)
            val u = DeterministicRandom.at(seed, 600 + i)
            val shell = 0.48f + 0.60f * u * (0.85f + identity.particleDepthBias * 0.3f)
            particles += ParticleBase(
                dir = dir,
                shellRadius = shell.coerceAtMost(1.08f),
                kind = when (kindByIndex[i]) {
                    0 -> ParticleKind.AMBIENT
                    1 -> ParticleKind.BRIGHT
                    else -> ParticleKind.GLINT
                },
                warm = warmByIndex[i], // few warm particles（§12 面积上限由渲染执行）
                sizeJitter = DeterministicRandom.at(seed, 630 + i),
            )
        }

        // ---- Core knots（§18：2–4 个稳定结；一个在暖色族） ----
        val knotCount = 2 + (identity.warmKnotTopology * 2.99f).toInt().coerceIn(0, 2)
        val knots = ArrayList<CoreKnotTopo>(knotCount)
        for (i in 0 until knotCount) {
            val angle = identity.identityPhase * TWO_PI + i * (TWO_PI / knotCount) +
                DeterministicRandom.range(seed, 700 + i, -0.5f, 0.5f)
            val r = identity.coreRatio * (0.35f + 0.45f * DeterministicRandom.at(seed, 710 + i))
            knots += CoreKnotTopo(
                offset = Vec3(cos(angle) * r, sin(angle) * r * 0.8f, 0.25f * r),
                radiusRatio = 0.016f + 0.018f * DeterministicRandom.at(seed, 720 + i),
                warm = i == 0, // 仅一个小暖结（§12 暖色面积上限由渲染执行）
            )
        }

        return OrganismTopology(rings, longs, frags, particles, knots, TOPOLOGY_VERSION)
    }

    /** Fibonacci 球第 i 个方向（§17：goldenAngle·i + identityPhase）。 */
    fun fibDir(i: Int, n: Int, phase: Float): Vec3 {
        val u = (i + .5f) / n
        val z = 1f - 2f * u
        val radial = sqrt(kotlin.math.max(0f, 1f - z * z))
        val theta = GOLDEN_ANGLE * i + phase
        return Vec3(radial * cos(theta), radial * sin(theta), z)
    }

    /** 由法向量构造稳定正交平面基。 */
    fun planeFromNormal(normal: Vec3): FilamentPlane {
        val n = normal.normalized()
        val ref = if (kotlin.math.abs(n.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
        val u = n.cross(ref).normalized()
        val v = n.cross(u).normalized()
        return FilamentPlane(u, v, n)
    }

    /** 由倾角/滚角构造平面基（结构环用；绕 X 倾 tilt，再绕 Z 滚 roll）。 */
    private fun planeFromTiltRoll(tilt: Float, roll: Float): FilamentPlane {
        val ct = cos(tilt)
        val st = sin(tilt)
        // 基础平面 = XZ 平面（法线 +Y），先绕 X 倾转再绕 Z 滚转
        val n0 = Vec3(0f, 1f, 0f)
        val u0 = Vec3(1f, 0f, 0f)
        val v0 = Vec3(0f, 0f, 1f)
        fun rotX(p: Vec3) = Vec3(p.x, p.y * ct - p.z * st, p.y * st + p.z * ct)
        val cr = cos(roll)
        val sr = sin(roll)
        fun rotZ(p: Vec3) = Vec3(p.x * cr - p.y * sr, p.x * sr + p.y * cr, p.z)
        return FilamentPlane(rotZ(rotX(u0)), rotZ(rotX(v0)), rotZ(rotX(n0)))
    }

    private const val TWO_PI = 2f * PI.toFloat()
}
