package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.visual.surface.organismDescriptionFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V3 §79 — TalkBack 聚合语义：真实 state、禁止心理话术。 */
class OrganismA11yTest {

    @Test
    fun descriptionReflectsRealState() {
        val known = EchoPresenceState(
            maturity = EchoMaturity.KNOWN,
            sensingStatus = SensingRuntimeStatus.ACTIVE,
            rhythmState = RhythmState(activityLevel = 0.6f, coverage = 0.9f),
        )
        assertTrue(organismDescriptionFor(known).contains("今天的节奏"))
        val lowData = EchoPresenceState(
            maturity = EchoMaturity.KNOWN,
            sensingStatus = SensingRuntimeStatus.ACTIVE,
            rhythmState = RhythmState(activityLevel = 0.6f, coverage = 0.2f),
        )
        assertTrue(organismDescriptionFor(lowData).contains("数据有限"))
        val paused = EchoPresenceState(sensingStatus = SensingRuntimeStatus.USER_PAUSED)
        assertTrue(organismDescriptionFor(paused).contains("已暂停"))
        assertTrue(organismDescriptionFor(null).isNotBlank())
    }

    @Test
    fun noPsychologicalLanguage() {
        val banned = listOf("焦虑", "抑郁", "情绪", "压力", "孤独", "心理", "开心", "难过")
        val states = listOf(
            null,
            EchoPresenceState(maturity = EchoMaturity.SEED),
            EchoPresenceState(maturity = EchoMaturity.MATURE),
            EchoPresenceState(sensingStatus = SensingRuntimeStatus.USER_PAUSED),
            EchoPresenceState(sensingStatus = SensingRuntimeStatus.DEGRADED),
        )
        states.forEach { st ->
            val d = organismDescriptionFor(st)
            banned.forEach { b -> assertFalse("禁止心理话术: $b in $d", d.contains(b)) }
        }
    }
}
