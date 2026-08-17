package com.yunjue.echo.mind.runtime

import android.content.Context
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.data.PassiveSensingPrefs
import com.yunjue.echo.mind.data.PresenceRepository
import com.yunjue.echo.mind.intelligence.AiProviderManager
import com.yunjue.echo.mind.intelligence.ProviderStatus
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.SensingRuntimeInputs
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.sensing.hasCoreSensorHardware
import com.yunjue.echo.mind.sensing.hasMicPermissionGranted
import com.yunjue.echo.mind.model.resolveSensingRuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Echo Runtime 统一运行时状态（Master Prompt v2 §13/§17）：
 * UI 不再各自拼状态；Sensing / Presence / Provider 三类运行时由协调器统一广播。
 */
data class EchoRuntimeState(
    val sensing: SensingRuntimeStatus,
    val presence: EchoPresenceState?,
    val provider: ProviderStatus,
)

/** v3.2 §3：运行时组件状态（系统聚合层；不取代 SensingRuntimeStatus）。 */
enum class RuntimeComponentStatus {
    READY,
    STARTING,
    DEGRADED,
    PAUSED,
    UNAVAILABLE,
}

/** v3.2 §3：Runtime health 模型（Sensing 六态 + 组件聚合；UI 不拼 Boolean）。 */
data class EchoRuntimeHealth(
    val sensing: RuntimeComponentStatus,
    val presence: RuntimeComponentStatus,
    val intelligence: RuntimeComponentStatus,
    val memory: RuntimeComponentStatus,
)

/** SensingRuntimeStatus → 组件聚合状态（纯映射，单测锚点）。 */
fun sensingComponentHealth(sensing: SensingRuntimeStatus): RuntimeComponentStatus = when (sensing) {
    SensingRuntimeStatus.NOT_AUTHORIZED -> RuntimeComponentStatus.UNAVAILABLE
    SensingRuntimeStatus.STARTING -> RuntimeComponentStatus.STARTING
    SensingRuntimeStatus.ACTIVE -> RuntimeComponentStatus.READY
    SensingRuntimeStatus.DEGRADED -> RuntimeComponentStatus.DEGRADED
    SensingRuntimeStatus.SYSTEM_PAUSED, SensingRuntimeStatus.USER_PAUSED -> RuntimeComponentStatus.PAUSED
}

/** ProviderStatus → 组件聚合状态（纯映射，单测锚点）。 */
fun providerComponentHealth(provider: ProviderStatus): RuntimeComponentStatus = when (provider) {
    ProviderStatus.NOT_CONFIGURED -> RuntimeComponentStatus.UNAVAILABLE
    ProviderStatus.READY -> RuntimeComponentStatus.READY
    ProviderStatus.VALIDATING -> RuntimeComponentStatus.STARTING
    else -> RuntimeComponentStatus.DEGRADED
}

/**
 * v3.2 §3/§101 — Runtime health 聚合（纯函数，六态矩阵单测锚点）：
 * - sensing ← 六态映射（NOT_AUTHORIZED/STARTING/ACTIVE/DEGRADED/SYSTEM_PAUSED/USER_PAUSED）；
 * - presence ← 已组装 READY / 未组装 DEGRADED（无状态不编造）；
 * - intelligence ← Provider 状态映射；
 * - memory ← 本地 Room 常驻 READY（不可用仅在 DB fail-closed 启动失败 = 进程级）。
 */
fun computeEchoRuntimeHealth(
    sensing: SensingRuntimeStatus,
    presence: EchoPresenceState?,
    provider: ProviderStatus,
): EchoRuntimeHealth = EchoRuntimeHealth(
    sensing = sensingComponentHealth(sensing),
    presence = if (presence != null) RuntimeComponentStatus.READY else RuntimeComponentStatus.DEGRADED,
    intelligence = providerComponentHealth(provider),
    memory = RuntimeComponentStatus.READY,
)

