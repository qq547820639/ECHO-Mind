package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import com.yunjue.echo.mind.visual.render.EchoMaterialSpec
import com.yunjue.echo.mind.visual.render.FilamentStroke
import com.yunjue.echo.mind.visual.render.OrganismFrame
import kotlin.math.min

/**
 * AgslEchoBackend — V3 §19/§20 AGSL 材质后端（STANDARD / ADVANCED tier）。
 *
 * 职责拆分（shape ≠ material）：
 * - CPU（core:visual）已产出 vector 几何（OrganismFrame 全部层）；
 * - 本后端只把几何栅格化为 **vector mask**（R = 冷色几何覆盖，G = 暖色几何覆盖），
 *   材质（ambient / core SDF / glow / spectral mix / tone soft knee）全部在 AGSL 内完成。
 * - 只作用于 organism 自身绘制区域；**不**给整个 Compose parent tree 套 RenderEffect。
 * - Shader 输入只有 uniform + mask；不读取 Repository / DB / Observation / 私密叙事（§8）。
 *
 * 失败安全：RuntimeShader 编译失败 / API < 33 → [isAvailable] = false，调用侧回退
 * Canvas 后端（LEGACY；同一 organism，更简单材质，§22）。
 */
object AgslEchoBackend {

    /** AGSL 源（材质唯一事实源；线性空间近似计算，输出 premultiplied alpha）。 */
    const val SHADER_SOURCE = """
uniform float2 iResolution;
uniform float iExposure;
uniform float iCavity;
uniform float iHalo;
uniform float iGlowRadius;
uniform float iDepthFog;
uniform float iKnee;
uniform float iComp;
layout(color) uniform half4 iPrimary;
layout(color) uniform half4 iSecondary;
layout(color) uniform half4 iWarm;
uniform shader iVectorMask;

// soft-knee 参数经 uniform iKnee/iComp 注入（唯一事实源 = EchoMaterialSpec；shader 无硬编码副本）
half softKnee(half x, half knee, half comp) {
    if (x <= knee) return x;
    return knee + (1.0 - knee) * (1.0 - exp(-(x - knee) * comp));
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * iResolution) / min(iResolution.x, iResolution.y);
    float r = length(uv);

    // 近黑 ambient（近中心微亮，快速落黑；§24 大量 black + 少量真亮）
    half ambient = half(exp(-r * r * 7.5) * iExposure * 0.16);

    // vector mask：R = 冷色几何，G = 暖色几何，B = 归一化深度（0 back → 1 front）
    half4 mask = iVectorMask.eval(fragCoord);
    half geom = mask.r;
    half warm = mask.g;
    half depth = mask.b;

    // glow：8 向 uniform 半径多采近似边缘散射（§20/§W edge scattering，
    // 采样半径由宿主按分辨率/密度注入 iGlowRadius，分辨率无关）
    half glow = 0.0;
    float gr = iGlowRadius;
    float gd = iGlowRadius * 0.7071;
    glow += iVectorMask.eval(fragCoord + float2(gr, 0.0)).r;
    glow += iVectorMask.eval(fragCoord - float2(gr, 0.0)).r;
    glow += iVectorMask.eval(fragCoord + float2(0.0, gr)).r;
    glow += iVectorMask.eval(fragCoord - float2(0.0, gr)).r;
    glow += iVectorMask.eval(fragCoord + float2(gd, gd)).r;
    glow += iVectorMask.eval(fragCoord - float2(gd, gd)).r;
    glow += iVectorMask.eval(fragCoord + float2(gd, -gd)).r;
    glow += iVectorMask.eval(fragCoord + float2(-gd, gd)).r;
    glow *= 0.125;

    // 空心核 SDF：腔内压暗（dark cavity），腔缘一圈 internal atmosphere 微亮
    half cavity = 1.0 - smoothstep(iCavity * 0.82, iCavity * 1.04, r);
    half rim = smoothstep(iCavity * 0.82, iCavity, r) *
        (1.0 - smoothstep(iCavity, iCavity * 1.30, r));

    // Organism Quality §13：subtle volume haze（violet/blue atmosphere——
    // 空间感 ≠ 整屏 blur；峰值在身体边缘内侧，中心与外围都保持克制）
    half haze = half(exp(-r * r * 3.2) * (1.0 - exp(-r * r * 18.0)) * iExposure * 0.05);

    // §11 depth fog：深处几何向 secondary(violet) 雾色偏移并压暗（front/middle/back）
    half fog = clamp((1.0 - depth) * iDepthFog, 0.0, 1.0);

    // spectral mix：几何由内向外从 primary 过渡到 secondary（§12 蓝紫族）
    half3 cool = mix(iPrimary.rgb, iSecondary.rgb, half(min(r * 1.5, 1.0)));
    half3 col = cool * (geom * 0.9 + glow * 0.35) + iWarm.rgb * warm;
    col = mix(col, iSecondary.rgb * (geom * 0.55 + glow * 0.20), half3(fog * 0.65));
    col += iPrimary.rgb * (ambient + rim * 0.10 * half(iHalo));
    col += mix(iPrimary.rgb, iSecondary.rgb, 0.5) * haze;
    col *= (1.0 - cavity * 0.82);

    // tone：soft knee，禁止 hard clip（§24；knee/comp 来自 packet.material uniform）
    half knee = half(iKnee);
    half comp = half(iComp);
    col.r = softKnee(col.r, knee, comp);
    col.g = softKnee(col.g, knee, comp);
    col.b = softKnee(col.b, knee, comp);

    half alpha = clamp(geom * 0.95 + glow * 0.5 + warm + ambient + rim * 0.12 + haze, 0.0, 1.0);
    alpha *= (1.0 - cavity * 0.90);
    return half4(col * alpha, alpha); // premultiplied
}
"""

