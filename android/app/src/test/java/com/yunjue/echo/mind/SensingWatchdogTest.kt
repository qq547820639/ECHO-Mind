package com.yunjue.echo.mind

import com.yunjue.echo.mind.sensing.SensingWatchdog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 1 收尾：感知自愈看门狗决策回归。
 * 铁律：用户 consent 关闭 → 绝不重启（用户选择优先）。
 */
class SensingWatchdogTest {

    private val NOW = 1_800_000_000_000L
    private val STALE = SensingWatchdog.STALE_MS

    @Test
    fun consentOffNeverRestarts() {
        assertFalse(SensingWatchdog.shouldAttemptRestart(consentOn = false, serviceActive = false, lastCollectionAt = 0L, now = NOW))
        assertFalse(SensingWatchdog.shouldAttemptRestart(consentOn = false, serviceActive = true, lastCollectionAt = NOW - 2 * STALE, now = NOW))
    }

    @Test
    fun deadServiceRestarts() {
        assertTrue(SensingWatchdog.shouldAttemptRestart(consentOn = true, serviceActive = false, lastCollectionAt = 0L, now = NOW))
    }

    @Test
    fun activeFreshServiceDoesNotRestart() {
        assertFalse(SensingWatchdog.shouldAttemptRestart(consentOn = true, serviceActive = true, lastCollectionAt = NOW - 1000L, now = NOW))
    }

    @Test
    fun activeButStaleServiceRestarts() {
        // Doze/后台限制假活：服务在运行但心跳过期 → 尝试重启
        assertTrue(SensingWatchdog.shouldAttemptRestart(consentOn = true, serviceActive = true, lastCollectionAt = NOW - 2 * STALE, now = NOW))
    }

    @Test
    fun activeButNeverCollectedDoesNotRestart() {
        // 刚启动尚无首个窗口：不误判为假活
        assertFalse(SensingWatchdog.shouldAttemptRestart(consentOn = true, serviceActive = true, lastCollectionAt = 0L, now = NOW))
    }
}
