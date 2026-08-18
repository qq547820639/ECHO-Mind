package com.yunjue.echo.mind.visual.render

import com.yunjue.echo.mind.visual.math.DeterministicRandom
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OrganismTopology — V3 §14–§18 稳定拓扑（identity 级缓存）+ Organism Quality Pass §6/§7。
 *
 * 三层结构（§6 视觉目标；identity 恒定，maturity 只调丰富度）：
 *  A. Structural Rings（15–20%：4–7 个非闭合骨架弧——不是轨道圆）
 *  B. Long Filaments（35–45%：跨半球、0.55–0.92R 分布、arc ≤1.6π）
 *  C. Local Filament Fragments（35–45%：24–40 个短弧（18°–75°），壳层 0.45–0.95R，
 *     很少穿过 core cavity；identity 恒定，daily 只调可见度/材质）
 *  + Fibonacci 球粒子基位置（§17：78/17/5 ambient/bright/glint，壳层收敛 0.50–1.00R）
 *  + 稳定 core knots（§18：2–4 个，位置族 identity 恒定）。
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

/** 结构环（identity skeleton；稳定倾角 + lobe 谐波 + 非闭合弧口）。 */
data class StructuralRingTopo(
    val plane: FilamentPlane,
    val radiusRatio: Float,
    val lobeHarmonicAmp: Float,
    val phase: Float,
    /** 弧口（非完整圆——打破轨道圆读感；0..TWO_PI）。 */
    val arcStart: Float,
    val arcLength: Float,
)

/** 长丝（跨半球弧；arcLength ≤1.6π —— 不是完整圆）。 */
data class LongFilamentTopo(
    val plane: FilamentPlane,
    val arcStart: Float,
    val arcLength: Float,
    val baseRadiusRatio: Float,
    val freqOffset: Int,
    val phase: Float,
    val depthWarpAmp: Float,
)

