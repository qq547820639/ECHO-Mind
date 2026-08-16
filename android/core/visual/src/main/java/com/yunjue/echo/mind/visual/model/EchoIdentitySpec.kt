package com.yunjue.echo.mind.visual.model

import com.yunjue.echo.mind.visual.math.DeterministicRandom
import kotlin.math.floor

/**
 * EchoIdentitySpec — V3 §10–§13 身份层（Identity）确定性规格。
 *
 * 身份层铁律：
 * - 唯一随机源是 identitySeed；派生全部经 [DeterministicRandom]（SplitMix64 终混）。
 *   禁止 Random.Default / Math.random / UUID / System.currentTimeMillis 进入身份。
 * - same(seed + channel) 永远同一结果 → 跨 App/Wallpaper/Dream/Wrist/Journey 同一 ECHO。
 * - 不同用户不能只换颜色：lobeCount / chirality / coreRatio / ring tilt / frequency family /
 *   particle depth distribution / warm knot topology / palette family 全部参与几何。
 * - Daily / Moment 层**不得**重新生成本规格任何字段（V3 §11）。
 *
 * 本类型是 renderer-internal 身份编译产物，不是新业务 Contract（V3 §7）。
 */
data class EchoIdentitySpec(
    /** 身份种子（installation random seed；数月恒定）。 */
    val identitySeed: Long,
    /** 叶瓣数 2..5（V3 §11；结构性 topology 差异维度）。 */
    val lobeCount: Int,
    /** 手性 -1/+1（filament/orbital 的镜像方向；Day0→Day180 连续血缘锚点）。 */
    val chirality: Int,
    /** 核心腔体比 .29...43（hollow core cavity ratio / R；V3 §11/§18）。 */
    val coreRatio: Float,
    /** 主环倾角 -.52..+.52 rad（V3 §11）。 */
    val primaryTilt: Float,
    /** 次环倾角 -.76..+.76 rad（V3 §11）。 */
    val secondaryTilt: Float,
    /** filament 基频族 2..5（harmonic field 的 f；V3 §11/§15）。 */
    val baseFrequency: Int,
    /** 轨道偏置 -.12..+.12（V3 §11）。 */
    val orbitalBias: Float,
    /** 膜偏置 .86..1.14（V3 §11）。 */
    val membraneBias: Float,
    /** 身份相位 0..1（粒子 Fibonacci 球的 golden-angle 相位偏移；V3 §17）。 */
    val identityPhase: Float,
    /** 粒子深度分布偏置 0..1（volume radius .48..1.08 内的稳定分布形态；V3 §10）。 */
    val particleDepthBias: Float,
    /** 暖结拓扑种子通道值 0..1（2–4 个稳定 core knot 的位置族；V3 §10/§18）。 */
    val warmKnotTopology: Float,
    /** 感知调色板（LCh 语义；V3 §12）。 */
    val palette: EchoPaletteSpec,
) {
    companion object {
        /** identityUnit(seed, channel) → 0..1（确定性；channel 即 V3 §11/§12 的序号）。 */
        fun identityUnit(seed: Long, channel: Int): Float =
            DeterministicRandom.at(seed, channel)

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

        private fun fracOf(v: Long): Float = (v ushr 40 and 0xFFFFFF).toFloat() / 16777215f

        /** identitySeed → 完整身份规格（纯函数；同 seed 恒同结果）。 */
        fun derive(seed: Long): EchoIdentitySpec {
            // §12 primary hue 与 EchoIdentityGenome.accentHue 同源（frac(mix(seed,1))）——
            // App / Wallpaper / Dream / Wrist 呈现同一 hue 族（SAME ECHO 色族一致）。
            val primaryHue = 218f + 44f * fracOf(DeterministicRandom.mix(seed, 1)) // 218°..262°
            val secondaryHue = primaryHue + 22f + 22f * identityUnit(seed, 21) // §12：+22°..44°
            val warmHue = 28f + 12f * identityUnit(seed, 22)               // §12：28°..40°
            return EchoIdentitySpec(
                identitySeed = seed,
                lobeCount = 2 + floor(identityUnit(seed, 1) * 4f).toInt(), // 2..5
                chirality = if (identityUnit(seed, 2) < .5f) -1 else 1,
                coreRatio = lerp(.29f, .43f, identityUnit(seed, 3)),
                primaryTilt = lerp(-.52f, .52f, identityUnit(seed, 4)),
                secondaryTilt = lerp(-.76f, .76f, identityUnit(seed, 5)),
                baseFrequency = 2 + floor(identityUnit(seed, 6) * 4f).toInt(), // 2..5
                orbitalBias = lerp(-.12f, .12f, identityUnit(seed, 7)),
                membraneBias = lerp(.86f, 1.14f, identityUnit(seed, 8)),
                identityPhase = identityUnit(seed, 9),
                particleDepthBias = identityUnit(seed, 10),
                warmKnotTopology = identityUnit(seed, 11),
                palette = EchoPaletteSpec(
                    primary = PerceptualColor(l = 0.78f, c = 0.12f, h = primaryHue),
                    secondary = PerceptualColor(l = 0.62f, c = 0.095f, h = secondaryHue),
                    warm = PerceptualColor(l = 0.72f, c = 0.12f, h = warmHue),
                ),
            )
        }
    }
}

/**
 * 感知颜色（CIELCh：L 0..1 归一化亮度 / C 彩度 / h 色相角度）。
 * V3 §12：identity palette 的长期语义是 LCh，不是 HSV/RGB。
 */
data class PerceptualColor(
    val l: Float,
    val c: Float,
    val h: Float,
)

/** 身份感知调色板（primary / secondary / warm 三色族；暖色面积 ≤15% 由渲染层执行）。 */
data class EchoPaletteSpec(
    val primary: PerceptualColor,
    val secondary: PerceptualColor,
    val warm: PerceptualColor,
)
