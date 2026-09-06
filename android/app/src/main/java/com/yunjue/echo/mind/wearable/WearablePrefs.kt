package com.yunjue.echo.mind.wearable

import android.content.Context
import android.content.SharedPreferences

/**
 * WearablePrefs —— ECHO on Wrist 的用户控制与 revision 持久化。
 *
 * 每个权限/开关只回答三件事：为什么？ECHO 会得到什么？可以关闭吗？
 * （权限文案见 Me → ECHO on Wrist；这里只存事实开关。）
 *
 * 持久化的唯一个人数据：Presence revision 计数器（协议单调性，无隐私含义）。
 */
class WearablePrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("echo_wearable", Context.MODE_PRIVATE)

    /** Presence revision（进程死亡后继续单调增长）。 */
    var presenceRevision: Long
        get() = prefs.getLong(KEY_PRESENCE_REVISION, 0L)
        set(value) = prefs.edit().putLong(KEY_PRESENCE_REVISION, value).apply()

    /** 佩戴上下文权限（默认关：未真机验证前不采集；开启后 vendor 未验证值仍为 UNKNOWN）。 */
    var wearingContextEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEARING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WEARING_ENABLED, value).apply()

    /** 睡眠上下文权限（默认关；需要官方能力 + 真机验证 + 显式授权三重门）。 */
    var sleepContextEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SLEEP_ENABLED, value).apply()

    /** 触觉（默认 SILENT；只允许显式 tap/呼吸节奏/完成确认）。 */
    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS_ENABLED, value).apply()

    /** 前台加速度计摘要（默认关：foreground only；开启只在 App 打开时采集）。 */
    var motionSummaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MOTION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_MOTION_ENABLED, value).apply()

    /** 用户主动断开（断开后不自动重连，直到用户重新打开）。 */
    var userDisconnected: Boolean
        get() = prefs.getBoolean(KEY_USER_DISCONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_DISCONNECTED, value).apply()

    /** 表盘样式（设计稿 17：0-3 四种腕上投影风格；影响手机端预览与后续腕上渲染）。 */
    var watchFaceStyle: Int
        get() = prefs.getInt(KEY_WATCH_FACE_STYLE, 0)
        set(value) = prefs.edit().putInt(KEY_WATCH_FACE_STYLE, value.coerceIn(0, 3)).apply()

    /** 表盘复杂信息（设计稿 17：显示更多信息——时钟/状态行；关 = 仅生命体）。 */
    var watchFaceComplications: Boolean
        get() = prefs.getBoolean(KEY_WATCH_COMPLICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_WATCH_COMPLICATIONS, value).apply()

    companion object {
        private const val KEY_PRESENCE_REVISION = "presence_revision"
        private const val KEY_WEARING_ENABLED = "wearing_context_enabled"
        private const val KEY_SLEEP_ENABLED = "sleep_context_enabled"
        private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        private const val KEY_MOTION_ENABLED = "motion_summary_enabled"
        private const val KEY_USER_DISCONNECTED = "user_disconnected"
        private const val KEY_WATCH_FACE_STYLE = "watch_face_style"
        private const val KEY_WATCH_COMPLICATIONS = "watch_face_complications"
    }
}
