package com.yunjue.echo.mind.model

/**
 * ERA 13.5 §49 — 感知能力模型枚举（自 sensing 迁入 model：Ground Truth 词表）。
 *
 * Phase 6.1 Permission Degraded：感知能力模型（PM 规格 §2）。
 * 纯 Kotlin（无 Android 依赖），:core:model 模块可承载；
 * 逐能力状态判定函数 capabilityState 留在 sensing（Android 上下文判定）。
 */
enum class SensingCapability { SENSOR, SCREEN, USAGE, NOTIFICATION, MIC }

/** Phase 6.1 Permission Degraded：能力状态（PM 规格 §2.1）。 */
enum class CapabilityState { AVAILABLE, DENIED, UNAVAILABLE, DISABLED }
