package com.yunjue.echo.mind.model

import java.time.Instant
import java.util.UUID

data class CheckinInput(
    val mood: Int,
    val stress: Int,
    val energy: Int,
    val sleepRecovery: Int,
    val eventFlag: Boolean,
    val helpRequested: Boolean,
    val note: String?,
    val clientTime: Instant = Instant.now(),
    val eventId: String = "evt_${UUID.randomUUID()}"
)

data class JournalInput(
    val body: String,
    val tags: List<String> = emptyList(),
    val logicalId: String = "journal_${UUID.randomUUID()}",
    val revision: Int = 1,
    val clientTime: Instant = Instant.now(),
    val eventId: String = "evt_${UUID.randomUUID()}"
)

data class SafetyDecision(
    val severity: Severity,
    val matchedRuleIds: List<String>,
    val freezeGeneration: Boolean,
    val scriptKey: String? = null
)

enum class Severity { NONE, YELLOW, RED, EXIT }

data class QuestionnaireScore(val score: Int, val message: String, val urgentItem: Boolean = false)

data class QuestionnaireDefinition(
    val code: String,
    val title: String,
    val items: List<String>,
    val version: String = "1.0"
)

data class PracticeDefinition(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val steps: List<String>,
    val version: String = "1.0"
)

/**
 * 端侧派生特征输入（对应后端 DerivedFeatureIn 契约）。
 *
 * - schemaVersion 固定 "feat-v1"
 * - source 标识主要信号源（accel/gyro/screen/notification/app_activity/health/mic_opt）
 * - windowStart/windowEnd 为 5 分钟聚合窗口
 * - summary 中文自然语言摘要（≤4000 字）
 * - vector 特征向量（≤256 维 float）
 * - sourcesPresent 该窗口实际覆盖的 modality 枚举（accel/gyro/screen/notification/app_activity/mic_opt/health），
 *   与后端 gap_finder.EXPECTED_SOURCES 契约对齐（02b 共享知识 4）
 *
 * 原始传感数据仅在端侧处理，上传的只有 summary + vector。
 */
data class DerivedFeatureInput(
    val schemaVersion: String = "feat-v1",
    val source: String,
    val windowStart: Instant,
    val windowEnd: Instant,
    val summary: String,
    val vector: List<Float>,
    val sourcesPresent: List<String> = emptyList()
)

/**
 * Skill 卡片展示模型（T11）：与后端 [SkillOut] 结构对齐但简化。
 *
 * - trigger_conditions / steps 在后端是 list[dict]，端侧渲染时降维为可读字符串列表，
 *   避免在 UI 层直接持有半结构化字典。
 * - guardrails 后端即 list[str]，原样保留。
 * - 仅用于 UI 渲染，不参与上行同步。
 */
data class SkillDisplay(
    val id: String,
    val name: String,
    val version: Int,
    val triggerConditions: List<String>,
    val guardrails: List<String>,
    val steps: List<String>,
    val status: String
)

/**
 * 每日叙事展示模型（T12.4）：对齐 GET /v1/narratives 响应。
 *
 * - mood_hint 字段保留仅用于向后解析（后端 T03 将废弃该字段）；UI 一律不再渲染情绪语义。
 * - events 为该日被动特征事件摘要列表（source + summary）
 * - 仅用于 UI 渲染（趋势视图），不参与上行同步。
 */
data class NarrativeDisplay(
    val date: String,
    val moodHint: String,
    val events: List<NarrativeEventDisplay>,
    val gaps: List<String> = emptyList()
)

data class NarrativeEventDisplay(
    val source: String,
    val summary: String,
    val moodHint: String
)

/**
 * 用户画像展示模型（T12.4）：对齐 GET /v1/profile/{user_id} 响应的 traits 字段。
 *
 * - observationDays：累计观察天数
 * - narrativeDaysLast7：近 7 天有叙事的天数
 * - loadFailed：拉取失败语义（网络/解析/非 2xx），趋势页据此区分 error 与 no_data
 * - 仅用于 UI 渲染（趋势视图），不参与上行同步。
 */
data class ProfileDisplay(
    val observationDays: Int,
    val narrativeDaysLast7: Int,
    val recentMoodHint: String,
    val version: Int,
    val updatedAt: String? = null,
    val loadFailed: Boolean = false
)

/**
 * 趋势数据批量拉取结果（PRD v0.6 契约点 8）。
 *
 * - narratives：按日期升序的有叙事日期列表
 * - loadFailed：网络/服务端/解析失败（区别于真无数据的 NO_DATA）
 * - fromCache：当前离线使用缓存数据（OFFLINE_CACHED 态）
 * - dataCoverage：0..1，有数据天数 / 请求天数
 * - missingDates：请求范围内缺失的日期（missing window 标注）
 * - isPartial：有数据但存在缺失窗口（PARTIAL 态）
 */
data class NarrativeFetchResult(
    val narratives: List<NarrativeDisplay>,
    val loadFailed: Boolean,
    val fromCache: Boolean,
    val dataCoverage: Float,
    val missingDates: List<String>,
    val isPartial: Boolean
)

/**
 * Skill 执行完成上报输入（对应后端 POST /v1/skills/completions 契约）。
 *
 * - status：started / completed / stopped
 * - durationSeconds：执行耗时（秒）
 */
data class SkillCompletionInput(
    val skillId: String,
    val status: String,
    val durationSeconds: Int,
    val clientTime: Instant = Instant.now(),
    val eventId: String = "evt_${UUID.randomUUID()}"
)

/**
 * 统一同步状态（PRD v0.6 契约点 9）。
 *
 * 仅供 UI 展示语义，严禁把内部 HTTP status 暴露给普通用户。
 */
enum class SyncState {
    SYNCED,
    PENDING,
    SYNCING,
    OFFLINE,
    RETRYING,
    BLOCKED_BY_AUTH,
    BLOCKED_BY_CONSENT,
    FAILED_TERMINAL
}
