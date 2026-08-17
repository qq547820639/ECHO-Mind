package com.yunjue.echo.mind

import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoPresenceCodec
import com.yunjue.echo.mind.presence.shouldRefreshSnapshot

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.View
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.WallpaperScheduler
import com.yunjue.echo.mind.presence.resolveRenderPolicy
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRenderSession
import com.yunjue.echo.mind.presencevisual.EchoRenderEnvironmentState
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.presencevisual.EchoVisualClock
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.visual.surface.EchoSurface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ERA 3 — ECHO Dream（充电桌面屏保，Master Prompt PART 46/59/62）。
 *
 * 原则：
 * - Android 官方 screensaver：仅充电/底座 + 空闲时由系统唤起；是独立 Ambient Surface，
 *   不是锁屏替代品（不承诺接管锁屏 UI）；
 * - 最沉浸的 Ambient surface：大 ECHO + 时钟 + 日期，大量 UI 自动隐藏；
 * - 只消费 EchoPresenceState 快照（与 Wallpaper 同源），不运行 Intelligence pipeline；
 * - 渲染用 View + 自适应帧间隔（ERA 31 R14 §16：过渡期 33ms / 静置期 250ms）：
 *   View 脱离窗口后 invalidate 不再触发 onDraw，回调链自动停止，0 残留渲染；
 * - 显示内容全部 PUBLIC_SAFE：ECHO 字标 + 时间 + 日期（无状态词、无敏感文字）。
 * - V3 §BD–§BF：渲染经 EchoRendererFacade session（帧路径只喂 EchoVisualClock）；
 *   Paint/Typeface 复用字段（init 一次分配）；单一复用 Runnable 调度 invalidate；
 *   视觉相位用 boot-global EchoVisualClock（dreamStartedAtMs 只用于入场帧率 ramp）。
 */
class EchoDreamService : DreamService() {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setInteractive(false)   // 屏保无需交互
        setFullscreen(true)
        setScreenBright(false)  // 极轻的日夜变化由视觉引擎承担，不额外控制屏幕亮度
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        setContentView(EchoDreamView(this))
    }
}

