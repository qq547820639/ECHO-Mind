package com.yunjue.echo.mind.actions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v3 §18 — EchoActionRuntime：Scene 内行动的运行时。
 *
 * 职责：Available actions / Suggested action / Running action / Completed action。
 * 建议判定委托 [InterventionPolicy]（L2 opt-in + 置信门槛，L0/L1 不打扰）。
 * 行动在 ECHO Scene 内执行（不跳回 Legacy skill screen）。
 */
enum class EchoActionKind { BREATHING, PAUSE }

/** 行动可用性与建议（UI 渲染输入；由运行时统一计算，UI 不自行拼）。 */
data class EchoActionAvailability(
    val breathing: Boolean = true,
    val pause: Boolean = true,
    val suggested: Boolean = false,
)

class EchoActionRuntime {
    private val _running = MutableStateFlow<EchoActionKind?>(null)

    /** 正在执行的行动（null = Ambient Scene）。 */
    val running: StateFlow<EchoActionKind?> = _running

    fun start(kind: EchoActionKind) {
        _running.value = kind
    }

    /** 结束行动 → 回 Ambient Scene（§19：Scene 自然恢复）。 */
    fun stop() {
        _running.value = null
    }

    /**
     * 可用性 + 建议（纯函数：低置信/未知 → 无建议；opt-in + 高置信 → 建议）。
     */
    fun availability(
        confidence: Float,
        ambientKnown: Boolean,
        suggestionsEnabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ): EchoActionAvailability {
        val level = InterventionPolicy.resolve(
            InterventionInputs(
                confidence = confidence,
                ambientKnown = ambientKnown,
                suggestionsEnabled = suggestionsEnabled,
                nowMs = now,
            )
        )
        return EchoActionAvailability(
            breathing = true,
            pause = true,
            suggested = level >= InterventionLevel.L2_SUGGEST_WHEN_OPENED,
        )
    }
}
