package com.yunjue.echo.mind.me

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 61（ADR-060 第 2 轮）——「ECHO 不知道什么」纯映射契约：
 * 能力边界行由运行时/权限/基线事实导出；无事实不产行；基线阈值 7 天。
 */
class EchoDoesNotKnowTest {

    @Test
    fun allCapabilitiesPresentYieldsNoLines() {
        val facts = EchoKnowsFacts(
            sensingEnabled = true,
            micEnabled = true,
            providerConfigured = true,
            baselineDays = 30,
        )
        assertTrue("能力齐备时不编造边界行", EchoDoesNotKnow.from(facts).isEmpty())
    }

    @Test
    fun missingCapabilitiesProduceHonestLines() {
        val facts = EchoKnowsFacts(
            sensingEnabled = false,
            micEnabled = false,
            providerConfigured = false,
            baselineDays = 3,
        )
        val lines = EchoDoesNotKnow.from(facts)
        assertTrue(lines.any { it.contains("不观察你的屏幕使用") })
        // 感知未开启时麦克风行不重复出现（感知关闭已涵盖声音不记录）
        assertTrue(lines.none { it.contains("不记录你的声音") })
        assertTrue(lines.any { it.contains("解读基于你手机本地的确定性解释") })
        assertTrue(lines.any { it.contains("基线天数还不足（当前 3 天）") })
    }

    @Test
    fun micLineOnlyWhenSensingEnabled() {
        val lines = EchoDoesNotKnow.from(
            EchoKnowsFacts(sensingEnabled = true, micEnabled = false, providerConfigured = true, baselineDays = 30),
        )
        assertEquals(listOf("没有开启麦克风：ECHO 不记录你的声音。"), lines)
    }

    @Test
    fun baselineThresholdIsSevenDays() {
        assertTrue(
            "6 天 → 提示基线不足",
            EchoDoesNotKnow.from(EchoKnowsFacts(baselineDays = 6)).any { it.contains("基线天数还不足") },
        )
        assertTrue(
            "7 天 → 不再提示",
            EchoDoesNotKnow.from(EchoKnowsFacts(baselineDays = 7)).none { it.contains("基线天数还不足") },
        )
    }

    @Test
    fun retentionLabelsMapAllClasses() {
        // ERA 62：保留策略用户文案（与 retentionDaysFor 同源语义）
        assertEquals("7 天（到期自动清理）", retentionLabelText(com.yunjue.echo.mind.memory.RetentionClass.EPHEMERAL))
        assertEquals("30 天（到期自动清理）", retentionLabelText(com.yunjue.echo.mind.memory.RetentionClass.SHORT_TERM))
        assertEquals("365 天（到期自动清理）", retentionLabelText(com.yunjue.echo.mind.memory.RetentionClass.LONG_TERM))
        assertEquals("你固定的（永不自动清理）", retentionLabelText(com.yunjue.echo.mind.memory.RetentionClass.USER_PINNED))
    }
}
