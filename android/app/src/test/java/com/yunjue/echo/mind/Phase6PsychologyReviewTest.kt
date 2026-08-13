package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.PORTRAIT_BLOCKED_VOCABULARY
import com.yunjue.echo.mind.model.PortraitStatus
import com.yunjue.echo.mind.model.containsBlockedVocabulary
import com.yunjue.echo.mind.model.dimensionValueText
import com.yunjue.echo.mind.model.todayPortraitStateText
import com.yunjue.echo.mind.ui.EMERGENCY_HINT_COPY
import com.yunjue.echo.mind.ui.ONBOARDING_WELCOME_CORE_COPY
import com.yunjue.echo.mind.ui.l0OnboardingBlocked
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.3 Psychology Review 词表门禁（纯 JVM，规格 §3.1 / §8.1）。
 *
 * - BLOCK 词（焦虑/抑郁/孤独/压力过大/情绪低落/社交退缩/心理异常/心理风险/精神疾病/自杀/自伤）命中；
 * - ALLOW 词（移动较少/移动较多/开始较晚/开始较早/屏幕偏晚/与近期接近/和近期有些不同）不命中；
 * - 用户可见文案（todayPortraitStateText / dimensionValueText / Onboarding 核心句）不含 BLOCK 词；
 * - Onboarding 文案红线：不含 legacy「心理健康记录 / 筛查提示 / 心理记录与量表信息」；
 * - l0OnboardingBlocked 纯函数保留（L0 解耦后供 Support 页复用）。
 */
class Phase6PsychologyReviewTest {

    @Test
    fun blockedVocabularyHits() {
        for (word in PORTRAIT_BLOCKED_VOCABULARY) {
            assertTrue("BLOCK 词应命中：$word", containsBlockedVocabulary("今天你${word}了一些"))
        }
    }

    @Test
    fun allowWordsDoNotHit() {
        // ALLOW 词（行为观察性 / 相对性取值），不得被误判为心理推断
        val allow = listOf(
            "移动较少", "移动较多", "开始较晚", "开始较早", "屏幕偏晚",
            "与近期接近", "和近期有些不同", "较少", "较多", "接近", "偏晚", "偏早", "不规律"
        )
        for (word in allow) {
            assertFalse("ALLOW 词不应命中：$word", containsBlockedVocabulary(word))
        }
    }

    @Test
    fun userVisibleStateCopyHasNoBlockedVocabulary() {
        for (status in PortraitStatus.entries) {
            val text = todayPortraitStateText(status)
            assertFalse(
                "状态文案不得含 BLOCK 词 [${status.name}]：$text",
                containsBlockedVocabulary(text)
            )
        }
    }

    @Test
    fun onboardingCoreCopyKeepsContractWording() {
        // 规格 §1.3.1 核心句：含「不做心理诊断」契约表述
        assertTrue(ONBOARDING_WELCOME_CORE_COPY.contains("它不会判断你的情绪，也不会做心理诊断。"))
        // 不得含 BLOCK 词
        assertFalse(containsBlockedVocabulary(ONBOARDING_WELCOME_CORE_COPY))
        // 紧急入口文案不含 BLOCK 词
        assertFalse(containsBlockedVocabulary(EMERGENCY_HINT_COPY))
        // Onboarding 文案红线（规格 §1.5）：删除 legacy 定位
        for (legacy in listOf("心理健康记录", "筛查提示", "心理记录与量表信息", "监测你的心理状态", "筛查情绪问题")) {
            assertFalse("Onboarding 核心句不得含 legacy 定位：$legacy", ONBOARDING_WELCOME_CORE_COPY.contains(legacy))
        }
    }

    @Test
    fun dimensionValueTextAvoidsQuietActiveStable() {
        // 规格 §3.2 / §8.1：headline 无「安静/活跃/稳定」（第一版替换为「移动较少/移动较多/接近」）
        val values = listOf(
            "EARLIER", "LATER", "SIMILAR", "IRREGULAR", "LESS", "MORE",
            "MORE_CONCENTRATED", "MORE_FRAGMENTED", "VERY_SIMILAR",
            "SLIGHTLY_DIFFERENT", "CLEARLY_DIFFERENT"
        )
        for (value in values) {
            val text = dimensionValueText("X", value)
            assertFalse(
                "维度取值文案不得含「安静/活跃/稳定」：$text",
                text.contains("安静") || text.contains("活跃") || text.contains("稳定")
            )
        }
    }

    @Test
    fun l0BlockedPureFunctionRetainedForSupportPage() {
        // L0 从普通 Onboarding 解耦（enum 移除 L0 步骤），但纯函数保留（Support 页复用）
        assertTrue(l0OnboardingBlocked(currentDanger = true, psychosisOrMania = false, substanceImpairment = false))
        assertTrue(l0OnboardingBlocked(currentDanger = false, psychosisOrMania = true, substanceImpairment = false))
        assertTrue(l0OnboardingBlocked(currentDanger = false, psychosisOrMania = false, substanceImpairment = true))
        assertFalse(l0OnboardingBlocked(currentDanger = false, psychosisOrMania = false, substanceImpairment = false))
    }
}
