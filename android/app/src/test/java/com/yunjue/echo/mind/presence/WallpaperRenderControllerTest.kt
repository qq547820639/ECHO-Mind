package com.yunjue.echo.mind.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 14 §65 — Wallpaper 渲染生命周期硬指标：
 * 不可见时 continuous rendering = 0；destroy 后永久停止；不可见触摸不绘制。
 */
class WallpaperRenderControllerTest {

    @Test
    fun invisibleStopsRendering() {
        val c = WallpaperRenderController()
        c.onVisibilityChanged(true)
        assertTrue(c.renderActive)
        c.onVisibilityChanged(false)
        assertFalse(c.renderActive) // 0 帧率 0 CPU（§65 硬指标）
    }

    @Test
    fun visibleWithSurfaceRenders() {
        val c = WallpaperRenderController()
        c.onVisibilityChanged(true)
        c.onSurfaceChanged()
        assertTrue(c.renderActive)
    }

    @Test
    fun destroyStopsForever() {
        val c = WallpaperRenderController()
        c.onVisibilityChanged(true)
        c.onDestroy()
        assertFalse(c.renderActive)
        // 销毁后任何可见性事件不得复活渲染
        c.onVisibilityChanged(true)
        c.onSurfaceChanged()
        assertFalse(c.renderActive)
        assertTrue(c.destroyed)
    }

    @Test
    fun invisibleTouchProducesNoDraw() {
        val c = WallpaperRenderController()
        c.onVisibilityChanged(false)
        assertFalse(c.onTouch()) // 不可见触摸 → 不渲染涟漪
        c.onVisibilityChanged(true)
        assertTrue(c.onTouch())
        c.onDestroy()
        assertFalse(c.onTouch())
    }
}
