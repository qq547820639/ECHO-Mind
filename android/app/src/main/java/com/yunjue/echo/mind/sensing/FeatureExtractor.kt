package com.yunjue.echo.mind.sensing

import com.yunjue.echo.mind.model.DerivedFeatureInput
import java.time.Instant
import kotlin.math.sqrt

/**
 * 特征提取器：从各 Collector 的内存缓冲读取原始信号，按 5 分钟窗口聚合。
 *
 * - 产出 [DerivedFeatureInput]，包含 schema_version / source / window_start / window_end / summary / vector
 * - summary：中文自然语言摘要，≤4000 字
 * - vector：≤256 维 float（加速度统计量 + 陀螺仪统计量 + 屏幕事件计数 + 通知计数 + App 活跃时长等）
 * - 每个窗口产出一个 DerivedFeatureInput，source 根据主要信号类型选择
 * - 原始传感数据仅在端侧处理，不上云
 */
class FeatureExtractor {

    /**
     * 从各 Collector 缓冲提取并聚合窗口特征（Batch A v0.6.2 后已废弃）。
     *
     * 单一数据源收敛后，SensorCollector / ScreenCollector / AppActivityCollector
     * 不再保留本地缓冲，一律经 [SensingEventHub] 共享层消费，故本方法委托 [extractFromHub]。
     */
    @Deprecated("Collector 本地缓冲已移除，请使用 extractFromHub()")
    fun extractFromCollectors(
        windowStart: Instant,
        windowEnd: Instant,
        hub: SensingEventHub
    ): List<DerivedFeatureInput> = extractFromHub(windowStart, windowEnd, hub)

    /**
     * 从 [SensingEventHub] 共享缓冲提取并聚合窗口特征（T02 采集链路主路径）。
     *
     * NotificationCollector 由系统独立绑定，其数据经 hub 共享层到达此处；
     * accel/gyro/screen/app_activity 亦由各 Collector 写入 hub。
     */
    fun extractFromHub(
        windowStart: Instant,
        windowEnd: Instant,
        hub: SensingEventHub
    ): List<DerivedFeatureInput> = extract(
        windowStart = windowStart,
        windowEnd = windowEnd,
        accelSamples = hub.snapshotAccel(),
        gyroSamples = hub.snapshotGyro(),
        screenEvents = hub.snapshotScreen(),
        notifications = hub.snapshotNotifications(),
        appActivities = hub.snapshotAppActivity()
    )

    /**
     * 核心聚合逻辑（纯函数，便于单测）。
     *
     * 返回空列表表示窗口内无任何信号数据。
     */
    fun extract(
        windowStart: Instant,
        windowEnd: Instant,
        accelSamples: List<FloatArray> = emptyList(),
        gyroSamples: List<FloatArray> = emptyList(),
        screenEvents: List<ScreenCollector.ScreenEvent> = emptyList(),
        notifications: List<NotificationCollector.NotificationMeta> = emptyList(),
        appActivities: List<AppActivityCollector.AppActivity> = emptyList()
    ): List<DerivedFeatureInput> {
        val windowStartMs = windowStart.toEpochMilli()
        val windowEndMs = windowEnd.toEpochMilli()

        // 传感器缓冲无时间戳，整体视为窗口内数据
        val accelInWindow = accelSamples
        val gyroInWindow = gyroSamples
        val screenInWindow = screenEvents.filter { it.timestamp in windowStartMs until windowEndMs }
        val notifInWindow = notifications.filter { it.timestamp in windowStartMs until windowEndMs }
        val appInWindow = appActivities.filter { it.timestamp in windowStartMs until windowEndMs }

        // 窗口内全部信号为空则不产出
        if (accelInWindow.isEmpty() && gyroInWindow.isEmpty() &&
            screenInWindow.isEmpty() && notifInWindow.isEmpty() && appInWindow.isEmpty()
        ) {
            return emptyList()
        }

        val vector = buildVector(accelInWindow, gyroInWindow, screenInWindow, notifInWindow, appInWindow, windowStartMs, windowEndMs)
        val summary = buildSummary(accelInWindow, gyroInWindow, screenInWindow, notifInWindow, appInWindow, windowStartMs, windowEndMs)
        val source = selectSource(accelInWindow, gyroInWindow, screenInWindow, notifInWindow, appInWindow)
        // 窗口实际覆盖的 modality（与后端 gap_finder.EXPECTED_SOURCES 契约对齐，02b 共享知识 4）
        val sourcesPresent = buildList {
            if (accelInWindow.isNotEmpty()) add("accel")
            if (gyroInWindow.isNotEmpty()) add("gyro")
            if (screenInWindow.isNotEmpty()) add("screen")
            if (notifInWindow.isNotEmpty()) add("notification")
            if (appInWindow.isNotEmpty()) add("app_activity")
        }

        return listOf(
            DerivedFeatureInput(
                schemaVersion = "feat-v1",
                source = source,
                windowStart = windowStart,
                windowEnd = windowEnd,
                summary = summary,
                vector = vector,
                sourcesPresent = sourcesPresent
            )
        )
    }

