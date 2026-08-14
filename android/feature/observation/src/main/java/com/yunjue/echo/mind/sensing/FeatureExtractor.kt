package com.yunjue.echo.mind.sensing

import com.yunjue.echo.mind.model.DerivedFeatureInput
import java.time.Instant
import kotlin.math.sqrt

/**
 * 特征提取器：从**不可变窗口快照**提取 5 分钟窗口派生特征（Phase 4 Immutable Window）。
 *
 * - 输入为 [SensingEventHub.HubSnapshot]（不可变，一次取定）——**绝不在此方法内重新读 live hub**；
 * - retry 重处理同一 snapshot：相同输入 → 相同输出（纯函数）；
 * - 传感器样本 [SensorSample] 带 timestampMs，按窗口 [windowStart, windowEnd) 精确过滤
 *   （Phase 4.1）——新到达事件只能属于后一个窗口；
 * - Screen carry-over（Phase 4.3）：窗口开始前屏幕已 ON 的跨窗口状态由
 *   [ScreenCollector.screenStateCarry] 传入，窗口内 duration 从窗口起点起算；
 * - App foreground（Phase 4.3）：top-app duration 用 [AppActivityCollector] 提供的
 *   foregroundSinceMs 跨窗口计算，不把 `windowEnd - lastEvent.timestamp` 错误称为 top-app duration；
 * - 产出 [DerivedFeatureInput]（schema_version / source / window / summary / vector）；
 * - 原始传感数据仅在端侧处理，不上云。
 */
class FeatureExtractor {

    /**
     * 从不可变快照提取并聚合窗口特征（Phase 4 主路径）。
     *
     * @param screenCarryState 屏幕 carry-over 状态（null = 窗口开始前无 ON 状态）
     */
    fun extractFromSnapshot(
        windowStart: Instant,
        windowEnd: Instant,
        snapshot: SensingEventHub.HubSnapshot,
        screenCarryState: ScreenCollector.ScreenStateCarry? = null,
        appForeground: AppActivityCollector.AppForegroundState? = null
    ): List<DerivedFeatureInput> = extract(
        windowStart = windowStart,
        windowEnd = windowEnd,
        accelSamples = snapshot.accel,
        gyroSamples = snapshot.gyro,
        screenEvents = snapshot.screen,
        notifications = snapshot.notifications,
        appActivities = snapshot.appActivities,
        screenCarryState = screenCarryState,
        appForeground = appForeground
    )

