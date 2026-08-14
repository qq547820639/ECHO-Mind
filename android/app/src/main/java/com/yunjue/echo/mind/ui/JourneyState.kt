package com.yunjue.echo.mind.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Journey/趋势页 UI 侧共享助手（ERA 13 收口）。
 *
 * 七态解析 / NO_DATA 原因 / 覆盖度等纯逻辑已迁入 `journey/JourneyTrendState.kt`（应用层）；
 * 本文件只保留 UI 文本锚点与系统设置 intent。
 */

/** 趋势页固定免责文案（契约点 2，作为单测锚点）。 */
internal const val TREND_DISCLAIMER = "这些趋势来自设备上的行为派生特征，不能知道或判断你的真实情绪。"

/** 打开本应用系统设置页（修复权限用 deep link，Settings.ACTION_APPLICATION_DETAILS_SETTINGS）。 */
internal fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/**
 * v0.6.2（A4）：电池优化设置页（无需特殊权限，直接打开系统"忽略电池优化"列表）。
 * 用于 SYSTEM_BACKGROUND NO_DATA 态的"前往系统设置"CTA。
 */
internal fun batteryOptimizationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

internal fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "暂无"
    return runCatching {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("暂无")
}
