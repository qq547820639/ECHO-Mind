package com.yunjue.echo.mind.sensing

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Phase 6.1 Permission Degraded：感知能力模型（PM 规格 §2）。
 *
 * 替代旧「三重门控」的全有或全无语义：核心 Portrait 在 SENSOR 可用时即可工作，
 * 拒绝 NOTIFICATION / USAGE / MIC 只是 missing source + coverage 下降 + confidence 降低，
 * **不得**整个 sensing 停止。
 *
 * 核心门控纯函数 [PassiveSensingService.coreSensingGatePasses]（flag + consent + SENSOR 可用）
 * 已存在于 [PassiveSensingService] companion（Phase 6.1 已加），此处不重复定义。
 *
 * 本文件含 Android 上下文判定（[capabilityState]），枚举本身为纯 Kotlin，
 * 可被 model 层（[com.yunjue.echo.mind.model.PortraitAvailability]）与纯 JVM 单测引用。
 */
enum class SensingCapability { SENSOR, SCREEN, USAGE, NOTIFICATION, MIC }

/** Phase 6.1 Permission Degraded：能力状态（PM 规格 §2.1）。 */
enum class CapabilityState { AVAILABLE, DENIED, UNAVAILABLE, DISABLED }

/**
 * 逐能力状态判定（PM 规格 §2.1 / 任务 B）：
 * - SENSOR：传感器硬件存在性 + 被动感知总开关（加速度计/陀螺仪无运行时权限）；
 * - SCREEN：屏幕状态广播接收器（无权限概念，恒 AVAILABLE）；
 * - USAGE：AppOps 使用情况访问（PACKAGE_USAGE_STATS）；
 * - NOTIFICATION：NotificationListenerService 授权；
 * - MIC：RECORD_AUDIO 运行时权限 + 麦克风硬件 + 总开关。
 *
 * @param sensingEnabled 被动感知总开关（PassiveSensingPrefs.passiveSensingEnabled）；
 *   关闭时所有能力一律 DISABLED（用户关闭 consent/开关，PM 规格 DISABLED 语义）。
 */
internal fun capabilityState(
    context: Context,
    capability: SensingCapability,
    sensingEnabled: Boolean = true
): CapabilityState {
    if (!sensingEnabled) return CapabilityState.DISABLED
    return when (capability) {
        SensingCapability.SENSOR ->
            if (hasCoreSensorHardware(context)) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE
        SensingCapability.SCREEN -> CapabilityState.AVAILABLE
        SensingCapability.USAGE ->
            if (hasUsageAccessGranted(context)) CapabilityState.AVAILABLE else CapabilityState.DENIED
        SensingCapability.NOTIFICATION ->
            if (hasNotificationAccessGranted(context)) CapabilityState.AVAILABLE else CapabilityState.DENIED
        SensingCapability.MIC -> when {
            !hasMicHardware(context) -> CapabilityState.UNAVAILABLE
            !hasMicPermissionGranted(context) -> CapabilityState.DENIED
            else -> CapabilityState.AVAILABLE
        }
    }
}

/** 核心传感器硬件是否存在（加速度计或陀螺仪任一存在）。 */
internal fun hasCoreSensorHardware(context: Context): Boolean {
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
    return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
}

/**
 * 使用情况访问（App usage access / PACKAGE_USAGE_STATS）是否已授权。
 *
 * 命名带 Granted 后缀，避免与 [PassiveSensingService.hasUsageAccess]（companion member）
 * 在包作用域产生重名歧义。
 */
internal fun hasUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }
    } catch (e: Exception) {
        false
    }
}

/** 通知使用权（NotificationListenerService）是否已授权。 */
internal fun hasNotificationAccessGranted(context: Context): Boolean = runCatching {
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}.getOrDefault(false)

/** 麦克风硬件是否存在（无硬件 → UNAVAILABLE）。 */
internal fun hasMicHardware(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

/** RECORD_AUDIO 运行时权限是否已授予。 */
internal fun hasMicPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
