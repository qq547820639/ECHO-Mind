package com.yunjue.echo.mind.features.behaviorderived

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 2：行为派生状态单元测试。
 *
 * 边界：
 * - 数据不足 → 全部 UNKNOWN + "数据不足" 依据。
 * - 屏幕/通知/语音因素变化 → 维度变化（确定性）。
 * - 依据含"非心理诊断"提示。
 */
class DerivedBehaviorStateTest {

    @Test
    fun unknownWhenAllNull() {
        val s = deriveBehaviorState(
            screenRhythmMinutes = null,
            notificationInteracts = null,
            appSwitchCount = null,
            activityMinutes = null,
            voiceSessionMinutes = null,
        )
        assertEquals(DerivedEmotion.UNKNOWN, s.emotion)
        assertEquals(DerivedEnergy.UNKNOWN, s.energy)
        assertEquals(DerivedFocus.UNKNOWN, s.focus)
        assertTrue(s.evidenceSummary.contains("非心理诊断"))
    }

    @Test
    fun highActivityYieldsHighEnergy() {
        val s = deriveBehaviorState(
            screenRhythmMinutes = 60,
            notificationInteracts = 4,
            appSwitchCount = 5,
            activityMinutes = 90,
            voiceSessionMinutes = 5,
        )
        assertEquals(DerivedEnergy.HIGH, s.energy)
    }

    @Test
    fun lowActivityYieldsLowEnergy() {
        val s = deriveBehaviorState(
            screenRhythmMinutes = 60,
            notificationInteracts = 1,
            appSwitchCount = 2,
            activityMinutes = 5,
            voiceSessionMinutes = 1,
        )
        assertEquals(DerivedEnergy.LOW, s.energy)
    }

    @Test
    fun manyAppSwitchesYieldsDivided() {
        val s = deriveBehaviorState(
            screenRhythmMinutes = 60,
            notificationInteracts = 20,
            appSwitchCount = 25,
            activityMinutes = 30,
            voiceSessionMinutes = 5,
        )
        assertEquals(DerivedFocus.DIVIDED, s.focus)
    }

    @Test
    fun lateScreenYieldsFatigued() {
        val s = deriveBehaviorState(
            screenRhythmMinutes = 180,
            notificationInteracts = 5,
            appSwitchCount = 5,
            activityMinutes = 30,
            voiceSessionMinutes = 1,
        )
        assertEquals(DerivedEmotion.FATIGUED, s.emotion)
    }

    @Test
    fun nullPortraitYieldsUnknown() {
        val s = deriveFromPortrait(null)
        assertEquals(DerivedEmotion.UNKNOWN, s.emotion)
        assertEquals(DerivedEnergy.UNKNOWN, s.energy)
        assertEquals(DerivedFocus.UNKNOWN, s.focus)
        assertNotNull(s.evidenceSummary)
    }
}