    /**
     * ADVANCED final grading（§21：RuntimeColorFilter 用于 final grading / tone consistency）。
     * 只做色调一致性微调——UI text / navigation / Activity 永不进入 shader pipeline。
     */
    const val GRADING_SOURCE = """
half4 main(half4 c) {
    half l = dot(c.rgb, half3(0.2126, 0.5872, 0.0722));
    // 极轻 vibrance + 高光一致性（线性域近似；保持近黑不变）
    half3 graded = mix(half3(l), c.rgb, 1.05);
    half knee = 0.58;
    graded = min(graded, knee) + (graded - min(graded, knee)) * 0.92;
    return half4(graded, c.a);
}
"""

    /**
     * §X：RuntimeShader 是否可用（API 33+ 且 AGSL 编译成功）——**进程级只算一次**
     * （首次探测含着色器编译，属高成本路径；禁止任何帧路径重复探测）。
     */
    @Volatile
    private var availableCache: Boolean? = null

    fun isAvailable(): Boolean {
        availableCache?.let { return it }
        val v = if (Build.VERSION.SDK_INT < 33) {
            false
        } else {
            try {
                RuntimeShader(SHADER_SOURCE)
                true
            } catch (_: Throwable) {
                false
            }
        }
        availableCache = v
        return v
    }

    /** §X：ADVANCED 合成（RuntimeColorFilter）是否可用——进程级只算一次。 */
    @Volatile
    private var advancedAvailableCache: Boolean? = null

    fun isAdvancedAvailable(): Boolean {
        advancedAvailableCache?.let { return it }
        val v = if (Build.VERSION.SDK_INT < 36) {
            false
        } else {
            try {
                android.graphics.RuntimeColorFilter(GRADING_SOURCE)
                true
            } catch (_: Throwable) {
                false
            }
        }
        advancedAvailableCache = v
        return v
    }

    /**
     * §W：glow 采样半径（px）——分辨率/密度无关：基准 1080px 视口下 2.5–6px，
     * 随 minDim 线性缩放并由 halo 强度调制（8-tap 边缘散射视觉特征不变）。
     */
    fun glowRadiusPxFor(haloIntensity: Float, minDim: Float): Float =
        ((2.5f + 3.5f * haloIntensity.coerceIn(0f, 1f)) * (minDim / 1080f)).coerceIn(2f, 6f)

    /**
     * 工程/评审证据：把生产 vector mask（R 冷几何 / G 暖几何 / B 深度 / A coverage）
     * 栅格化为独立 bitmap——AGSL raster 需设备 HW canvas，mask 是 JVM 可验证的
     * Advanced 材质输入真值（不得伪装成 AGSL 输出）。
     */
    fun rasterizeMaskForInspection(
        frame: com.yunjue.echo.mind.visual.render.OrganismFrame,
        widthPx: Int,
        heightPx: Int,
        warmColor: Int,
    ): Bitmap {
        val session = AgslSession(widthPx, heightPx)
        session.rasterizeMask(frame, widthPx.toFloat(), heightPx.toFloat(), warmColor)
        return session.maskSnapshot()
    }

