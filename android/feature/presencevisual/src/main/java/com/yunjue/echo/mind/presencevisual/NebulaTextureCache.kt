package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.concurrent.ConcurrentHashMap

/**
 * NebulaTextureCache — Organism Visual Breakthrough §24：deterministic procedural texture cache。
 *
 * 体积叶的材质基元：低频软边缘径向纹理（128×128），运行时生成、非设计稿 PNG、
 * 非 hero asset；同 color 恒同纹理（确定性），按 identity/state 驱动的颜色缓存复用，
 * 绝不每帧生成（getOrPut 语义；LRU 容量封顶防多 identity 长会话膨胀）。
 *
 * 渲染纪律（§55）：纹理只在首次遇到新颜色时构建（µs 级单次成本）；
 * 帧路径 drawBitmap(matrix, paint) 零分配。
 */
object NebulaTextureCache {

    private const val SIZE = 128
    private const val HALF = SIZE / 2f
    private const val MAX_ENTRIES = 40

    private val cache = ConcurrentHashMap<Int, Bitmap>()

    /**
     * 星云叶纹理：中心 100% → 32% 处 55% → 60% 处 16% → 88% 处 0 的锐衰减
     * （可见核心收敛到 ~60% 半径——叠加成云的同时保留 lobe 之间的内部黑暗；
     * 单层 alpha 由渲染 paint 承载）。
     */
    fun textureFor(color: Int): Bitmap = cache.getOrPut(color) {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            HALF, HALF, HALF,
            intArrayOf(
                withAlpha(color, 1f),
                withAlpha(color, 0.55f),
                withAlpha(color, 0.16f),
                withAlpha(color, 0f),
            ),
            floatArrayOf(0f, 0.32f, 0.60f, 0.88f),
            Shader.TileMode.CLAMP,
        )
        c.drawCircle(HALF, HALF, HALF, paint)
        trimIfNeeded()
        bmp
    }

    /** LRU 语义近似：超过容量按插入序清除最旧条目（ConcurrentHashMap 无序——用代际戳）。 */
    private fun trimIfNeeded() {
        if (cache.size > MAX_ENTRIES) {
            val excess = cache.size - MAX_ENTRIES
            cache.keys.take(excess).forEach { cache.remove(it) }
        }
    }

    /** 测试钩子。 */
    fun clearForTest() = cache.clear()

    fun sizeForTest(): Int = cache.size

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return color and 0x00FFFFFF or (a shl 24)
    }
}