    /**
     * 核心聚合逻辑（纯函数，便于单测）。
     *
     * 返回空列表表示窗口内无任何信号数据。
     */
    fun extract(
        windowStart: Instant,
        windowEnd: Instant,
        accelSamples: List<SensorSample> = emptyList(),
        gyroSamples: List<SensorSample> = emptyList(),
        screenEvents: List<ScreenCollector.ScreenEvent> = emptyList(),
        notifications: List<NotificationCollector.NotificationMeta> = emptyList(),
        appActivities: List<AppActivityCollector.AppActivity> = emptyList(),
        screenCarryState: ScreenCollector.ScreenStateCarry? = null,
        appForeground: AppActivityCollector.AppForegroundState? = null
    ): List<DerivedFeatureInput> {
        val windowStartMs = windowStart.toEpochMilli()
        val windowEndMs = windowEnd.toEpochMilli()

        // Phase 4.1：传感器样本按 timestampMs 精确过滤窗口（新事件只能属于后一个窗口）
        val accelInWindow = accelSamples.filter { it.timestampMs in windowStartMs until windowEndMs }
        val gyroInWindow = gyroSamples.filter { it.timestampMs in windowStartMs until windowEndMs }
        val screenInWindow = screenEvents.filter { it.timestamp in windowStartMs until windowEndMs }
        val notifInWindow = notifications.filter { it.timestamp in windowStartMs until windowEndMs }
        val appInWindow = appActivities.filter { it.timestamp in windowStartMs until windowEndMs }

        // 窗口内全部信号为空则不产出
        if (accelInWindow.isEmpty() && gyroInWindow.isEmpty() &&
            screenInWindow.isEmpty() && notifInWindow.isEmpty() && appInWindow.isEmpty()
        ) {
            return emptyList()
        }

        val vector = buildVector(
            accelInWindow, gyroInWindow, screenInWindow, notifInWindow, appInWindow,
            windowStartMs, windowEndMs, screenCarryState, appForeground
        )
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
                schemaVersion = "passive-core-v1",
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
     * 维度布局（与后端 schema_registry passive-core-v1 对齐）：
     * - 加速度统计量（8 维）：mean_x/y/z, std_x/y/z, magnitude_mean, magnitude_std
     * - 陀螺仪统计量（6 维）：mean_x/y/z, std_x/y/z
     * - 屏幕事件（3 维）：on_count, off_count, on_duration_ms
     * - 通知（3 维）：total_count, social_count, other_count
     * - App 活跃（2 维）：switch_count, top_app_duration_ms
     * 共 22 维，远低于 256 维上限。
     */
    private fun buildVector(
        accel: List<SensorSample>,
        gyro: List<SensorSample>,
        screen: List<ScreenCollector.ScreenEvent>,
        notifications: List<NotificationCollector.NotificationMeta>,
        apps: List<AppActivityCollector.AppActivity>,
        windowStartMs: Long,
        windowEndMs: Long,
        screenCarryState: ScreenCollector.ScreenStateCarry?,
        appForeground: AppActivityCollector.AppForegroundState?
    ): List<Float> {
        val vec = mutableListOf<Float>()

        // ===== 加速度统计量（8 维）=====
        if (accel.isNotEmpty()) {
            val xs = accel.map { it.x }
            val ys = accel.map { it.y }
            val zs = accel.map { it.z }
            val mags = accel.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
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
            val xs = gyro.map { it.x }
            val ys = gyro.map { it.y }
            val zs = gyro.map { it.z }
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
        val screenOnDurationMs = computeScreenOnDurationMs(screen, windowStartMs, windowEndMs, screenCarryState)
        vec += screenOnCount.toFloat()
        vec += screenOffCount.toFloat()
        vec += screenOnDurationMs.toFloat()

        // ===== 通知（3 维）=====
        val notifTotal = notifications.size
        val notifSocial = notifications.count { it.category == NOTIFICATION_CATEGORY_SOCIAL }
        val notifOther = notifTotal - notifSocial
        vec += notifTotal.toFloat()
        vec += notifSocial.toFloat()
        vec += notifOther.toFloat()

        // ===== App 活跃（2 维）=====
        val appSwitchCount = if (apps.size <= 1) 0 else apps.size - 1
        val topAppDurationMs = computeTopAppDurationMs(apps, windowStartMs, windowEndMs, appForeground)
        vec += appSwitchCount.toFloat()
        vec += topAppDurationMs.toFloat()

        // 硬性限制 ≤256 维（当前 22 维，远低于上限）
        return if (vec.size > MAX_VECTOR_DIM) vec.take(MAX_VECTOR_DIM) else vec.toList()
    }

    /**
     * 生成中文自然语言摘要（≤4000 字）。
     */
    private fun buildSummary(
        accel: List<SensorSample>,
        gyro: List<SensorSample>,
        screen: List<ScreenCollector.ScreenEvent>,
        notifications: List<NotificationCollector.NotificationMeta>,
        apps: List<AppActivityCollector.AppActivity>,
        windowStartMs: Long,
        windowEndMs: Long
    ): String {
        val parts = mutableListOf<String>()

        // 活动量（基于加速度幅值标准差）
        if (accel.isNotEmpty()) {
            val mags = accel.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
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
        accel: List<SensorSample>,
        gyro: List<SensorSample>,
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

    /**
     * 计算屏幕开启总时长（ms），基于 ON/OFF 事件配对（Phase 4.3：carry-over state）。
     *
     * - 窗口开始前屏幕已 ON（[screenCarryState] 的 screenOnSinceMs ≤ windowStartMs）：
     *   duration 从 windowStartMs 起算（不丢失跨窗口开启段）；
     * - 窗口内 ON → OFF 配对正常累计；
     * - 窗口结束时仍为开启状态，截断到 windowEnd（含 carry 段）。
     */
    private fun computeScreenOnDurationMs(
        events: List<ScreenCollector.ScreenEvent>,
        windowStartMs: Long,
        windowEndMs: Long,
        screenCarryState: ScreenCollector.ScreenStateCarry?
    ): Long {
        if (events.isEmpty() && (screenCarryState?.screenOnSinceMs == null || screenCarryState.screenOnSinceMs > windowStartMs)) {
            return 0L
        }
        val sorted = events.sortedBy { it.timestamp }
        var total = 0L
        // carry-over：窗口开始前已 ON（且窗口内无更早的 OFF 事件）
        val carryOn = screenCarryState?.let { carry ->
            if (carry.screenOnSinceMs <= windowStartMs) {
                val firstOffBeforeWindowEnd = sorted.firstOrNull { it.state == ScreenCollector.ScreenState.OFF }
                // 若窗口内第一个事件是 OFF，carry 段只到该 OFF 事件；否则到窗口结束
                if (firstOffBeforeWindowEnd != null) {
                    (firstOffBeforeWindowEnd.timestamp - windowStartMs).coerceAtLeast(0L)
                } else {
                    (windowEndMs - windowStartMs).coerceAtLeast(0L)
                }
            } else null
        }
        if (carryOn != null) {
            total += carryOn
        }
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
        // 窗口结束时仍为开启状态，截断到 windowEnd（含 carry 段起点之后的事件）
        onTime?.let { start ->
            total += (windowEndMs - start).coerceAtLeast(0L)
        }
        return total
    }

    /**
     * 计算 top-app 前台时长（ms）（Phase 4.3：跨窗口 foreground_since）。
     *
     * 修复旧错误：`windowEnd - lastEvent.timestamp` 只是"最后一个事件到窗口结束"的间隔，
     * 不是真实 top-app duration。正确语义：
     * - 使用 [AppActivityCollector.AppForegroundState.foregroundSinceMs]（该 package 开始前台时刻）：
     *   duration = min(windowEnd, 当前仍在前台 ? now : 切换时刻) - max(windowStart, foregroundSinceMs)；
     * - 窗口内发生切换：以最后一次切换事件为准（apps.last() 指示当前前台）。
     */
    private fun computeTopAppDurationMs(
        apps: List<AppActivityCollector.AppActivity>,
        windowStartMs: Long,
        windowEndMs: Long,
        appForeground: AppActivityCollector.AppForegroundState?
    ): Long {
        val lastEvent = apps.maxByOrNull { it.timestamp }
        if (lastEvent == null && appForeground == null) return 0L
        // foregroundSince：优先取 carry 状态（跨窗口），否则取窗口内最后一个切换事件
        val since = appForeground?.foregroundSinceMs?.coerceAtMost(lastEvent?.timestamp ?: Long.MAX_VALUE)
            ?: lastEvent?.timestamp
            ?: return 0L
        if (since >= windowEndMs) return 0L
        val end = windowEndMs
        return (end - maxOf(since, windowStartMs)).coerceAtLeast(0L)
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

        /** 通知 category 归类为「社交」的字符串（与 NotificationCollector 透传的 category 对齐）。 */
        const val NOTIFICATION_CATEGORY_SOCIAL = "social"
    }
}
