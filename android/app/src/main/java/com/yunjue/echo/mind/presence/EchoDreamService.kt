package com.yunjue.echo.mind.presence

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.service.dreams.DreamService
import android.view.View
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.ui.renderEchoFrameToCanvas
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * ERA 3 — ECHO Dream（充电桌面屏保，Master Prompt PART 46/59/62）。
 *
 * 原则：
 * - Android 官方 screensaver：仅充电/底座 + 空闲时由系统唤起；是独立 Ambient Surface，
 *   不是锁屏替代品（不承诺接管锁屏 UI）；
 * - 最沉浸的 Ambient surface：大 ECHO + 时钟 + 日期，大量 UI 自动隐藏；
 * - 只消费 EchoPresenceState 快照（与 Wallpaper 同源），不运行 Intelligence pipeline；
 * - 渲染用 View + postInvalidateOnAnimation：View 脱离窗口后回调链自动停止，0 残留渲染；
 * - 显示内容全部 PUBLIC_SAFE：ECHO 字标 + 时间 + 日期（无状态词、无敏感文字）。
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

    private var startNanos = 0L
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        val presence = EchoPresenceCodec.decode(
            prefs.getString(AppPreferences.KEY_ECHO_PRESENCE_SNAPSHOT, null)
        )
        val hourOfDay = LocalTime.now().let { it.hour + it.minute / 60f }
        val params = if (presence != null) {
            computeVisualParameters(presence, hourOfDay, SurfaceMode.DREAM)
        } else {
            com.yunjue.echo.mind.ui.NEUTRAL_VISUAL_PARAMS
        }
        if (startNanos == 0L) startNanos = System.nanoTime()
        val timeSeconds = (System.nanoTime() - startNanos) / 1_000_000_000f
        val frame = computeEchoSceneFrame(
            params = params,
            seed = presence?.identityGenome?.seed ?: 0L,
            timeSeconds = timeSeconds,
            width = w,
            height = h,
        )
        renderEchoFrameToCanvas(canvas, frame, w, h)
        drawPublicSafeOverlay(canvas, frame, w, h)

        postInvalidateOnAnimation()
    }

    /**
     * Dream 的 PUBLIC_SAFE 覆盖层：ECHO 字标 + 时间 + 日期。
     * 白名单内容（无状态词、无情绪词、无习惯异常、无支持信息）——锁屏隐私由内容本身保证。
     */
    private fun drawPublicSafeOverlay(canvas: Canvas, frame: EchoSceneFrame, w: Float, h: Float) {
        val wordmark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frame.accentColor
            alpha = 0xCC
            textSize = w * 0.06f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6FFFFFF.toInt()
            textSize = w * 0.14f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        val date = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            textSize = w * 0.05f
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("ECHO", w / 2f, h * 0.22f, wordmark)
        canvas.drawText(LocalTime.now().format(timeFormatter), w / 2f, h * 0.34f, clock)
        canvas.drawText(LocalDate.now().format(dateFormatter), w / 2f, h * 0.41f, date)
    }
}
