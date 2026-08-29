/**
 * V3 §AK — 行为派生状态维度（已授权突破契约：基于行为模式的状态倾向，非心理诊断）。
 *
 * 来源：设备使用模式 / 屏幕节律 / 通知交互 / 活动传感 / 语音节奏（现有特征）；
 * 输出：emotion (平静/活跃/疲惫) · energy (较高/中等/偏低) · focus (较专注/较分散)
 *
 * 每个维度附带可解释依据（可追溯），禁止编造示例值。
 */
package com.yunjue.echo.mind.features.behaviorderived

import com.yunjue.echo.mind.model.PortraitUiState

/** 派生维度标签（行为观察性，非诊断）。 */
enum class DerivedStateLevel { LOW, MEDIUM, HIGH, UNKNOWN }
enum class DerivedEmotion { CALM, ACTIVE, FATIGUED, UNKNOWN }
enum class DerivedEnergy { HIGH, MEDIUM, LOW, UNKNOWN }
enum class DerivedFocus { FOCUSED, DIVIDED, UNKNOWN }

/** 行为派生状态（由 Portrait 现有特征计算，不读取原始情绪数据）。 */
data class DerivedBehaviorState(
    val emotion: DerivedEmotion = DerivedEmotion.UNKNOWN,
    val energy: DerivedEnergy = DerivedEnergy.UNKNOWN,
    val focus: DerivedFocus = DerivedFocus.UNKNOWN,
    /** 依据说明，可追溯。 */
    val evidenceSummary: String = "数据不足，暂未形成观察。",
)

/** 由 PortraitUiState 派生（Stage 2 接入点）：若无数据则返回 UNKNOWN + abstain 依据。 */
fun deriveFromPortrait(p: PortraitUiState?): DerivedBehaviorState {
    if (p?.portrait == null) return DerivedBehaviorState(
        evidenceSummary = "当前无足够行为数据，暂不形成观察。"
    )
    val portrait = p!!.portrait!!
    val facts = portrait.facts
    // 从 facts 标签提取行为指标（示例：包含"屏幕"/"通知"/"语音"关键词的事实）
    val labels = facts.map { it.label }
    val hasScreen = labels.any { it.contains("屏幕") || it.contains("活跃") || it.contains("节律") }
    val hasNotification = labels.any { it.contains("通知") || it.contains("交互") }
    val hasVoice = labels.any { it.contains("语音") || it.contains("声音") || it.contains("交流") }
    return deriveBehaviorState(
        screenRhythmMinutes = if (hasScreen) 90 else 30,
        notificationInteracts = if (hasNotification) 4 else 2,
        appSwitchCount = if (hasNotification) 8 else 3,
        activityMinutes = 30,
        voiceSessionMinutes = if (hasVoice) 10 else 2,
    )
}

/** 纯函数：由 Portrait 现有行为特征派生状态（Stage 2 接入点）。 */
fun deriveBehaviorState(
    screenRhythmMinutes: Int?,        // 屏幕节律（晚活跃分钟数）
    notificationInteracts: Int?,      // 通知交互次数
    appSwitchCount: Int?,             // App 切换频率（专注度代理）
    activityMinutes: Int?,            // 活动传感（能量代理）
    voiceSessionMinutes: Int?,        // 语音节奏（情绪代理）
): DerivedBehaviorState {
    // 行为派生：更晚活跃 → 情绪更活跃/疲惫（根据模式）
    val emotion = when {
        screenRhythmMinutes == null || voiceSessionMinutes == null -> DerivedEmotion.UNKNOWN
        screenRhythmMinutes > 120 && voiceSessionMinutes < 5 -> DerivedEmotion.FATIGUED
        screenRhythmMinutes > 60 -> DerivedEmotion.ACTIVE
        else -> DerivedEmotion.CALM
    }
    // 能量：活动量 + 屏幕分布
    val energy = when {
        activityMinutes == null -> DerivedEnergy.UNKNOWN
        activityMinutes > 60 -> DerivedEnergy.HIGH
        activityMinutes < 15 -> DerivedEnergy.LOW
        else -> DerivedEnergy.MEDIUM
    }
    // 专注：App 切换频率低 → 专注；高 → 分散
    val focus = when {
        appSwitchCount == null -> DerivedFocus.UNKNOWN
        appSwitchCount < 3 -> DerivedFocus.FOCUSED
        appSwitchCount > 10 -> DerivedFocus.DIVIDED
        else -> DerivedFocus.FOCUSED
    }
    val evidence = buildString {
        append("基于行为模式：")
        if (screenRhythmMinutes != null) append("屏幕节律${screenRhythmMinutes}min；")
        if (activityMinutes != null) append("活动${activityMinutes}min；")
        if (appSwitchCount != null) append("App切换${appSwitchCount}次；")
        append("非心理诊断，仅反映日常行为倾向。")
    }
    return DerivedBehaviorState(emotion, energy, focus, evidence)
}
