package com.yunjue.echo.mind.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * 内存传感器样本（Phase 4.1：带时间戳，精确窗口归属）。
 *
 * - 仅存在于进程内存：不进入 Room、不进入 Outbox、不上传（产品契约）；
 * - timestampMs 为样本采集时刻（epoch ms），供 [FeatureExtractor] 按 5 分钟窗口精确过滤，
 *   避免窗口边界样本被归入错误窗口；
 * - sensorType 保留（ACCELEROMETER / GYROSCOPE），x/y/z 为传感器三轴读数。
 */
data class SensorSample(
    val timestampMs: Long,
    val sensorType: Int,
    val x: Float,
    val y: Float,
    val z: Float
) {
    /** 便捷：转为 FloatArray（旧接口兼容 / 统计用）。 */
    fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)
}

/**
 * ERA 32 R22（时钟基准修复）：SensorEvent.timestamp 是**开机纳秒**
 * （与 SystemClock.elapsedRealtimeNanos 同基准），不是 epoch——
 * 直接除 1e6 得到开机毫秒，会被 [FeatureExtractor] 的 epoch 窗口过滤全部丢弃。
 * 用「当前 epoch − 当前开机时间」偏移量把开机纳秒换算成 epoch 毫秒，保留样本级精度；
 * timestamp ≤ 0（异常事件）时回退当前 epoch。
 */
fun sensorEventTimestampToEpochMs(
    eventTimestampNs: Long,
    nowEpochMs: Long,
    nowElapsedRealtimeNs: Long,
): Long =
    if (eventTimestampNs > 0L) {
        nowEpochMs + (eventTimestampNs - nowElapsedRealtimeNs) / 1_000_000L
    } else {
        nowEpochMs
    }

/**
 * 传感器采集器：注册加速度计 + 陀螺仪监听，样本只写入 [SensingEventHub]。
 *
 * - 单一数据源（Batch A v0.6.2）：本采集器**不保留本地缓冲**，只写 hub；
 *   snapshot 委托 hub（见 [snapshotAccel] / [snapshotGyro]）
 * - 缓冲容量由 hub 统一管理（上限 [MAX_BUFFER_SIZE]，超出自动丢弃最旧数据）
 * - 仅端侧处理，不上云不落盘
 * - start/stop 幂等，重复调用安全
 *
 * **Phase 4.1（Sensor Timestamp）**：样本携带采集时刻（epoch ms，精确窗口归属）——
 * 由开机纳秒 event.timestamp 经 [sensorEventTimestampToEpochMs] 换算而来。
 */
class SensorCollector(context: Context, private val hub: SensingEventHub) : SensorEventListener {
    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 是否已注册监听。 */
    @Volatile
    var running: Boolean = false
        private set

    fun start() {
        if (running) return
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        running = true
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    /** 加速度样本快照（委托 hub，单一数据源）。 */
    fun snapshotAccel(): List<SensorSample> = hub.snapshotAccel()

    /** 陀螺仪样本快照（委托 hub，单一数据源）。 */
    fun snapshotGyro(): List<SensorSample> = hub.snapshotGyro()

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (values.size < 3) return
        // ERA 32 R22（时钟基准修复）：event.timestamp 是开机纳秒，不是 epoch——
        // 直接除 1e6 得到开机毫秒，会被 FeatureExtractor 的 epoch 窗口过滤全部丢弃，
        // 导致 accel/gyro（活动量）特征从未产出。
        val timestampMs = sensorEventTimestampToEpochMs(
            eventTimestampNs = event.timestamp,
            nowEpochMs = System.currentTimeMillis(),
            nowElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
        )
        val sample = SensorSample(
            timestampMs = timestampMs,
            sensorType = event.sensor?.type ?: Sensor.TYPE_ACCELEROMETER,
            x = values[0],
            y = values[1],
            z = values[2]
        )
        when (event.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> hub.onAccelSample(sample)
            Sensor.TYPE_GYROSCOPE -> hub.onGyroSample(sample)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /**
         * hub 对加速度/陀螺仪缓冲的容量上限（Phase 4.2 评估）。
         *
         * SENSOR_DELAY_NORMAL ≈ 200ms/样本 → 5 分钟约 1500 样本；
         * 旧值 1024 会在窗口前半段丢弃样本（容量不足，静默丢失）。
         * 新值 4096 覆盖 5 分钟 @ 200ms 采样（1500）并留 2.7 倍余量，
         * 同时为 flush 失败重试保留缓冲（ACK 语义下失败窗口不丢数据）。
         * 内存开销：4096 × SensorSample（约 24B）≈ 100KB，可忽略。
         */
        const val MAX_BUFFER_SIZE = 4096
    }
}