/**
 * EchoRuntimeCoordinator —— 运行时协调器（v2 §13）。
 *
 * 职责：
 * - 监听系统真实权限 → 计算唯一 SensingRuntimeStatus（六态铁律：只有 USER_PAUSED = 用户关闭）；
 * - 触发 Presence 组装（PresenceRepository 仍是唯一组装方）；
 * - 广播 Provider 状态（轻量：是否配置 + 最近一次健康检查结果，不主动发网络请求）；
 * - 避免 UI 重复推导（EchoSceneScreen / Journey / Me 全部消费本协调器）。
 */
class EchoRuntimeCoordinator(
    private val appContext: Context,
    private val preferences: AppPreferences,
    private val passiveSensingPrefs: PassiveSensingPrefs,
    private val presenceRepository: PresenceRepository,
    private val aiProviderManager: AiProviderManager,
) {
    private val _sensing = MutableStateFlow(
        if (preferences.onboardingCompleted) SensingRuntimeStatus.STARTING else SensingRuntimeStatus.NOT_AUTHORIZED
    )

    /** 唯一感知运行时状态。 */
    val sensing: StateFlow<SensingRuntimeStatus> = _sensing

    /** 唯一 Current ECHO State（由 PresenceRepository 组装）。 */
    val presence: StateFlow<EchoPresenceState?> = presenceRepository.state

    private val _provider = MutableStateFlow(providerSnapshot())
    val provider: StateFlow<ProviderStatus> = _provider

    /** 组合运行时状态（冷流；UI 用 collectAsStateWithLifecycle 订阅）。 */
    val state: kotlinx.coroutines.flow.Flow<EchoRuntimeState> =
        kotlinx.coroutines.flow.combine(sensing, presence, provider) { s, p, pr ->
            EchoRuntimeState(sensing = s, presence = p, provider = pr)
        }

    /** v3.2 §3：Runtime health（组件级统一状态，UI 不拼 Boolean）。 */
    val health: kotlinx.coroutines.flow.Flow<EchoRuntimeHealth> =
        kotlinx.coroutines.flow.combine(sensing, presence, provider) { s, p, pr ->
            computeEchoRuntimeHealth(sensing = s, presence = p, provider = pr)
        }

    /**
     * 以系统真实权限状态刷新感知六态（唯一事实来源）。
     * 铁律：flag 失败/系统杀进程/心跳过期 → SYSTEM_PAUSED；「关闭」只来自用户。
     */
    suspend fun refreshSensingRuntime() {
        val now = System.currentTimeMillis()
        val micEnabled = passiveSensingPrefs.micEnabled.first()
        _sensing.value = resolveSensingRuntimeStatus(
            SensingRuntimeInputs(
                everAuthorized = preferences.onboardingCompleted,
                userConsentOn = passiveSensingPrefs.passiveSensingEnabled.first(),
                serviceStarted = preferences.sensingActive,
                hasEverCollected = preferences.lastCollectionTimestamp > 0L,
                collectionFresh = now - preferences.lastCollectionTimestamp <= PresenceRepository.RUNTIME_FRESH_MS,
                coreSensorsAvailable = hasCoreSensorHardware(appContext),
                optionalCapabilityDegraded = micEnabled && !hasMicPermissionGranted(appContext),
                persistenceFailing = preferences.consecutivePersistenceFailures > 0,
            )
        )
    }

    /** 刷新 Provider 轻量状态（不发网络请求；深度健康检查走 AI Intelligence 页）。 */
    fun refreshProviderStatus() {
        _provider.value = providerSnapshot()
    }

    /** 全量刷新：权限真值 → 六态 + Presence 组装 + Provider 状态。 */
    suspend fun refreshAll() {
        refreshSensingRuntime()
        presenceRepository.refresh()
        refreshProviderStatus()
    }

    private fun providerSnapshot(): ProviderStatus =
        if (aiProviderManager.hasProvider()) ProviderStatus.READY else ProviderStatus.NOT_CONFIGURED
}