    /**
     * 构建特征向量（≤256 维 float）。
     *
     * 维度布局：
     * - 加速度统计量（8 维）：mean_x/y/z, std_x/y/z, magnitude_mean, magnitude_std
     * - 陀螺仪统计量（6 维）：mean_x/y/z, std_x/y/z
     * - 屏幕事件（3 维）：on_count, off_count, on_duration_ms
     * - 通知（3 维）：total_count, social_count, other_count
     * - App 活跃（2 维）：switch_count, top_app_duration_ms
     * 共 22 维，远低于 256 维上限。
     */
    private fun buildVector(
        accel: List<FloatArray>,
        gyro: List<FloatArray>,
        screen: List<ScreenCollector.ScreenEvent>,
        notifications: List<NotificationCollector.NotificationMeta>,
        apps: List<AppActivityCollector.AppActivity>,
        windowStartMs: Long,
        windowEndMs: Long
    ): List<Float> {
        val vec = mutableListOf<Float>()

        // ===== 加速度统计量（8 维）=====
        if (accel.isNotEmpty()) {
            val xs = accel.map { it.getOrElse(0) { 0f } }
            val ys = accel.map { it.getOrElse(1) { 0f } }
            val zs = accel.map { it.getOrElse(2) { 0f } }
            val mags = accel.map { sqrt(it.getOrElse(0) { 0f } * it.getOrElse(0) { 0f } + it.getOrElse(1) { 0f } * it.getOrElse(1) { 0f } + it.getOrElse(2) { 0f } * it.getOrElse(2) { 0f }) }
            vec += xs.average().toFloat()
            vec += ys.average().toFloat()
            vec += zs.average().toFloat()
            vec += std(xs).toFloat()
            vec += std(ys).toFloat()
            vec += std(zs).toFloat()
            vec += mags.average().toFloat()
            vec += std(mags).toFloat()
        } else {
            repeat(8) { vec += 0f }
        }

        // ===== 陀螺仪统计量（6 维）=====
        if (gyro.isNotEmpty()) {
            val xs = gyro.map { it.getOrElse(0) { 0f } }
            val ys = gyro.map { it.getOrElse(1) { 0f } }
            val zs = gyro.map { it.getOrElse(2) { 0f } }
            vec += xs.average().toFloat()
            vec += ys.average().toFloat()
            vec += zs.average().toFloat()
            vec += std(xs).toFloat()
            vec += std(ys).toFloat()
            vec += std(zs).toFloat()
        } else {
            repeat(6) { vec += 0f }
        }

        // ===== 屏幕事件（3 维）=====
        val screenOnCount = screen.count { it.state == ScreenCollector.ScreenState.ON }
        val screenOffCount = screen.count { it.state == ScreenCollector.ScreenState.OFF }
        val screenOnDurationMs = computeScreenOnDurationMs(screen, windowStartMs, windowEndMs)
        vec += screenOnCount.toFloat()
        vec += screenOffCount.toFloat()
        vec += screenOnDurationMs.toFloat()

        // ===== 通知（3 维）=====
        val notifTotal = notifications.size
        val notifSocial = notifications.count { it.category == "social" }
        val notifOther = notifTotal - notifSocial
        vec += notifTotal.toFloat()
        vec += notifSocial.toFloat()
        vec += notifOther.toFloat()

        // ===== App 活跃（2 维）=====
        val appSwitchCount = if (apps.size <= 1) 0 else apps.size - 1
        val topAppDurationMs = if (apps.isEmpty()) 0L else (windowEndMs - apps.last().timestamp).coerceAtLeast(0L)
        vec += appSwitchCount.toFloat()
        vec += topAppDurationMs.toFloat()

        // 硬性限制 ≤256 维（当前 22 维，远低于上限）
        return if (vec.size > MAX_VECTOR_DIM) vec.take(MAX_VECTOR_DIM) else vec.toList()
    }