/** 局部碎片（短弧 18°–75°；圆心偏置在 0.45–0.95R 壳层，很少穿过中心）。 */
data class LocalFragmentTopo(
    val center: Vec3,
    val plane: FilamentPlane,
    val arcStart: Float,
    val arcLength: Float,
    val radiusRatio: Float,
    val phase: Float,
    /** 面外深度摆动（打破平面感；identity 恒定）。 */
    val depthWarpAmp: Float,
    /** 曲率微调（daily 可轻微调制，不改拓扑）。 */
    val curvature: Float,
    /** 少数碎片用 primary（其余 secondary）——碎片不是同一色带。 */
    val primaryFamily: Boolean,
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
    const val TOPOLOGY_VERSION = 4

    private const val GOLDEN_ANGLE = 2.39996323f
    private const val MAX_PARTICLES = 220
    private const val MAX_CACHE = 16

    private data class TopoKey(
        val seed: Long,
        val quality: EchoRenderQuality,
        val maturityName: String,
        val version: Int,
    )

    /**
     * §32 拓扑缓存：accessOrder LRU（容量 [MAX_CACHE]，按 key 淘汰最久未用条目——
     * 不做 clear-on-overflow 全量清空，避免双 identity 交替渲染时逐帧全量重建）。
     * 拓扑构建重（220 粒子基 + 两次 220 元素排序）；synchronized 串行化 miss 重建，
     * 命中路径无竞争成本可忽略（Wallpaper/Dream 会话线程安全）。
     */
    private val cache = object : LinkedHashMap<TopoKey, OrganismTopology>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TopoKey, OrganismTopology>): Boolean =
            size > MAX_CACHE
    }

    /** 获取（或确定性重建）稳定拓扑。Moment/Daily 更新永不触发重建。 */
    fun topologyFor(
        identity: EchoIdentitySpec,
        quality: EchoRenderQuality,
        maturityName: String,
    ): OrganismTopology {
        val key = TopoKey(identity.identitySeed, quality, maturityName, TOPOLOGY_VERSION)
        return synchronized(cache) { cache.getOrPut(key) { build(identity, quality, maturityName) } }
    }

    /** 测试钩子：清空缓存。 */
    fun clearCacheForTest() = synchronized(cache) { cache.clear() }

    /** 测试钩子：当前缓存条目数（LRU 容量淘汰断言用）。 */
    fun cacheSizeForTest(): Int = synchronized(cache) { cache.size }

    private fun build(
        identity: EchoIdentitySpec,
        quality: EchoRenderQuality,
        maturityName: String,
    ): OrganismTopology {
        val seed = identity.identitySeed
        // 盐分段（确定性独立随机流纪律）：rings 1000–1046 / longs 2000–2152 / frags 3000–3199
        // （形变族 3400–3539）/ particles 4000–4879（shell 4000+/分类 4220+/暖标 4440+/抖动 4660+）
        // / knots 5000–5203——全部盐区间两两不相交
        // （OrganismTopologyTest.saltSegmentsDoNotCollideAcrossLayers 静态断言）。
        val profile = EchoSceneCompiler.qualityProfile(quality)
        val maturity = EchoSceneCompiler.maturityMultiplier(maturityName)
        val filScale = profile.filamentScale * maturity

        // ---- A. Structural Rings（15–20%；4..7 个非闭合骨架弧，倾角来自 identity）----
        val ringCount = (4 + identity.lobeCount / 2 +
            (identity.warmKnotTopology * 1.99f).toInt()).coerceIn(4, 7)
        val rings = ArrayList<StructuralRingTopo>(ringCount)
        for (i in 0 until ringCount) {
            val tilt = if (i == 0) {
                identity.primaryTilt
            } else {
                identity.secondaryTilt * (0.6f + 0.4f * DeterministicRandom.at(seed, 1000 + i))
            }
            val roll = DeterministicRandom.range(seed, 1010 + i, 0f, TWO_PI)
            rings += StructuralRingTopo(
                plane = planeFromTiltRoll(tilt, roll),
                radiusRatio = 0.58f + 0.055f * i + identity.orbitalBias * 0.35f,
                lobeHarmonicAmp = 0.030f + 0.035f * DeterministicRandom.at(seed, 1020 + i),
                phase = identity.identityPhase * TWO_PI + i * 0.9f,
                // 非闭合：留 3%–16% 弧口（打破轨道圆；identity 恒定）
                arcStart = DeterministicRandom.range(seed, 1030 + i, 0f, TWO_PI),
                arcLength = TWO_PI * (0.84f + 0.13f * DeterministicRandom.at(seed, 1040 + i)),
            )
        }

        // ---- B. Long Filaments（35–45%；跨半球弧，非完整圆，径向 0.55–0.92R 展开）----
        val longCount = (18f * filScale).toInt().coerceIn(6, 22)
        val longs = ArrayList<LongFilamentTopo>(longCount)
        for (i in 0 until longCount) {
            val normal = fibDir(i, longCount, identity.identityPhase * TWO_PI)
            longs += LongFilamentTopo(
                plane = planeFromNormal(normal),
                arcStart = DeterministicRandom.range(seed, 2000 + i * 7, 0f, TWO_PI),
                arcLength = DeterministicRandom.range(seed, 2001 + i * 7, 1.0f * PI.toFloat(), 1.6f * PI.toFloat()),
                baseRadiusRatio = 0.55f + 0.37f * DeterministicRandom.at(seed, 2002 + i * 7),
                freqOffset = (DeterministicRandom.at(seed, 2003 + i * 7) * 2.99f).toInt(), // f + 0..2
                phase = DeterministicRandom.at(seed, 2004 + i * 7) * TWO_PI,
                depthWarpAmp = 0.14f + 0.20f * DeterministicRandom.at(seed, 2005 + i * 7),
            )
        }

        // ---- C. Local Fragments（35–45%；18°–75° 短弧，壳层 0.45–0.95R，很少穿中心）----
        val fragCount = (34f * filScale).toInt().coerceIn(10, 40)
        val frags = ArrayList<LocalFragmentTopo>(fragCount)
        for (i in 0 until fragCount) {
            val dir = fibDir(i * 2 + 1, fragCount * 2 + 1, identity.identityPhase * TWO_PI + 1.3f)
            // 二次分布：主体在中外层（0.48–0.95R），极少贴核
            val u = DeterministicRandom.at(seed, 3000 + i * 5)
            val shell = 0.48f + 0.47f * u * u
            frags += LocalFragmentTopo(
                center = dir * shell,
                plane = planeFromNormal(fibDir(i + 40, fragCount + 41, identity.identityPhase)),
                arcStart = DeterministicRandom.range(seed, 3001 + i * 5, 0f, TWO_PI),
                // 18°–75°（0.31–1.31 rad）
                arcLength = DeterministicRandom.range(seed, 3002 + i * 5, 0.31f, 1.31f),
                radiusRatio = 0.09f + 0.15f * DeterministicRandom.at(seed, 3003 + i * 5),
                phase = DeterministicRandom.at(seed, 3004 + i * 5) * TWO_PI,
                depthWarpAmp = 0.05f + 0.09f * DeterministicRandom.at(seed, 3400 + i),
                curvature = 0.4f + 0.6f * DeterministicRandom.at(seed, 3450 + i),
                primaryFamily = DeterministicRandom.at(seed, 3500 + i) < 0.28f,
            )
        }

        // ---- Fibonacci 球粒子基（§17；分类精确 78/17/5：hash 排名分层，避免阈值抽样漂移）----
        val classRank = (0 until MAX_PARTICLES).sortedBy { DeterministicRandom.at(seed, 4220 + it) }
        val kindByIndex = IntArray(MAX_PARTICLES) // 0 ambient / 1 bright / 2 glint
        classRank.forEachIndexed { rank, idx ->
            kindByIndex[idx] = when {
                rank < (MAX_PARTICLES * 0.78f).toInt() -> 0
                rank < (MAX_PARTICLES * 0.95f).toInt() -> 1
                else -> 2
            }
        }
        val warmRank = (0 until MAX_PARTICLES).sortedBy { DeterministicRandom.at(seed, 4440 + it) }
        val warmByIndex = BooleanArray(MAX_PARTICLES)
        warmRank.take((MAX_PARTICLES * 0.04f).toInt()).forEach { warmByIndex[it] = true }
        val particles = ArrayList<ParticleBase>(MAX_PARTICLES)
        for (i in 0 until MAX_PARTICLES) {
            val dir = fibDir(i, MAX_PARTICLES, identity.identityPhase * GOLDEN_ANGLE)
            val u = DeterministicRandom.at(seed, 4000 + i)
            // 壳层收敛 0.50–1.00R（削减远层散点——星空感来源；深度偏置微调形态）
            val shell = (0.50f + 0.50f * u * u * (0.92f + identity.particleDepthBias * 0.16f))
                .coerceAtMost(1.0f)
            particles += ParticleBase(
                dir = dir,
                shellRadius = shell,
                kind = when (kindByIndex[i]) {
                    0 -> ParticleKind.AMBIENT
                    1 -> ParticleKind.BRIGHT
                    else -> ParticleKind.GLINT
                },
                warm = warmByIndex[i], // few warm particles（§12 面积上限由渲染执行）
                sizeJitter = DeterministicRandom.at(seed, 4660 + i),
            )
        }

        // ---- Core knots（§18：2–4 个稳定结；一个在暖色族=核心解剖）----
        val knotCount = 2 + (identity.warmKnotTopology * 2.99f).toInt().coerceIn(0, 2)
        val knots = ArrayList<CoreKnotTopo>(knotCount)
        for (i in 0 until knotCount) {
            val angle = identity.identityPhase * TWO_PI + i * (TWO_PI / knotCount) +
                DeterministicRandom.range(seed, 5000 + i, -0.5f, 0.5f)
            val r = identity.coreRatio * (0.35f + 0.45f * DeterministicRandom.at(seed, 5100 + i))
            knots += CoreKnotTopo(
                offset = Vec3(cos(angle) * r, sin(angle) * r * 0.8f, 0.25f * r),
                radiusRatio = 0.024f + 0.024f * DeterministicRandom.at(seed, 5200 + i),
                warm = i == 0, // 仅一个小暖结（核心解剖；§12 暖色面积上限由渲染执行）
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
