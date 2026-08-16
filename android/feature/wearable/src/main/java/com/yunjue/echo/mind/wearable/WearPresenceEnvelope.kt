package com.yunjue.echo.mind.wearable

/**
 * Phone → Band Presence 投影 envelope。
 *
 * **禁止直接序列化整个 [com.yunjue.echo.mind.model.EchoPresenceState]**：
 * 本 envelope 是经 [WearPresenceProjector] + [WearablePrivacyProjector] 生成的
 * 明确最小 PUBLIC_SAFE 投影，只含渲染与当前交互所必需的信息。
 *
 * 禁止进入本 envelope 的字段（ECHO_WRIST_PRIVACY.md §Never Send）：
 * API key / provider credential / provider base URL / raw Memory / Correction 原文 /
 * private Context / private Narrative / raw audio / notification body /
 * precise location / Identity secret seed / 有隐私含义的数据库 ID。
 */
data class WearIdentityProjection(
    /** 核心拓扑 0..1（1 = 高度对称） ← EchoIdentityGenome.coreTopology。 */
    val topology: Float,
    /** 对称倾向 0..1 ← symmetryTendency。 */
    val symmetry: Float,
    /** 轨道几何 0..1（0 = 环状，1 = 弥散） ← orbitGeometry。 */
    val orbit: Float,
    /** 运动人格 0..1（0 = 安静，1 = 活跃） ← motionPersonality。 */
    val motion: Float,
    /** 纹理族 0..3 ← textureFamily。 */
    val texture: Int,
    /** 颜色族 0..4 ← colorFamily。 */
    val colorFamily: Int,
    /** 主色相 0..1 ← accentHue。 */
    val accent: Float,
)

/** Moment 最小投影（来自 EchoDailyComposition，不含 private Narrative/情感）。 */
data class WearMomentProjection(
    val flow: Float,
    val coherence: Float,
    val density: Float,
    val turbulence: Float,
    val brightness: Float,
)

/** 表面参数：surface-specific difference（几何简化/动画预算/隐私/屏形/电量预算）。 */
data class WearSurfaceParams(
    /** QUIET / DEFAULT / LIVELY（PresenceMotionLevel 名）。 */
    val motionLevel: String,
    /** 低电量模式：降低更新频率。 */
    val lowPower: Boolean,
    /** 系统减弱动态：手环端最小化动画。 */
    val reducedMotion: Boolean,
    /** 前台加速度计摘要开关（手机 Me → ECHO on Wrist 的 consent；默认关）。 */
    val motionSummaryEnabled: Boolean = false,
)

data class WearPresenceEnvelope(
    override val schemaVersion: Int = WEAR_SCHEMA_CURRENT,
    override val messageId: String,
    override val generatedAt: Long,
    override val source: WearMessageSource = WearMessageSource.PHONE,
    /** Presence revision：手机单调增长；手环收到 revision <= 缓存值必须 ignore。 */
    override val revision: Long,
    /** 过期时间（epoch ms）。断连且过期：保留 Identity，Moment 降级 QUIET/LOW_CERTAINTY。 */
    val expiresAt: Long,
    /** SEED / DISCOVERING / EMERGING / KNOWN / MATURE。 */
    val maturity: String,
    val identity: WearIdentityProjection,
    val moment: WearMomentProjection,
    val surface: WearSurfaceParams,
    /** 可选一行公开表达（克制；见 WearablePrivacyProjector 允许清单）。 */
    val publicHeadline: String? = null,
    /** 当前可用的腕上动作（BREATHING / PAUSE）。 */
    val availableActions: List<String> = emptyList(),
) : WearEnvelope

/**
 * 所有 envelope 的公共骨架：schemaVersion / messageId / generatedAt / source，
 * presence 额外 revision + expiresAt。
 */
interface WearEnvelope {
    val schemaVersion: Int
    val messageId: String
    val generatedAt: Long
    val source: WearMessageSource
    val revision: Long get() = 0L
}