/** Dream 渲染 View（View 脱离窗口后 postInvalidateOnAnimation 链自动停止 → 0 残留渲染）。 */
internal class EchoDreamView(context: Context) : View(context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(AppPreferences.PREFS_FILE, Context.MODE_PRIVATE)
    }

    /** §BD：会话起始（SystemClock.elapsedRealtime）——只用于入场 3s 帧率 ramp（调度节奏，
     *  非视觉相位；视觉时间基准 = EchoVisualClock）。 */
    private val dreamStartedAtMs = SystemClock.elapsedRealtime()

    private var lastSnapshotReadMs = 0L
    private var snapshot: EchoPresenceState? = null
    /** ERA 31 R14（§16）：最近视觉变化时刻——与 Wallpaper 同款自适应帧间隔策略。 */
    private var lastVisualChangeMs = 0L
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日")
    /** 快照缺失时的中性 Presence 兜底（预分配，避免 onDraw 内分配）。 */
    private val fallbackPresence = EchoPresenceState()

    // §BE：Paint/Typeface 一次性分配（onDraw 只改 color/textSize 等廉价属性）
    private val lightTypeface: Typeface =
        Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val wordmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 0xCC
        textAlign = Paint.Align.CENTER
        typeface = lightTypeface
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = lightTypeface
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    /** §BE：单一复用 invalidate Runnable（removeCallbacks + postDelayed，无每帧分配）。 */
    private val invalidateRunnable = Runnable { invalidate() }

    /** §BD：facade 渲染会话（输入变化时重建；帧路径只 draw）。 */
    private var renderSession: EchoRenderSession? = null

    /** §BE：session 重建键（逐字段比较——onDraw 内零对象分配）。 */
    private var sessionUpdatedAt: java.time.Instant = java.time.Instant.EPOCH
    private var sessionMaturityName: String = ""
    private var sessionReduceMotion: Boolean = false
    private var sessionMotionLevel: String = ""
    private var sessionNightMode: Boolean = false
    private var sessionEnv: com.yunjue.echo.mind.presencevisual.EchoEnvironmentSnapshot? = null
    private var sessionMinuteOfDay: Int = -1
    private var sessionW: Int = -1
    private var sessionH: Int = -1

    /** ECHO 字标 accent（organism 前膜 primary 色；session 重建时一并派生，帧内只读）。 */
    private var accentColor: Int = 0xFF8FA3C8.toInt()

    @SuppressLint("CanvasSize") // View.onDraw 的 canvas 即完整绘制面，canvas.width/height 为正确引用
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // ERA 74 §65：快照重读节流（至多每秒一次；首帧立即读取）
        if (shouldRefreshSnapshot(lastSnapshotReadMs, SystemClock.elapsedRealtime())) {
            val previous = snapshot?.updatedAt
            snapshot = EchoPresenceCodec.decode(
                prefs.getString(AppPreferences.KEY_ECHO_PRESENCE_SNAPSHOT, null)
            )
            if (snapshot?.updatedAt != previous) {
                lastVisualChangeMs = SystemClock.elapsedRealtime()
            }
            lastSnapshotReadMs = SystemClock.elapsedRealtime()
        }
        val presenceState = snapshot ?: fallbackPresence
        // §BF：每帧单次 LocalDateTime.now() 快照 → time/date/minuteOfDay 全部派生
        val now = LocalDateTime.now()
        val localTime = now.toLocalTime()
        val minuteOfDay = localTime.hour * 60 + localTime.minute
        // ERA 74 §64：用户视觉偏好进入渲染（键与 AppPreferences 同源）
        val reduceMotion = prefs.getBoolean("presence_reduce_motion", false)
        val motionLevelName = prefs.getString("presence_motion_level", "DEFAULT") ?: "DEFAULT"
        val nightMode = prefs.getBoolean("presence_night_mode", false)
        // §BB：环境快照（5s 缓存内零系统调用）
        val env = EchoRenderEnvironmentState.current(context)
        // §BE：逐字段比较（不构造键对象，onDraw 零分配）；任一变化 → 重建 genome/request/session
        val needRebuild = renderSession == null ||
            sessionUpdatedAt != presenceState.updatedAt ||
            sessionMaturityName != presenceState.maturity.name ||
            sessionReduceMotion != reduceMotion ||
            sessionMotionLevel != motionLevelName ||
            sessionNightMode != nightMode ||
            sessionEnv != env ||
            sessionMinuteOfDay != minuteOfDay ||
            sessionW != canvas.width ||
            sessionH != canvas.height
        if (needRebuild) {
            sessionUpdatedAt = presenceState.updatedAt
            sessionMaturityName = presenceState.maturity.name
            sessionReduceMotion = reduceMotion
            sessionMotionLevel = motionLevelName
            sessionNightMode = nightMode
            sessionEnv = env
            sessionMinuteOfDay = minuteOfDay
            sessionW = canvas.width
            sessionH = canvas.height
            rebuildSession(
                presenceState = presenceState,
                env = env,
                reduceMotion = reduceMotion,
                motionLevelName = motionLevelName,
                nightMode = nightMode,
                hourOfDay = localTime.hour + localTime.minute / 60f,
                width = canvas.width,
                height = canvas.height,
            )
        }
        // §71：Dream center x=.5 y≈.46
        canvas.save()
        canvas.translate(0f, (0.46f - 0.50f) * h)
        renderSession?.draw(canvas, EchoVisualClock.nowNanos())
        canvas.restore()
        drawPublicSafeOverlay(canvas, accentColor, w, h, now, localTime)

        // V3 §71：Dream 帧率——entry 前 3s 24fps / steady 15 / reduced 8；
        // View 脱离窗口后 invalidate 不再触发 onDraw，回调链自动停止（0 残留渲染语义保持）。
        // §BD：elapsedSinceStartMs 只来自 dreamStartedAtMs（调度 ramp，非视觉相位）。
        val interval = WallpaperScheduler.dreamFrameDelayMs(
            elapsedSinceStartMs = SystemClock.elapsedRealtime() - dreamStartedAtMs,
            reducedMotion = reduceMotion,
        )
        removeCallbacks(invalidateRunnable)
        postDelayed(invalidateRunnable, interval)
    }

    /**
     * §BD：session 重建（低频路径：输入变化才执行；onDraw 内只调用，不承载逐帧逻辑）。
     * visual-runtime V3 §H：Dream 复用 core/visual 分层 organism（SAME ECHO）；
     * genome 经唯一语义链（EchoVisualMapper → VisualGenomeCompiler）计算。
     */
    private fun rebuildSession(
        presenceState: EchoPresenceState,
        env: com.yunjue.echo.mind.presencevisual.EchoEnvironmentSnapshot,
        reduceMotion: Boolean,
        motionLevelName: String,
        nightMode: Boolean,
        hourOfDay: Float,
        width: Int,
        height: Int,
    ) {
        val policy = resolveRenderPolicy(
            baseSurface = EchoSurface.DREAM_AMBIENT,
            reduceMotion = reduceMotion,
            motionLevelName = motionLevelName,
            nightMode = nightMode,
        )
        val genome = VisualGenomeCompiler.compile(
            EchoVisualMapper.map(
                presenceState, hourOfDay,
                policy.motionLevel, policy.nightMode, policy.reduceMotion,
            ),
            presenceState.identityGenome,
        )
        // ECHO 字标 accent = organism 前膜 primary 色（确定性派生自 identitySeed）
        val primary = EchoIdentitySpec.derive(genome.identitySeed).palette.primary
        accentColor = ColorSpace.lch(primary.l, primary.c, primary.h)
        renderSession = EchoRendererFacade.createSession(
            EchoRenderRequest(
                genome = genome,
                surface = EchoSurface.DREAM_AMBIENT,
                motion = policy.motion, // §BF：reducedMotion 经 facade request 的 MotionPolicy
                maturityName = presenceState.maturity.name,
                requestedTier = env.tier, // 设备能力解析（AGSL 可用即走材质后端）
            ),
            width,
            height,
        )
    }

    /**
     * Dream 的 PUBLIC_SAFE 覆盖层：ECHO 字标 + 时间 + 日期。
     * 白名单内容（无状态词、无情绪词、无习惯异常、无支持信息）——锁屏隐私由内容本身保证。
     * §BE：Paint/Typeface 均为复用字段；§72 burn-in 每分钟 deterministic offset。
     */
    private fun drawPublicSafeOverlay(
        canvas: Canvas,
        accentColor: Int,
        w: Float,
        h: Float,
        now: LocalDateTime,
        localTime: java.time.LocalTime,
    ) {
        wordmarkPaint.color = accentColor
        wordmarkPaint.alpha = 0xCC
        wordmarkPaint.textSize = w * 0.06f
        clockPaint.textSize = w * 0.14f
        datePaint.textSize = w * 0.05f

        // §72：burn-in——clock/date 每分钟 deterministic offset（x[-3,+3]dp / y[-2,+2]dp，不跳变）
        val minuteOfDay = localTime.hour * 60 + localTime.minute
        val (oxDp, oyDp) = WallpaperScheduler.burnInOffsetDp(minuteOfDay)
        val density = resources.displayMetrics.density
        val ox = oxDp * density
        val oy = oyDp * density
        canvas.drawText("ECHO", w / 2f, h * 0.22f, wordmarkPaint)
        canvas.drawText(localTime.format(timeFormatter), w / 2f + ox, h * 0.34f + oy, clockPaint)
        canvas.drawText(now.toLocalDate().format(dateFormatter), w / 2f + ox, h * 0.41f + oy, datePaint)
    }
}
