package com.yunjue.echo.mind.presencevisual

/**
 * EchoVisualClock — V3 §N 视觉全局时钟（boot-global monotonic）。
 *
 * 同一 ECHO 的视觉相位（呼吸/轨道/丝相位）永远不因 recompose / navigation /
 * visibility 变化而重启回 phase 0：所有组件一律消费本时钟，
 * 禁止组件本地 `withFrameNanos { start }` 起表（帧循环只负责请求帧）。
 * Journey 历史重建例外——用确定性 canonical 时间（JOURNEY_CANONICAL_TIME_SECONDS）。
 */
object EchoVisualClock {

    /** boot-global monotonic visual time（纳秒；与组件生命周期无关）。 */
    fun nowNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()

    /**
     * boot-global monotonic visual time（秒）。
     * §N 精度约束：仅供小时间锚/展示用途——绝对秒数以 Float 携带在大 uptime 下
     * ulp 超过帧间隔（≈24 天 ulp≈0.25s）；渲染/运动求值一律消费 [nowNanos]（Long）。
     */
    fun nowSeconds(): Float = nowNanos() / 1_000_000_000f
}
