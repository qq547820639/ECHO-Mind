package com.yunjue.echo.mind.sensing

/**
 * ERA 32 R26：进程级感知同意门控（内存标志）。
 *
 * 感知服务通过三重门控（consent + flag + 核心传感器）后置 true，停止/撤回置 false；
 * 系统独立绑定的 [NotificationCollector] 按此门控写入——此前通知元数据在
 * consent 关闭期间仍持续累积（fail-soft 隐私缺口）。
 */
object SensingConsentGate {
    @Volatile
    var active: Boolean = false
}
