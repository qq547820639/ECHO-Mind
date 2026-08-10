package com.yunjue.echo.mind.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 传感器采集器：注册加速度计 + 陀螺仪监听，样本只写入 [SensingEventHub]。
 *
 * - 单一数据源（Batch A v0.6.2）：本采集器**不保留本地缓冲**，只写 hub；
 *   snapshot 委托 hub（见 [snapshotAccel] / [snapshotGyro]）
 * - 缓冲容量由 hub 统一管理（上限 [MAX_BUFFER_SIZE]，超出自动丢弃最旧数据）
 * - 仅端侧处理，不上云不落盘
 * - start/stop 幂等，重复调用安全
 *
 * **T02 结论（关于 accel/gyro buffer 是否加 timestamp）**：
 * 评估后**保留现状（FloatArray 无 timestamp）**，理由：
 * 1. 调度器在固定 5 分钟边界（:00/:05/:10 对齐）flush，flush 时刻缓冲内样本均属刚结束窗口，
 *    边界抖动（<1s）对聚合统计影响可忽略；
 * 2. 加时间戳需同步改动 hub 快照类型 / FeatureExtractor 统计路径 / clearConsumed 语义，
 *    改动面大且无实际收益（delayed callback 场景极少、clock jump 会整体对齐到新边界但不损坏数据）；
 * 3. 最小改动原则：屏幕/通知/App 活跃事件已带时间戳并按窗口过滤，传感器统计无需逐样本过滤。
 * 若未来需要「传感器样本级窗口归属」精度，再引入 TimestampedSensorSample（仅内存，不落盘不上传）。
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
    fun snapshotAccel(): List<FloatArray> = hub.snapshotAccel()

    /** 陀螺仪样本快照（委托 hub，单一数据源）。 */
    fun snapshotGyro(): List<FloatArray> = hub.snapshotGyro()

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        val snapshot = values.copyOf()
        when (event.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> hub.onAccelSample(snapshot)
            Sensor.TYPE_GYROSCOPE -> hub.onGyroSample(snapshot)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** hub 对加速度/陀螺仪缓冲的容量上限。 */
        const val MAX_BUFFER_SIZE = 1024
    }
}
