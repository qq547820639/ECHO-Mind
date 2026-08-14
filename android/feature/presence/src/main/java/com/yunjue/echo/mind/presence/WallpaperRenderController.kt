package com.yunjue.echo.mind.presence

/**
 * ERA 14 §65 — Wallpaper 渲染生命周期控制（纯状态机，可单测）。
 *
 * 硬指标：不可见时 continuous rendering = 0（Choreographer 回调必须移除）。
 * EchoWallpaperService 以本控制器为唯一事实源；测试锚定以下语义：
 * - 不可见 → renderActive = false（0 帧率 0 CPU）；
 * - 可见 + surface → renderActive = true；
 * - destroy → 永久停止；
 * - 不可见时触摸 → 不产生任何绘制（涟漪不渲染）。
 */
class WallpaperRenderController {
    /** 当前可见性（onVisibilityChanged 输入）。 */
    var visible: Boolean = false
        private set

    /** 是否已销毁（onDestroy 后一切停止）。 */
    var destroyed: Boolean = false
        private set

    /** 渲染是否允许运行（渲染循环必须据此启停 Choreographer 回调）。 */
    var renderActive: Boolean = false
        private set

    fun onVisibilityChanged(visible: Boolean) {
        this.visible = visible
        if (destroyed) {
            renderActive = false
            return
        }
        renderActive = visible
    }

    fun onSurfaceChanged() {
        if (visible && !destroyed) renderActive = true
    }

    /** 触摸事件是否应产生绘制（不可见/已销毁 → false）。 */
    fun onTouch(): Boolean = renderActive && visible && !destroyed

    fun onDestroy() {
        destroyed = true
        renderActive = false
        visible = false
    }
}
