package com.yunjue.echo.mind.model

import com.yunjue.echo.mind.sensing.CapabilityState
import com.yunjue.echo.mind.sensing.SensingCapability

/**
 * Phase 6.5.2：画像可用性模型（PM 规格 §5.2，内部字段）。
 *
 * Trend 脱离 legacy Profile（fetchProfile / ProfileDisplay）后的数据来源：
 * `GET /v1/me/portraits/today` + `GET /v1/me/baseline/status` + 本地能力判定
 * （[com.yunjue.echo.mind.sensing.capabilityState]）。
 *
 * 纯数据（无 Android 依赖），供 ui 层与纯 JVM 单测共用。
 */
data class PortraitAvailability(
    /** 基线状态：WARMING_UP / EARLY_BASELINE / BASELINE_READY / ... */
    val baselineStatus: String = "UNKNOWN",
    /** 基线有效天数（替代 legacy observationDays）。 */
    val baselineDays: Int = 0,
    /** 今日覆盖度 0..1。 */
    val coverage: Float = 0f,
    /** 缺失 source code（accel/gyro/screen/notification/app_activity）。 */
    val missingSources: List<String> = emptyList(),
    /** 最近成功采集 epoch ms。 */
    val lastCollectedAt: Long = 0L,
    /** 最近成功同步 epoch ms。 */
    val lastSyncedAt: Long = 0L,
    /** 物化状态：materialized / dirty / none（服务端 materialization_state）。 */
    val materializationStatus: String = "none",
    /** SENSOR/SCREEN/USAGE/NOTIFICATION/MIC 能力状态（规格 §2.1）。 */
    val capabilities: List<CapabilityState> = emptyList()
)

/**
 * Phase 6.5.2：本地感知诊断（PM 规格 §5.2，内部字段）。
 *
 * 数据来源：PassiveSensingPrefs / AppPreferences / 能力判定函数；
 * 供 Trend NO_DATA 原因推导（[com.yunjue.echo.mind.ui.resolveTrendNoDataReason]）与支持页诊断。
 */
data class SensingDiagnostics(
    /** 逐能力状态。 */
    val capabilities: Map<SensingCapability, CapabilityState> = emptyMap(),
    /** 感知是否激活（服务启动中 / consent 已开）。 */
    val sensingActive: Boolean = false,
    /** 最近成功采集 epoch ms。 */
    val lastCollectionAt: Long = 0L,
    /** 连续窗口持久化失败计数（成功后清零）。 */
    val consecutivePersistenceFailures: Int = 0,
    /** 待上传事件数（outbox pending）。 */
    val pendingUploadCount: Int = 0
)
