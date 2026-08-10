package com.yunjue.echo.mind.model

import java.time.Instant
import java.util.UUID

// legacy: v0.8 removal target — CheckinInput / saveCheckin 已删除（主动签到范式停用，410 存根）。
// 保留文件顶部占位注释以说明删除决策；不保留任何旧模型定义。

data class SafetyDecision(
    val severity: Severity,
    val matchedRuleIds: List<String>,
    val freezeGeneration: Boolean,
    val scriptKey: String? = null
)

enum class Severity { NONE, YELLOW, RED, EXIT }

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
 * Skill 卡片展示模型（T11）：与后端 [SkillOut] 结构对齐（PRD 契约点 4/5）。
 *
 * - trigger_conditions / steps 在后端是 list[dict]，端侧渲染时降维为可读字符串列表，
 *   避免在 UI 层直接持有半结构化字典。
 * - guardrails 后端即 list[str]，原样保留。
 * - actionType / estimatedDuration / completionSchema / safetyConstraints / revision
 *   为 v0.6 执行契约字段（T02 修复缺陷 7：端侧先前丢弃这些字段）。
 * - 仅用于 UI 渲染，不参与上行同步。
 */
data class SkillDisplay(
    val id: String,
    val name: String,
    val version: Int,
    val triggerConditions: List<String>,
    val guardrails: List<String>,
    val steps: List<String>,
    val status: String,
    val actionType: String = "guided_steps",
    val estimatedDuration: Int? = null,
    val completionSchema: String? = null,
    val safetyConstraints: List<String> = emptyList(),
    val revision: Int = 1
) {
    /** action_type 白名单（PRD 契约点 4）：白名单外一律 fail closed（不渲染开始、不产生 completion）。 */
    companion object {
        val ACTION_TYPE_WHITELIST: Set<String> = setOf(
            "guided_steps", "reflection_prompt", "breathing", "journaling", "checklist"
        )
    }
}

/**
 * Skill 执行会话状态（T02）：running / paused。
 * 进程死亡后恢复统一回到 PAUSED（不自动计时，避免虚增时长）。
 */
enum class SkillSessionStatus { RUNNING, PAUSED }

/**
 * Skill 执行会话持久化领域模型（T02），与 Room ActiveSkillSessionEntity 对齐。
 *
 * duration 语义：
 * - running: activeDurationMs = accumulatedActiveMs + (now - segmentStartedAtMs)
 * - paused : activeDurationMs = accumulatedActiveMs（暂停不计时）
 */
data class ActiveSkillSession(
    val sessionId: String,
    val skillId: String,
    val skillVersion: Int,
    val skillRevision: Int,
    val actionType: String,
    val status: SkillSessionStatus,
    val currentStep: Int,
    val startedAt: Long,
    val accumulatedActiveMs: Long,
    val segmentStartedAtMs: Long?,
    val pausedAt: Long?,
    val updatedAt: Long
) {
    /** 当前活动时长（ms）。暂停时不计入暂停段。 */
    fun activeDurationMs(now: Long): Long = when (status) {
        SkillSessionStatus.RUNNING -> accumulatedActiveMs + ((now - (segmentStartedAtMs ?: startedAt)).coerceAtLeast(0L))
        SkillSessionStatus.PAUSED -> accumulatedActiveMs
    }
}

/**
 * 每日叙事展示模型（T12.4）：对齐 GET /v1/narratives 响应。
 *
 * - events 为该日被动特征事件摘要列表（source + summary + sources_present）
 * - 不承载任何情绪语义（mood_hint 解析已删除，PRD 契约点 2）
 * - 仅用于 UI 渲染（趋势视图），不参与上行同步。
 */
data class NarrativeDisplay(
    val date: String,
    val events: List<NarrativeEventDisplay>,
    val gaps: List<String> = emptyList()
)

data class NarrativeEventDisplay(
    val source: String,
    val summary: String,
    val sourcesPresent: List<String> = emptyList()
)

/**
 * 用户画像展示模型（T12.4）：对齐 GET /v1/profile/{user_id} 响应的 traits 字段。
 *
 * - observationDays：累计观察天数
 * - narrativeDaysLast7：近 7 天有叙事的天数
 * - sourcesPresentUnion：近 7 天窗口内实际信号源并集（v0.6 final 契约）
 * - loadFailed：拉取失败语义（网络/解析/非 2xx），趋势页据此区分 error 与 no_data
 * - 仅用于 UI 渲染（趋势视图），不参与上行同步。
 */
data class ProfileDisplay(
    val observationDays: Int,
    val narrativeDaysLast7: Int,
    val version: Int,
    val sourcesPresentUnion: List<String> = emptyList(),
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

// ===== Portrait（Milestone F/G/H：Today Portrait + Portrait Timeline + Room 缓存） =====

/**
 * 单条画像事实（对齐 GET /v1/portraits/today 响应的 facts 数组元素）。
 *
 * - label：事实标签（如「起床时间」）
 * - todayText：今天的观察
 * - baselineText：平常（基线）的观察
 * - deltaText：今天与平常的差异描述
 * 仅用于 UI 渲染（「为什么这么说？」展开区），不参与上行同步。
 */
data class PortraitFactDto(
    val label: String,
    val todayText: String,
    val baselineText: String,
    val deltaText: String
)

/**
 * 每日画像展示模型（Milestone F）：对齐 GET /v1/portraits/today / /v1/portraits 响应元素。
 *
 * - status：服务端画像状态（WARMING_UP / EARLY_BASELINE / READY / PARTIAL_DATA / LOW_CONFIDENCE）
 * - confidence：HIGH / MEDIUM / LOW
 * - baselineDays / baselineVersion：基线积累天数与版本（冷启动阶段文案依据）
 * - headline：当日要点短句列表（渲染为 chips）
 * - summary：服务端自然语言段落
 * - dimensions：维度键 → 取值（RHYTHM/MOVEMENT/SCREEN_PATTERN/DAY_STRUCTURE/STABILITY）
 * - coverage：服务端数据覆盖信息（可选，值类型不定）
 * - facts：事实对照列表（「为什么这么说？」区）
 * - timezoneUsed：服务端计算该画像所用的时区（端侧缓存键与日期处理依据）
 * 仅用于 UI 渲染 + Room 缓存，不参与上行同步。
 */
data class DailyPortraitDto(
    val date: String,
    val status: String,
    val confidence: String,
    val baselineDays: Int,
    val baselineVersion: String? = null,
    val headline: List<String> = emptyList(),
    val summary: String = "",
    val dimensions: Map<String, String> = emptyMap(),
    val coverage: Map<String, Any>? = null,
    val facts: List<PortraitFactDto> = emptyList(),
    val timezoneUsed: String? = null
)

/**
 * 基线状态展示模型（Milestone F）：对齐 GET /v1/baseline/status 响应。
 * 当前版本不直接驱动 UI，保留供后续版本做基线可视化（与 ApiClient.getBaselineStatus 配套）。
 */
data class BaselineStatusDto(
    val status: String,
    val baselineDays: Int,
    val baselineVersion: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val bucketUsage: Map<String, Any>? = null,
    val todayCoverage: Map<String, Any>? = null
)

/**
 * 画像批量拉取结果（Milestone G）：对齐 GET /v1/portraits?days=7|28 响应。
 * portraits 数组元素结构与 [DailyPortraitDto] 一致（含 date）。
 */
data class PortraitListDto(
    val portraits: List<DailyPortraitDto> = emptyList()
)
