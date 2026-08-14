package com.yunjue.echo.mind.sensing

/**
 * ERA 1 — 统一感知运行时状态（Sensing Runtime Status）。
 *
 * 六态语义（Master Prompt PART 67 / Presence Architecture §3）：
 * - [NOT_AUTHORIZED]：尚未完成授权（onboarding 未完成）。
 * - [STARTING]：授权完成、服务刚启动、还没有任何采集证据。
 * - [ACTIVE]：正常感知中。
 * - [DEGRADED]：感知运行，但某项能力不可用或本地持久化暂时失败。
 * - [SYSTEM_PAUSED]：系统原因（进程被杀/电池策略/flag fail-closed/采集心跳停滞）。
 * - [USER_PAUSED]：用户自己暂停。
 *
 * 铁律：**「关闭」永远只表示用户行为（USER_PAUSED）。**
 * 网络错误、feature flag 失败、同步失败、系统杀进程 → 只能 SYSTEM_PAUSED / DEGRADED，
 * 绝不伪装成用户关闭。UI 文案一律来自 [sensingRuntimeStatusText]，禁止另行硬编码。
 */
enum class SensingRuntimeStatus {
    NOT_AUTHORIZED,
    STARTING,
    ACTIVE,
    DEGRADED,
    SYSTEM_PAUSED,
    USER_PAUSED,
}

/**
 * 六态解析输入（纯数据，便于构造与单测）。
 *
 * - [everAuthorized]：onboarding 是否已完成（AppPreferences.onboardingCompleted）。
 * - [userConsentOn]：被动感知总开关（PassiveSensingPrefs.passiveSensingEnabled）。
 * - [serviceStarted]：服务是否处于运行态（AppPreferences.sensingActive）。
 * - [hasEverCollected]：历史上是否成功采集过（lastCollectionTimestamp > 0）。
 * - [collectionFresh]：采集心跳是否新鲜（运行时阈值，区别于趋势页的 3 天阈值）。
 * - [coreSensorsAvailable]：核心传感器硬件是否存在。
 * - [optionalCapabilityDegraded]：USAGE/NOTIFICATION/MIC 中任一 DENIED 或 UNAVAILABLE。
 * - [persistenceFailing]：连续窗口持久化失败计数 > 0。
 */
data class SensingRuntimeInputs(
    val everAuthorized: Boolean,
    val userConsentOn: Boolean,
    val serviceStarted: Boolean,
    val hasEverCollected: Boolean = false,
    val collectionFresh: Boolean = false,
    val coreSensorsAvailable: Boolean = true,
    val optionalCapabilityDegraded: Boolean = false,
    val persistenceFailing: Boolean = false,
)

/**
 * 六态纯函数解析（判定顺序即优先级，ERA 1 冻结）：
 *
 * 1. 从未完成授权 → NOT_AUTHORIZED；
 * 2. 授权过但用户关闭开关 → USER_PAUSED（唯一的「关闭」语义）；
 * 3. 开关开但服务未运行 → SYSTEM_PAUSED（系统杀进程 / flag fail-closed / 电池策略）；
 * 4. 服务运行中但从未采集 → STARTING（刚启动，等待首个窗口）；
 * 5. 服务运行中、采集过但心跳过期 → SYSTEM_PAUSED（假活：Doze/后台限制）；
 * 6. 核心传感器缺失 / optional 能力降级 / 持久化失败 → DEGRADED（感知仍在运行）；
 * 7. 其余 → ACTIVE。
 */
fun resolveSensingRuntimeStatus(inputs: SensingRuntimeInputs): SensingRuntimeStatus = when {
    !inputs.everAuthorized -> SensingRuntimeStatus.NOT_AUTHORIZED
    !inputs.userConsentOn -> SensingRuntimeStatus.USER_PAUSED
    !inputs.serviceStarted -> SensingRuntimeStatus.SYSTEM_PAUSED
    !inputs.collectionFresh && !inputs.hasEverCollected -> SensingRuntimeStatus.STARTING
    !inputs.collectionFresh -> SensingRuntimeStatus.SYSTEM_PAUSED
    !inputs.coreSensorsAvailable ||
        inputs.optionalCapabilityDegraded ||
        inputs.persistenceFailing -> SensingRuntimeStatus.DEGRADED
    else -> SensingRuntimeStatus.ACTIVE
}

// ===== 六态用户文案（单测锚点；UI 层不得另行硬编码） =====

const val RUNTIME_COPY_NOT_AUTHORIZED = "ECHO 还没有开始了解你。"
const val RUNTIME_COPY_STARTING = "ECHO 正在开始了解你"
const val RUNTIME_COPY_ACTIVE = "ECHO 正在了解今天"
const val RUNTIME_COPY_DEGRADED = "ECHO 正常运行中，部分信息暂时不可用"
const val RUNTIME_COPY_SYSTEM_PAUSED = "ECHO 暂时休息了"
const val RUNTIME_COPY_USER_PAUSED = "ECHO 已暂停（由你关闭）"

/** 状态 → 用户文案（纯函数）。 */
fun sensingRuntimeStatusText(status: SensingRuntimeStatus): String = when (status) {
    SensingRuntimeStatus.NOT_AUTHORIZED -> RUNTIME_COPY_NOT_AUTHORIZED
    SensingRuntimeStatus.STARTING -> RUNTIME_COPY_STARTING
    SensingRuntimeStatus.ACTIVE -> RUNTIME_COPY_ACTIVE
    SensingRuntimeStatus.DEGRADED -> RUNTIME_COPY_DEGRADED
    SensingRuntimeStatus.SYSTEM_PAUSED -> RUNTIME_COPY_SYSTEM_PAUSED
    SensingRuntimeStatus.USER_PAUSED -> RUNTIME_COPY_USER_PAUSED
}
