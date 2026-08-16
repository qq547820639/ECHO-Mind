package com.yunjue.echo.mind.visual.surface

import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.SensingRuntimeStatus

/**
 * V3 §79 — organism 聚合语义描述（TalkBack 唯一节点；粒子/filament 不进树）。
 *
 * 使用真实 state（maturity / 数据清晰度 / 感知状态），禁止心理话术。
 */
fun organismDescriptionFor(presence: EchoPresenceState?): String {
    if (presence == null) return "ECHO 生命体"
    return when (presence.sensingStatus) {
        SensingRuntimeStatus.USER_PAUSED -> "ECHO 生命体。感知已暂停，它保持安静。"
        SensingRuntimeStatus.NOT_AUTHORIZED -> "ECHO 生命体。尚未开始感知。"
        SensingRuntimeStatus.SYSTEM_PAUSED -> "ECHO 生命体。系统暂停了感知。"
        SensingRuntimeStatus.DEGRADED -> "ECHO 生命体。今天的数据比平时少。"
        else -> when (presence.maturity) {
            EchoMaturity.SEED -> "ECHO 生命体。它刚刚开始认识你。"
            EchoMaturity.DISCOVERING, EchoMaturity.EMERGING ->
                "ECHO 生命体。它正在认识你的日常节奏。"
            EchoMaturity.KNOWN, EchoMaturity.MATURE -> {
                if (presence.rhythmState.coverage in 0.01f..0.4f) {
                    "ECHO 生命体。今天的数据有限，它比较安静。"
                } else {
                    "ECHO 生命体，反映你今天的节奏。"
                }
            }
        }
    }
}