    /**
     * AGSL 会话（RuntimeShader 编译一次复用 + mask bitmap 复用——§32 hot path 零位图分配）。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class AgslSession(val width: Int, val height: Int, val advanced: Boolean = false) {
        private val shader = RuntimeShader(SHADER_SOURCE)
        private val gradingPaint: Paint? = if (advanced && Build.VERSION.SDK_INT >= 36) {
            try {
                Paint().apply { colorFilter = android.graphics.RuntimeColorFilter(GRADING_SOURCE) }
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        private val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private val maskCanvas = Canvas(maskBitmap)
        private val maskShader = BitmapShader(maskBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val warmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            shader.setInputBuffer("iVectorMask", maskShader)
            drawPaint.shader = shader
        }

        /** 绘制一帧（几何每帧重栅格化进复用 bitmap；无 per-frame 分配）。 */
        fun draw(
            canvas: Canvas,
            frame: OrganismFrame,
            widthPx: Float,
            heightPx: Float,
            exposure: Float,
            halo: Float,
            primaryColor: Int,
            secondaryColor: Int,
            warmColor: Int,
            material: EchoMaterialSpec = EchoMaterialSpec(),
        ) {
            rasterizeMask(frame, widthPx, heightPx, warmColor)
            shader.setFloatUniform("iResolution", widthPx, heightPx)
            shader.setFloatUniform("iExposure", exposure.coerceIn(0f, 1f))
            shader.setFloatUniform("iCavity", frame.coreCavity.radiusFraction)
            shader.setFloatUniform("iHalo", halo.coerceIn(0f, 1f))
            // §11 depth fog 强度（Quality Pass：0.55——深处明显偏雾但不吃掉结构）
            shader.setFloatUniform("iDepthFog", 0.55f)
            // §W：glow 采样半径随分辨率/密度缩放（分辨率无关的 8-tap 边缘散射）
            shader.setFloatUniform(
                "iGlowRadius",
                glowRadiusPxFor(halo, min(widthPx, heightPx)),
            )
            shader.setColorUniform("iPrimary", primaryColor)
            shader.setColorUniform("iSecondary", secondaryColor)
            shader.setColorUniform("iWarm", warmColor)
            // T2-P2-3：tone soft-knee 参数由 packet.material 注入（shader 端无硬编码副本；默认值=编译产物同值）
            shader.setFloatUniform("iKnee", material.toneKnee)
            shader.setFloatUniform("iComp", material.toneCompression)
            val grading = gradingPaint
            if (grading != null) {
                // §21：final grading 只作用于 organism 自身 layer
                val saveCount = canvas.saveLayer(0f, 0f, widthPx, heightPx, grading)
                canvas.drawRect(0f, 0f, widthPx, heightPx, drawPaint)
                canvas.restoreToCount(saveCount)
            } else {
                canvas.drawRect(0f, 0f, widthPx, heightPx, drawPaint)
            }
        }

