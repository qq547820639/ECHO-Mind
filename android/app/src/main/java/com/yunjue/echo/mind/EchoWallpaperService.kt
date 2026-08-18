package com.yunjue.echo.mind

import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoPresenceCodec
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.PresenceRenderPolicy
import com.yunjue.echo.mind.presence.WallpaperRenderController
import com.yunjue.echo.mind.presence.WallpaperScheduler
import com.yunjue.echo.mind.presence.resolveRenderPolicy
import com.yunjue.echo.mind.presence.shouldRefreshSnapshot
import com.yunjue.echo.mind.presencevisual.EchoEnvironmentSnapshot
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRenderSession
import com.yunjue.echo.mind.presencevisual.EchoRenderEnvironmentState
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.presencevisual.EchoVisualClock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.motion.MotionEvaluator
import com.yunjue.echo.mind.visual.render.EchoInteractionSpec
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.surface.EchoSurface
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
 * - V3 §R/§AY–§BC：渲染经 EchoRendererFacade session（帧路径只喂
 *   EchoVisualClock + interaction）；时间戳全部 SystemClock.elapsedRealtime；
 *   环境读数走 EchoRenderEnvironmentState（5s 缓存），无逐帧系统服务查询。
 */
class EchoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = EchoEngine()

    companion object {
        /** 触摸涟漪衰减时长。 */
        const val RIPPLE_DURATION_MS = 1200L

        /** §AZ：launcher offset 目标幅度（xOffset 全程 → ±10% → 裁到 ±5% width）。 */
        private const val LAUNCHER_OFFSET_SCALE = 0.10f
        private const val LAUNCHER_OFFSET_MAX = 0.05f

        /** §AZ：每帧轻平滑（current += (target-current)*0.15）。 */
        private const val LAUNCHER_OFFSET_LERP = 0.15f
    }

    inner class EchoEngine : Engine() {
        /** ERA 14 §65：渲染生命周期唯一事实源（不可见 → 0 帧率，可单测状态机）。 */
        private val render = WallpaperRenderController()
        private var frameCallback: Choreographer.FrameCallback? = null

        /** ERA 31 R13（§16）：最近一次视觉变化时刻（快照更新/触摸涟漪）——自适应帧间隔依据。 */
        private var lastVisualChangeMs = 0L

        /** §AY：触摸涟漪/触点时间戳（SystemClock.elapsedRealtime 基准）。 */
        private var rippleUntilMs = 0L
        private var lastTouchMs = 0L
        /** §70：触摸点（归一化；进入 transient interaction ripple）。 */
        private var touchX = 0.5f
        private var touchY = 0.5f
        /** §AZ：launcher offset 目标 + 当前值（轻平滑；Reduced Motion 关闭）。 */
        private var launcherOffsetTargetX = 0f
        private var launcherOffsetX = 0f

        /** §BA：视口尺寸（onSurfaceChanged 保存；触点归一化用真实 surface，非 displayMetrics）。 */
        private var surfaceW = 0
        private var surfaceH = 0

        /** §R/§BC：facade 渲染会话（surface 尺寸/输入变化时重建；帧路径只 draw）。 */
        private var renderSession: EchoRenderSession? = null
        private var sessionInputs: SessionInputs? = null

        private val prefs: SharedPreferences by lazy {
            applicationContext.getSharedPreferences(
                AppPreferences.PREFS_FILE,
                Context.MODE_PRIVATE
            )
        }

        private var snapshot: EchoPresenceState? = EchoPresenceCodec.decode(
            prefs.getString(AppPreferences.KEY_ECHO_PRESENCE_SNAPSHOT, null)
        )
        /** ERA 74 §65：快照重读节流（每帧 JSON 解码 → 至多每秒一次；elapsedRealtime 基准）。 */
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
                        // §AY：触摸时间戳一律 SystemClock.elapsedRealtime
                        val now = SystemClock.elapsedRealtime()
                        rippleUntilMs = now + RIPPLE_DURATION_MS
                        lastTouchMs = now
                        // §BA：触点归一化用保存的 surface 视口（非 resources.displayMetrics）
                        val w = surfaceW.takeIf { it > 0 } ?: 1
                        val h = surfaceH.takeIf { it > 0 } ?: 1
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
            // §AZ：launcher 滚动 → 目标 offset = ((xOffset-.5)*.10) 裁到 ±5%；
            // 每帧向目标轻平滑（drawFrame 内 lerp 0.15）；Reduced Motion → parallax off
            val reduced = prefs.getBoolean(AppPreferences.KEY_PRESENCE_REDUCE_MOTION, false)
            launcherOffsetTargetX = if (reduced) {
                0f
            } else {
                ((xOffset - 0.5f) * LAUNCHER_OFFSET_SCALE).coerceIn(-LAUNCHER_OFFSET_MAX, LAUNCHER_OFFSET_MAX)
            }
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // §BA：保存真实 surface 视口（触点归一化 + session 尺寸键）
            surfaceW = width
            surfaceH = height
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
                lastVisualChangeMs = SystemClock.elapsedRealtime()
            }
            lastSnapshotReadMs = SystemClock.elapsedRealtime()
        }

        /** ERA 74 §64：用户视觉偏好进入渲染（减少动画/动态程度/夜间模式；
         *  键经 AppPreferences 伴生常量同源——编译期内联，不初始化业务容器）。 */
        private fun renderPolicy(): PresenceRenderPolicy = resolveRenderPolicy(
            baseSurface = EchoSurface.WALLPAPER_VISUAL_ONLY,
            reduceMotion = prefs.getBoolean(AppPreferences.KEY_PRESENCE_REDUCE_MOTION, false),
            motionLevelName = prefs.getString(AppPreferences.KEY_PRESENCE_MOTION_LEVEL, "DEFAULT") ?: "DEFAULT",
            nightMode = prefs.getBoolean(AppPreferences.KEY_PRESENCE_NIGHT_MODE, false),
        )

        private fun startRendering() {
            if (frameCallback != null) return
            lastVisualChangeMs = SystemClock.elapsedRealtime()
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    drawFrame()
                    // V3 §69/§BC：调度优先级表（0/8/10/12/18/30fps；30 cap）——
                    // 热态/省电输入来自 5s 缓存环境快照（无逐帧系统服务查询）
                    val now = SystemClock.elapsedRealtime()
                    val env = EchoRenderEnvironmentState.current(applicationContext)
                    val policy = renderPolicy()
                    val delay = WallpaperScheduler.wallpaperFrameDelayMs(
                        visible = render.renderActive,
                        reducedMotion = policy.reduceMotion,
                        thermalSevereOrWorse = env.thermalSevereOrWorse,
                        powerSave = env.powerSave,
                        msSinceTouch = now - lastTouchMs,
                        msSincePresenceUpdate = now - lastVisualChangeMs,
                        night = policy.nightMode,
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
                val nowMs = SystemClock.elapsedRealtime()
                if (shouldRefreshSnapshot(lastSnapshotReadMs, nowMs)) {
                    refreshSnapshot()
                }
                // §BB：环境快照每帧至多一次（5s 缓存内零系统调用）
                val env = EchoRenderEnvironmentState.current(applicationContext)
                val presenceState = snapshot ?: EchoPresenceState()
                val policy = renderPolicy()
                val localNow = LocalTime.now()
                val inputs = SessionInputs(
                    presenceUpdatedAt = presenceState.updatedAt,
                    maturityName = presenceState.maturity.name,
                    policy = policy,
                    env = env,
                    minuteOfDay = localNow.hour * 60 + localNow.minute,
                    width = canvas.width,
                    height = canvas.height,
                )
                // §R：session 只在输入（快照/策略/环境/分钟/尺寸）变化时重建；帧路径只 draw
                if (renderSession == null || sessionInputs != inputs) {
                    sessionInputs = inputs
                    val hourOfDay = localNow.hour + localNow.minute / 60f
                    // visual-runtime V3 §H：Wallpaper 复用 core/visual organism（SAME ECHO）；
                    // genome 经唯一语义链（EchoVisualMapper → VisualGenomeCompiler）计算。
                    val genome = VisualGenomeCompiler.compile(
                        EchoVisualMapper.map(
                            presenceState, hourOfDay,
                            policy.motionLevel, policy.nightMode, policy.reduceMotion,
                        ),
                        presenceState.identityGenome,
                    )
                    renderSession = EchoRendererFacade.createSession(
                        EchoRenderRequest(
                            genome = genome,
                            surface = EchoSurface.WALLPAPER_VISUAL_ONLY,
                            motion = policy.motion,
                            maturityName = presenceState.maturity.name,
                            requestedTier = EchoRenderTier.LEGACY,
                        ),
                        canvas.width,
                        canvas.height,
                    )
                }
                // §AZ：launcher offset 轻平滑（目标 ≠ 立即，避免 page swipe 跳变）
                launcherOffsetX += (launcherOffsetTargetX - launcherOffsetX) * LAUNCHER_OFFSET_LERP
                // §70：触摸窗口内使用与 App 同一 transient ripple（Gaussian 形变 + 1 ripple）；
                // 不开 App / 不 call AI / 不写 Presence。
                val rippleAgeMs = if (rippleUntilMs > nowMs) RIPPLE_DURATION_MS - (rippleUntilMs - nowMs) else -1L
                val interaction = if (rippleAgeMs >= 0L) {
                    EchoInteractionSpec(
                        active = true,
                        touchX = touchX,
                        touchY = touchY,
                        envelope = MotionEvaluator.interactionEnvelope(rippleAgeMs),
                    )
                } else {
                    EchoInteractionSpec()
                }
                // §68：portrait center x=.50W / y=.43H；launcher offset ≤±5% width
                canvas.save()
                canvas.translate(
                    launcherOffsetX * canvas.width,
                    (0.43f - 0.50f) * canvas.height,
                )
                renderSession?.draw(canvas, EchoVisualClock.nowNanos(), interaction)
                canvas.restore()
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }
}

/**
 * §R：request 重建输入键（任一变化 → 重建 genome/request/session；
 * env 为 data class——quality/tier/runtimeShader 变化即触发重建，§BB/§BG）。
 * （Kotlin 禁止 inner class 内声明嵌套 class → 提升为文件级私有。）
 */
private data class SessionInputs(
    val presenceUpdatedAt: java.time.Instant,
    val maturityName: String,
    val policy: PresenceRenderPolicy,
    val env: EchoEnvironmentSnapshot,
    val minuteOfDay: Int,
    val width: Int,
    val height: Int,
)
