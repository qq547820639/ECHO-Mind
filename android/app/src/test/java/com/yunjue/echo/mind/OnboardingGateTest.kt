package com.yunjue.echo.mind

import com.yunjue.echo.mind.ui.l0OnboardingBlocked
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12.6 L0 准入门禁不变量回归（纯函数，无需 Robolectric）。
 *
 * 验证 OnboardingScreen 的 currentDanger / psychosisOrMania / substanceImpairment
 * 阻断逻辑在 T12 改造后保持不变：任一为真即阻断进入应用。
 * 该纯函数与 OnboardingScreen「进入应用」按钮 enabled 条件共享同一判定。
 */
class OnboardingGateTest {

    @Test
    fun noRiskSignalsDoesNotBlock() {
        assertFalse(l0OnboardingBlocked(currentDanger = false, psychosisOrMania = false, substanceImpairment = false))
    }

    @Test
    fun currentDangerBlocksOnboarding() {
        assertTrue(l0OnboardingBlocked(currentDanger = true, psychosisOrMania = false, substanceImpairment = false))
    }

    @Test
    fun psychosisOrManiaBlocksOnboarding() {
        assertTrue(l0OnboardingBlocked(currentDanger = false, psychosisOrMania = true, substanceImpairment = false))
    }

    @Test
    fun substanceImpairmentBlocksOnboarding() {
        assertTrue(l0OnboardingBlocked(currentDanger = false, psychosisOrMania = false, substanceImpairment = true))
    }

    @Test
    fun allRiskSignalsBlockOnboarding() {
        assertTrue(l0OnboardingBlocked(currentDanger = true, psychosisOrMania = true, substanceImpairment = true))
    }
}
