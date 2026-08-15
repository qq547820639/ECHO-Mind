package com.yunjue.echo.mind

import com.yunjue.echo.mind.intelligence.ProviderStatus
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.runtime.RuntimeComponentStatus
import com.yunjue.echo.mind.runtime.computeEchoRuntimeHealth
import com.yunjue.echo.mind.runtime.providerComponentHealth
import com.yunjue.echo.mind.runtime.sensingComponentHealth
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * §101 Runtime Tests —— 六态（NOT_AUTHORIZED/STARTING/ACTIVE/DEGRADED/SYSTEM_PAUSED/USER_PAUSED）
 * → 组件聚合（READY/STARTING/DEGRADED/PAUSED/UNAVAILABLE）全矩阵 + health 四组件聚合。
 */
class EchoRuntimeHealthTest {

    @Test
    fun sensingSixStateMapsToComponentStatuses() {
        assertEquals(RuntimeComponentStatus.UNAVAILABLE, sensingComponentHealth(SensingRuntimeStatus.NOT_AUTHORIZED))
        assertEquals(RuntimeComponentStatus.STARTING, sensingComponentHealth(SensingRuntimeStatus.STARTING))
        assertEquals(RuntimeComponentStatus.READY, sensingComponentHealth(SensingRuntimeStatus.ACTIVE))
        assertEquals(RuntimeComponentStatus.DEGRADED, sensingComponentHealth(SensingRuntimeStatus.DEGRADED))
        assertEquals(RuntimeComponentStatus.PAUSED, sensingComponentHealth(SensingRuntimeStatus.SYSTEM_PAUSED))
        assertEquals(RuntimeComponentStatus.PAUSED, sensingComponentHealth(SensingRuntimeStatus.USER_PAUSED))
        // 全枚举覆盖（新增状态漏映射 = 编译期 when 不穷尽会报错；此处再兜底断言）
        SensingRuntimeStatus.entries.forEach { sensingComponentHealth(it) }
    }

    @Test
    fun providerStatusMapsToComponentStatuses() {
        assertEquals(RuntimeComponentStatus.UNAVAILABLE, providerComponentHealth(ProviderStatus.NOT_CONFIGURED))
        assertEquals(RuntimeComponentStatus.READY, providerComponentHealth(ProviderStatus.READY))
        assertEquals(RuntimeComponentStatus.STARTING, providerComponentHealth(ProviderStatus.VALIDATING))
        // 其余（AUTH_FAILED/MODEL_NOT_FOUND/RATE_LIMITED/QUOTA_EXCEEDED/NETWORK_ERROR）→ DEGRADED
        ProviderStatus.entries
            .filter { it !in setOf(ProviderStatus.NOT_CONFIGURED, ProviderStatus.READY, ProviderStatus.VALIDATING) }
            .forEach { assertEquals("$it 应降级", RuntimeComponentStatus.DEGRADED, providerComponentHealth(it)) }
    }

    @Test
    fun presenceAssembledIsReadyElseDegraded() {
        val healthNoPresence = computeEchoRuntimeHealth(
            sensing = SensingRuntimeStatus.ACTIVE,
            presence = null,
            provider = ProviderStatus.READY,
        )
        assertEquals(RuntimeComponentStatus.DEGRADED, healthNoPresence.presence)
        val healthWithPresence = computeEchoRuntimeHealth(
            sensing = SensingRuntimeStatus.ACTIVE,
            presence = EchoPresenceState(updatedAt = Instant.EPOCH),
            provider = ProviderStatus.READY,
        )
        assertEquals(RuntimeComponentStatus.READY, healthWithPresence.presence)
    }

    @Test
    fun healthAggregatesAllFourComponents() {
        val health = computeEchoRuntimeHealth(
            sensing = SensingRuntimeStatus.USER_PAUSED,
            presence = null,
            provider = ProviderStatus.AUTH_FAILED,
        )
        assertEquals(RuntimeComponentStatus.PAUSED, health.sensing)
        assertEquals(RuntimeComponentStatus.DEGRADED, health.presence)
        assertEquals(RuntimeComponentStatus.DEGRADED, health.intelligence)
        // memory：本地 Room 常驻（fail-closed 启动失败 = 进程级，不在此状态面）
        assertEquals(RuntimeComponentStatus.READY, health.memory)
    }

    @Test
    fun healthComputationIsDeterministic() {
        val a = computeEchoRuntimeHealth(SensingRuntimeStatus.ACTIVE, null, ProviderStatus.VALIDATING)
        val b = computeEchoRuntimeHealth(SensingRuntimeStatus.ACTIVE, null, ProviderStatus.VALIDATING)
        assertEquals(a, b)
    }
}
