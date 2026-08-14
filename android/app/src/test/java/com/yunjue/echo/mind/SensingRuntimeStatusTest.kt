package com.yunjue.echo.mind

import com.yunjue.echo.mind.sensing.RUNTIME_COPY_SYSTEM_PAUSED
import com.yunjue.echo.mind.sensing.RUNTIME_COPY_USER_PAUSED
import com.yunjue.echo.mind.sensing.SensingRuntimeInputs
import com.yunjue.echo.mind.sensing.SensingRuntimeStatus
import com.yunjue.echo.mind.sensing.resolveSensingRuntimeStatus
import com.yunjue.echo.mind.sensing.sensingRuntimeStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 1：SensingRuntimeStatus 六态解析纯函数回归。
 *
 * 铁律（Master Prompt PART 67）：「关闭」永远只表示用户行为（USER_PAUSED）；
 * 网络错误 / flag 失败 / 同步失败 / 系统杀进程 → SYSTEM_PAUSED / DEGRADED，
 * 绝不伪装成用户关闭。
 */
class SensingRuntimeStatusTest {

    private fun inputs(
        everAuthorized: Boolean = true,
        userConsentOn: Boolean = true,
        serviceStarted: Boolean = true,
        hasEverCollected: Boolean = true,
        collectionFresh: Boolean = true,
        coreSensorsAvailable: Boolean = true,
        optionalCapabilityDegraded: Boolean = false,
        persistenceFailing: Boolean = false,
    ) = SensingRuntimeInputs(
        everAuthorized = everAuthorized,
        userConsentOn = userConsentOn,
        serviceStarted = serviceStarted,
        hasEverCollected = hasEverCollected,
        collectionFresh = collectionFresh,
        coreSensorsAvailable = coreSensorsAvailable,
        optionalCapabilityDegraded = optionalCapabilityDegraded,
        persistenceFailing = persistenceFailing,
    )

    @Test
    fun neverAuthorizedIsNotAuthorized() {
        assertEquals(
            SensingRuntimeStatus.NOT_AUTHORIZED,
            resolveSensingRuntimeStatus(inputs(everAuthorized = false))
        )
    }

    @Test
    fun authorizedButConsentOffIsUserPaused() {
        // 唯一的「关闭」语义：用户主动关闭
        assertEquals(
            SensingRuntimeStatus.USER_PAUSED,
            resolveSensingRuntimeStatus(inputs(userConsentOn = false))
        )
    }

    @Test
    fun consentOnButServiceDeadIsSystemPaused() {
        // 系统杀进程 / flag fail-closed / 电池策略 → 系统暂停，绝不显示「关闭」
        assertEquals(
            SensingRuntimeStatus.SYSTEM_PAUSED,
            resolveSensingRuntimeStatus(inputs(serviceStarted = false))
        )
    }

    @Test
    fun serviceRunningWithoutAnyCollectionIsStarting() {
        assertEquals(
            SensingRuntimeStatus.STARTING,
            resolveSensingRuntimeStatus(
                inputs(hasEverCollected = false, collectionFresh = false)
            )
        )
    }

    @Test
    fun serviceRunningButStaleHeartbeatIsSystemPaused() {
        // 假活检测：曾经采集过、现在心跳过期（Doze/后台限制）→ 系统暂停
        assertEquals(
            SensingRuntimeStatus.SYSTEM_PAUSED,
            resolveSensingRuntimeStatus(inputs(collectionFresh = false))
        )
    }

    @Test
    fun freshCollectionAndAllGoodIsActive() {
        assertEquals(
            SensingRuntimeStatus.ACTIVE,
            resolveSensingRuntimeStatus(inputs())
        )
    }

    @Test
    fun degradedVariantsDoNotBecomeUserPaused() {
        // 核心传感器缺失 / optional 能力降级 / 持久化失败 → DEGRADED（ECHO 仍在运行）
        assertEquals(
            SensingRuntimeStatus.DEGRADED,
            resolveSensingRuntimeStatus(inputs(coreSensorsAvailable = false))
        )
        assertEquals(
            SensingRuntimeStatus.DEGRADED,
            resolveSensingRuntimeStatus(inputs(optionalCapabilityDegraded = true))
        )
        assertEquals(
            SensingRuntimeStatus.DEGRADED,
            resolveSensingRuntimeStatus(inputs(persistenceFailing = true))
        )
    }

    @Test
    fun onlyUserPausedCopySpeaksOfUserClosing() {
        val statuses = SensingRuntimeStatus.entries
        assertEquals(6, statuses.size)
        for (status in statuses) {
            val text = sensingRuntimeStatusText(status)
            assertTrue("状态文案不应为空：$status", text.isNotBlank())
        }
        // 「关闭/暂停」的用户语义只能出现在 USER_PAUSED
        assertEquals(RUNTIME_COPY_USER_PAUSED, sensingRuntimeStatusText(SensingRuntimeStatus.USER_PAUSED))
        assertTrue(RUNTIME_COPY_USER_PAUSED.contains("你"))
        assertFalse(
            "系统暂停文案不得暗示用户关闭",
            RUNTIME_COPY_SYSTEM_PAUSED.contains("关闭") || RUNTIME_COPY_SYSTEM_PAUSED.contains("你关闭")
        )
        // 六态文案唯一（防文案混用）
        assertEquals(statuses.size, statuses.map { sensingRuntimeStatusText(it) }.toSet().size)
    }
}
