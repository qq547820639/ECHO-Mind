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
 *
 * 原始传感数据仅在端侧处理，上传的只有 summary + vector。
 */
data class DerivedFeatureInput(
    val schemaVersion: String = "feat-v1",
    val source: String,
    val windowStart: Instant,
    val windowEnd: Instant,
    val summary: String,
    val vector: List<Float>
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
 * - mood_hint 取值：平稳 / 平稳偏积极 / 偏低 / 未知
 * - events 为该日被动特征事件摘要列表（source + summary + mood_hint）
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
 * - recentMoodHint：最近一日情绪提示
 * - 仅用于 UI 渲染（趋势视图），不参与上行同步。
 */
data class ProfileDisplay(
    val observationDays: Int,
    val narrativeDaysLast7: Int,
    val recentMoodHint: String,
    val version: Int,
    val updatedAt: String? = null
)