        /**
         * vector mask 栅格化（R=冷色几何覆盖，G=暖色几何覆盖，B=归一化深度，A=coverage）。
         *
         * 层对齐（UX-B1）：ripples（§29 触摸涟漪描边）、halos（远层光环）、frontMembrane
         * （前膜细环）均进 R 通道——AGSL 后端不再静默丢弃这三层（与 Canvas 行为对齐）。
         * 已知限制：coreCavity 的 §8 有机谐波边缘形变在 shader cavity（正圆 smoothstep）上
         * 不呈现——属设备 HW 门验证项（AGSL raster 需硬件 canvas）。
         */
        internal fun rasterizeMask(frame: OrganismFrame, widthPx: Float, heightPx: Float, warmColor: Int) {
            maskCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val minDim = min(widthPx, heightPx)
            val cx = widthPx / 2f
            val cy = heightPx / 2f

            fun maskStroke(stroke: FilamentStroke, widthScale: Float) {
                val pts = stroke.points
                if (pts.size < 2) return
                strokePaint.strokeWidth = stroke.widthFraction * minDim * widthScale
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    val alpha = ((a.alpha + b.alpha) * 0.5f).coerceIn(0f, 1f)
                    if (alpha <= 0.004f) continue
                    val depth = ((a.depth + b.depth) * 0.5f).coerceIn(0f, 1f)
                    strokePaint.color = Color.argb(
                        (alpha * 255).toInt(), 255, 0, (depth * 255).toInt(),
                    )
                    maskCanvas.drawLine(
                        a.x * widthPx, a.y * heightPx, b.x * widthPx, b.y * heightPx, strokePaint,
                    )
                }
            }
            // 远层光环进 R（低 alpha 圆环；B=255 前层——不被 depth fog 雾化，与 Canvas 行为对齐）
            strokePaint.style = android.graphics.Paint.Style.STROKE
            frame.halos.forEach { h ->
                strokePaint.strokeWidth = h.widthFraction * minDim
                strokePaint.color = Color.argb(
                    (h.alpha.coerceIn(0f, 1f) * 255).toInt(), 255, 0, 255,
                )
                maskCanvas.drawCircle(cx, cy, h.radiusFraction * minDim, strokePaint)
            }
            frame.structuralRings.forEach { maskStroke(it, 1.6f) }
            frame.longFilaments.forEach { maskStroke(it, 1.35f) }
            frame.localFragments.forEach { maskStroke(it, 1.2f) }
            frame.coreStrands.forEach { maskStroke(it, 1.1f) }

            frame.particles.forEach { p ->
                if (p.color == warmColor) return@forEach
                // B=粒子 frontness（SceneParticleV3.depth；不再硬编码 200——depth fog 按真实前后分层）
                dotPaint.color = Color.argb(
                    (p.alpha.coerceIn(0f, 1f) * 255).toInt(), 255, 0,
                    (p.depth.coerceIn(0f, 1f) * 255).toInt(),
                )
                maskCanvas.drawCircle(p.x * widthPx, p.y * heightPx, p.radiusFraction * minDim, dotPaint)
            }
            // 暖色几何进 G 通道（暖结 / 暖高光 / 暖粒子；面积 ≤15% 由 CPU 侧拓扑保证）
            frame.coreKnots.filter { it.color == warmColor }.forEach { k ->
                warmPaint.color = Color.argb(200, 0, 255, 0)
                maskCanvas.drawCircle(
                    k.x * widthPx, k.y * heightPx, k.radiusFraction * minDim * 1.4f, warmPaint,
                )
            }
            frame.warmAccents.forEach { w ->
                warmPaint.color = Color.argb((w.alpha.coerceIn(0f, 1f) * 255).toInt(), 0, 255, 0)
                maskCanvas.drawCircle(w.x * widthPx, w.y * heightPx, w.radiusFraction * minDim, warmPaint)
            }
            frame.particles.forEach { p ->
                if (p.color == warmColor) {
                    warmPaint.color = Color.argb((p.alpha.coerceIn(0f, 1f) * 160).toInt(), 0, 255, 0)
                    maskCanvas.drawCircle(p.x * widthPx, p.y * heightPx, p.radiusFraction * minDim, warmPaint)
                }
            }

            // 前膜细环进 R（§18 front membrane；宽度与 Canvas 渲染器同源 minDim*0.0016）
            strokePaint.strokeWidth = minDim * 0.0016f
            strokePaint.color = Color.argb(
                (frame.frontMembrane.alpha.coerceIn(0f, 1f) * 255).toInt(), 255, 0, 255,
            )
            maskCanvas.drawCircle(
                cx, cy, frame.frontMembrane.radiusFraction * minDim, strokePaint,
            )

            // 触摸涟漪描边进 R（§29；宽度≈stroke，与 Canvas 渲染器同源 minDim*0.002）
            frame.ripples.forEach { r ->
                strokePaint.strokeWidth = minDim * 0.002f
                strokePaint.color = Color.argb((r.alpha.coerceIn(0f, 1f) * 255).toInt(), 255, 0, 255)
                maskCanvas.drawCircle(r.x * widthPx, r.y * heightPx, r.radiusFraction * minDim, strokePaint)
            }
        }

        /** mask 只读快照（评审/工程证据用；生产帧路径不调用）。 */
        fun maskSnapshot(): Bitmap = maskBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
}
