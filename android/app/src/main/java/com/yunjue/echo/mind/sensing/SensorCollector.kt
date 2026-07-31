package com.yunjue.echo.mind.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 传感器采集器：注册加速度计 + 陀螺仪监听，原始数据在内存缓冲。
 *
 * - 缓冲容量有限，超出自动丢弃最旧数据
 * - 仅端侧处理，不上云不落盘
 * - start/stop 幂等，重复调用安全
 */
class SensorCollector(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** 原始数据内存缓冲（仅端侧）。 */
    val accelerometerBuffer = ConcurrentLinkedDeque<FloatArray>()
    val gyroscopeBuffer = ConcurrentLinkedDeque<FloatArray>()

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

    /** 清空缓冲（用于测试或回收）。 */
    fun clearBuffers() {
        accelerometerBuffer.clear()
        gyroscopeBuffer.clear()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        val snapshot = values.copyOf()
        when (event.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerBuffer.offerLast(snapshot)
                trim(accelerometerBuffer)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroscopeBuffer.offerLast(snapshot)
                trim(gyroscopeBuffer)
            }
        }
    }

    private fun trim(buffer: ConcurrentLinkedDeque<FloatArray>) {
        while (buffer.size > MAX_BUFFER_SIZE) buffer.pollFirst()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val MAX_BUFFER_SIZE = 1024
    }
}
