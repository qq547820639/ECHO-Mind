package com.yunjue.echo.mind.presence

import android.content.Context
import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import com.yunjue.echo.mind.AppPreferences
import java.time.LocalTime

/**
 * ERA 3 — ECHO Live Wallpaper（Master Prompt PART 46/60/61）。
 *
 * 原则：
 * - **只消费 EchoPresenceState 快照**，不运行 Personal Intelligence pipeline；
 *   不初始化业务容器（AppContainer 是 lazy 且 Keystore fail-closed，壁纸进程不触发）；
 * - **不可见立即停止渲染**（onVisibilityChanged(false) 移除 Choreographer 回调，
 *   0 帧率 0 CPU——能耗验收硬指标）；
 * - AI/sensing 更新率 ≠ 渲染帧率：快照分钟级变化，渲染只在可见时发生；
 * - **仅渲染视觉，不渲染任何文字** → 锁屏 Public Safe 由构造保证（无文字 = 无敏感文字）；
 * - 确定性帧模型：同一快照/种子/时间 → 同一画面（Journey 视觉记忆的前提）。
 */
class EchoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = EchoEngine()

    companion object {
        /** 触摸涟漪衰减时长。 */
        const val RIPPLE_DURATION_MS = 1200L
    }

    inner class EchoEngine : Engine() {
        /** ERA 14 §65：渲染生命周期唯一事实源（不可见 → 0 帧率，可单测状态机）。 */
        private val render = WallpaperRenderController()
        private var frameCallback: Choreographer.FrameCallback? = null
        private var startNanos = 0L

        /** ERA 3 收尾（WORK-ERA3-2）：触摸涟漪衰减截止时间（0 = 无涟漪）。 */
        private var rippleUntilMs = 0L

        private val prefs: SharedPreferences by lazy {
            applicationContext.getSharedPreferences(
                AppPreferences.PREFS_FILE,
                Context.MODE_PRIVATE
            )
        }

        private var snapshot: EchoPresenceState? = EchoPresenceCodec.decode(
            prefs.getString(AppPreferences.KEY_ECHO_PRESENCE_SNAPSHOT, null)
        )

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            // ERA 3 收尾：触摸涟漪（轻量交互；涟漪只影响亮度/波纹，不改变底层状态）
            setTouchEventsEnabled(true)
        }

        override fun onTouchEvent(event: android.view.MotionEvent) {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (render.onTouch()) {
                        rippleUntilMs = System.currentTimeMillis() + RIPPLE_DURATION_MS
                        drawFrame()
                    }
                }
                else -> Unit
            }
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            render.onVisibilityChanged(visible)
            if (visible) {
                // ERA 54（§52 审计收官）：解锁/回前台即重读快照——15 分钟刷新周期的 Presence
                // 更新无需等下一次 surface 变化（SharedPreferences 读，开销可忽略）。
                refreshSnapshot()
            }
            if (render.renderActive) startRendering() else stopRendering()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // 快照可能已更新：每次 surface 变化前重读
            render.onSurfaceChanged()
            refreshSnapshot()
            if (render.renderActive) drawFrame()
        }

        override fun onDestroy() {
            render.onDestroy()
            stopRendering()
            super.onDestroy()
        }

        private fun refreshSnapshot() {
            snapshot = EchoPresenceCodec.decode(
                prefs.getString(AppPreferences.KEY_ECHO_PRESENCE_SNAPSHOT, null)
            )
        }

        private fun startRendering() {
            if (frameCallback != null) return
            startNanos = 0L
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    drawFrame()
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            frameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        }

        private fun stopRendering() {
            frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            frameCallback = null
        }

        private fun drawFrame() {
            if (!render.renderActive) return
            val holder = surfaceHolder ?: return
            val canvas = try {
                holder.lockCanvas()
            } catch (_: Exception) {
                return
            } ?: return
            try {
                refreshSnapshot()
                val presence = snapshot
                val hourOfDay = LocalTime.now().let { it.hour + it.minute / 60f }
                val params = if (presence != null) {
                    computeVisualParameters(
                        state = presence,
                        hourOfDay = hourOfDay,
                        surface = SurfaceMode.HOME_WALLPAPER,
                    )
                } else {
                    NEUTRAL_VISUAL_PARAMS
                }
                if (startNanos == 0L) startNanos = System.nanoTime()
                val timeSeconds = (System.nanoTime() - startNanos) / 1_000_000_000f
                // 触摸涟漪：衰减 1.2s 内的亮度/波纹增强（不改底层状态，纯表现层）
                val ripple = if (rippleUntilMs > System.currentTimeMillis()) {
                    (rippleUntilMs - System.currentTimeMillis()).toFloat() / RIPPLE_DURATION_MS
                } else {
                    0f
                }
                val frame = computeEchoSceneFrame(
                    params = params.copy(
                        brightness = (params.brightness + ripple * 0.2f).coerceIn(0f, 1f),
                        accentIntensity = (params.accentIntensity + ripple * 0.3f).coerceIn(0f, 1f),
                        turbulence = (params.turbulence + ripple * 0.25f).coerceIn(0f, 1f),
                    ),
                    seed = presence?.identityGenome?.seed ?: 0L,
                    timeSeconds = timeSeconds,
                    width = canvas.width.toFloat(),
                    height = canvas.height.toFloat(),
                )
                renderEchoFrameToCanvas(canvas, frame, canvas.width.toFloat(), canvas.height.toFloat())
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }
}