    /**
     * 生成中文自然语言摘要（≤4000 字）。
     */
    private fun buildSummary(
        accel: List<FloatArray>,
        gyro: List<FloatArray>,
        screen: List<ScreenCollector.ScreenEvent>,
        notifications: List<NotificationCollector.NotificationMeta>,
        apps: List<AppActivityCollector.AppActivity>,
        windowStartMs: Long,
        windowEndMs: Long
    ): String {
        val parts = mutableListOf<String>()

        // 活动量（基于加速度幅值标准差）
        if (accel.isNotEmpty()) {
            val mags = accel.map { sqrt(it.getOrElse(0) { 0f } * it.getOrElse(0) { 0f } + it.getOrElse(1) { 0f } * it.getOrElse(1) { 0f } + it.getOrElse(2) { 0f } * it.getOrElse(2) { 0f }) }
            val magStd = std(mags)
            val activityLevel = when {
                magStd < 0.5 -> "低"
                magStd < 2.0 -> "中"
                else -> "高"
            }
            parts += "过去5分钟活动量${activityLevel}"
        }

        // 屏幕事件
        val screenOnCount = screen.count { it.state == ScreenCollector.ScreenState.ON }
        if (screenOnCount > 0) {
            parts += "屏幕开启${screenOnCount}次"
        }

        // 通知
        if (notifications.isNotEmpty()) {
            parts += "收到${notifications.size}条通知"
        }

        // App 切换
        if (apps.size > 1) {
            parts += "切换App${apps.size - 1}次"
        }

        val summary = if (parts.isEmpty()) {
            "过去5分钟无明显活动信号"
        } else {
            parts.joinToString("，") + "。"
        }

        // 硬性截断 ≤4000 字
        return if (summary.length > MAX_SUMMARY_LENGTH) summary.take(MAX_SUMMARY_LENGTH) else summary
    }

    /**
     * 根据主要信号类型选择 source。
     * 优先级：加速度 > 陀螺仪 > 屏幕 > 通知 > App 活跃。
     */
    private fun selectSource(
        accel: List<FloatArray>,
        gyro: List<FloatArray>,
        screen: List<ScreenCollector.ScreenEvent>,
        notifications: List<NotificationCollector.NotificationMeta>,
        apps: List<AppActivityCollector.AppActivity>
    ): String = when {
        accel.isNotEmpty() -> "accel"
        gyro.isNotEmpty() -> "gyro"
        screen.isNotEmpty() -> "screen"
        notifications.isNotEmpty() -> "notification"
        apps.isNotEmpty() -> "app_activity"
        else -> "accel"
    }

    /** 计算屏幕开启总时长（ms），基于 ON/OFF 事件配对。 */
    private fun computeScreenOnDurationMs(
        events: List<ScreenCollector.ScreenEvent>,
        windowStartMs: Long,
        windowEndMs: Long
    ): Long {
        if (events.isEmpty()) return 0L
        val sorted = events.sortedBy { it.timestamp }
        var total = 0L
        var onTime: Long? = null
        for (e in sorted) {
            when (e.state) {
                ScreenCollector.ScreenState.ON -> onTime = e.timestamp.coerceAtLeast(windowStartMs)
                ScreenCollector.ScreenState.OFF -> {
                    onTime?.let { start ->
                        total += (e.timestamp - start).coerceAtLeast(0L)
                        onTime = null
                    }
                }
            }
        }
        // 窗口结束时仍为开启状态，截断到 windowEnd
        onTime?.let { start ->
            total += (windowEndMs - start).coerceAtLeast(0L)
        }
        return total
    }

    /** 标准差（总体）。 */
    private fun std(values: List<Float>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    companion object {
        /** 后端契约：summary 最大长度。 */
        const val MAX_SUMMARY_LENGTH = 4000

        /** 后端契约：vector 最大维度。 */
        const val MAX_VECTOR_DIM = 256

        /** 聚合窗口时长（5 分钟）。 */
        const val WINDOW_DURATION_MS = 5 * 60 * 1000L
    }
}
