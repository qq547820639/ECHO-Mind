package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.EchoPresenceState

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

        /** PowerManager.THERMAL_STATUS_SEVERE 的值（API 29 常量；低 API 不参与热态调度，仅作数值比较）。 */
        private const val THERMAL_SEVERE_LEVEL = 4
    }

    inner class EchoEngine : Engine() {
        /** ERA 14 §65：渲染生命周期唯一事实源（不可见 → 0 帧率，可单测状态机）。 */
        private val render = WallpaperRenderController()
        private var frameCallback: Choreographer.FrameCallback? = null
        private var startNanos = 0L

        /** ERA 31 R13（§16）：最近一次视觉变化时刻（快照更新/触摸涟漪）——自适应帧间隔依据。 */
        private var lastVisualChangeMs = 0L

        /** ERA 3 收尾（WORK-ERA3-2）：触摸涟漪衰减截止时间（0 = 无涟漪）。 */
        private var rippleUntilMs = 0L
        /** §70：触摸点（归一化；进入 transient interaction ripple）。 */
        private var touchX = 0.5f
        private var touchY = 0.5f
        private var lastTouchMs = 0L
        /** §68：launcher offset 平移（±5% width 上限；Reduced Motion 关闭）。 */
        private var launcherOffsetX = 0f

        private val prefs: SharedPreferences by lazy {
            applicationContext.getSharedPreferences(
                AppPreferences.PREFS_FILE,
                Context.MODE_PRIVATE
            )
        }

        private var snapshot: EchoPresenceState? = EchoPresenceCodec.decode(
            prefs.getString(AppPreferences.KEY_ECHO_PRESENCE_SNAPSHOT, null)
        )
        /** ERA 74 §65：快照重读节流（每帧 JSON 解码 → 至多每秒一次）。 */
        private var lastSnapshotReadMs = 0L

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
                        lastTouchMs = System.currentTimeMillis()
                        val w = resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: 1
                        val h = resources.displayMetrics.heightPixels.takeIf { it > 0 } ?: 1
                        touchX = (event.x / w).coerceIn(0f, 1f)
                        touchY = (event.y / h).coerceIn(0f, 1f)
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

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            // §68：launcher offset 最多 ±5% width；Reduced Motion → parallax off
            val reduced = prefs.getBoolean("presence_reduce_motion", false)
            launcherOffsetX = if (reduced) 0f else (xOffset - 0.5f).coerceIn(-0.05f, 0.05f)
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
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
            val previous = snapshot?.updatedAt
            snapshot = EchoPresenceCodec.decode(
                prefs.getString(AppPreferences.KEY_ECHO_PRESENCE_SNAPSHOT, null)
            )
            if (snapshot?.updatedAt != previous) {
                // ERA 31 R13：快照变化 → 视觉参数将变化 → 进入流畅过渡窗口
                lastVisualChangeMs = System.currentTimeMillis()
            }
            lastSnapshotReadMs = System.currentTimeMillis()
        }

        /** ERA 74 §64：用户视觉偏好进入渲染（减少动画/动态程度/夜间模式；键与 AppPreferences 同源）。 */
        private fun surfaceConfig() = resolveSurfaceConfig(
            baseSurface = SurfaceMode.HOME_WALLPAPER,
            reduceMotion = prefs.getBoolean("presence_reduce_motion", false),
            motionLevelName = prefs.getString("presence_motion_level", "DEFAULT") ?: "DEFAULT",
            nightMode = prefs.getBoolean("presence_night_mode", false),
        )

        private fun startRendering() {
            if (frameCallback != null) return
            startNanos = 0L
            lastVisualChangeMs = System.currentTimeMillis()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    drawFrame()
                    // V3 §69：调度优先级表（0/8/10/12/18/30fps；30 cap）
                    val now = System.currentTimeMillis()
                    val config = surfaceConfig()
                    val delay = WallpaperScheduler.wallpaperFrameDelayMs(
                        visible = render.renderActive,
                        reducedMotion = config.surface == SurfaceMode.REDUCED_MOTION,
                        thermalSevereOrWorse = com.yunjue.echo.mind.presencevisual.EchoRenderEnvironment
                            .currentThermalStatus(applicationContext) >= THERMAL_SEVERE_LEVEL,
                        powerSave = com.yunjue.echo.mind.presencevisual.EchoRenderEnvironment
                            .isPowerSave(applicationContext),
                        msSinceTouch = now - lastTouchMs,
                        msSincePresenceUpdate = now - lastVisualChangeMs,
                        night = config.nightMode,
                    ) ?: run {
                        stopRendering()
                        return
                    }
                    Choreographer.getInstance().postFrameCallbackDelayed(this, delay)
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
                // ERA 74 §65：节流重读（可见/表面变化时已强制刷新；帧循环内至多每秒一次）
                if (shouldRefreshSnapshot(lastSnapshotReadMs, System.currentTimeMillis())) {
                    refreshSnapshot()
                }
                val presence = snapshot
                val hourOfDay = LocalTime.now().let { it.hour + it.minute / 60f }
                val config = surfaceConfig()
                if (startNanos == 0L) startNanos = System.nanoTime()
                val timeSeconds = (System.nanoTime() - startNanos) / 1_000_000_000f
                // visual-runtime R2 + V3：Wallpaper 复用 core/visual organism（SAME ECHO；无文字 Public Safe）。
                val genome = com.yunjue.echo.mind.visual.model.GenomeDeriver.derive(
                    presence ?: EchoPresenceState(), hourOfDay,
                )
                // §70：触摸窗口内使用与 App 同一 transient ripple（Gaussian 形变 + 1 ripple）；
                // 不开 App / 不 call AI / 不写 Presence。
                val rippleAgeMs = if (rippleUntilMs > System.currentTimeMillis()) {
                    RIPPLE_DURATION_MS - (rippleUntilMs - System.currentTimeMillis())
                } else {
                    -1L
                }
                val interaction = if (rippleAgeMs >= 0L) {
                    com.yunjue.echo.mind.visual.render.EchoInteractionSpec(
                        active = true,
                        touchX = touchX,
                        touchY = touchY,
                        envelope = com.yunjue.echo.mind.visual.motion.MotionEvaluator
                            .interactionEnvelope(rippleAgeMs),
                    )
                } else {
                    com.yunjue.echo.mind.visual.render.EchoInteractionSpec()
                }
                val frame = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                    spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                        genome = genome,
                        surface = com.yunjue.echo.mind.visual.surface.EchoSurface.WALLPAPER_VISUAL_ONLY,
                        clockSeconds = timeSeconds,
                    ),
                    width = canvas.width.toFloat(),
                    height = canvas.height.toFloat(),
                    options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                        maturityName = (presence ?: EchoPresenceState()).maturity.name,
                        quality = com.yunjue.echo.mind.presencevisual.EchoRenderEnvironment
                            .currentQuality(applicationContext),
                        reducedMotion = config.surface == SurfaceMode.REDUCED_MOTION,
                        interaction = interaction,
                    ),
                )
                // §68：portrait center x=.50W / y=.43H；launcher offset ≤±5% width
                canvas.save()
                canvas.translate(
                    launcherOffsetX * canvas.width,
                    (0.43f - 0.50f) * canvas.height,
                )
                com.yunjue.echo.mind.presencevisual.OrganismCanvasRenderer.draw(
                    canvas, frame, canvas.width.toFloat(), canvas.height.toFloat(),
                )
                canvas.restore()
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }
}
