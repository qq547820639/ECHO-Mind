package com.yunjue.echo.mind.localportrait

import com.yunjue.echo.mind.data.MessageRepository
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.containsBlockedVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周小结（分析消息）测试：与后端 tests/test_messages.py 逐场景镜像。
 *
 * - <3 天 abstain；确定性幂等 id；词表安全 fail-closed；
 * - 消息解析（GET /v1/me/messages 响应）。
 */
class LocalPortraitDigestTest {

    private fun portrait(
        date: String,
        rhythm: String = "SIMILAR",
        movement: String = "SIMILAR",
        screen: String = "SIMILAR"
    ): DailyPortraitDto = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "MEDIUM",
        baselineDays = 7,
        dimensions = mapOf(
            "RHYTHM" to PortraitDimensionDto(rhythm, "active_start_minute", 0.0),
            "MOVEMENT" to PortraitDimensionDto(movement, "movement_index", 0.0),
            "SCREEN_AMOUNT" to PortraitDimensionDto(screen, "screen_on_minutes", 0.0),
            "STABILITY" to PortraitDimensionDto("SLIGHTLY_DIFFERENT", null, null)
        )
    )

    @Test
    fun digestAbstainsUnderThreeDays() {
        assertNull(LocalPortraitDigest.build(emptyList()))
        assertNull(LocalPortraitDigest.build(listOf(portrait("2026-08-09"), portrait("2026-08-10"))))
    }

    @Test
    fun digestDeterministicAndContractSafe() {
        val rows = listOf(
            portrait("2026-08-08", rhythm = "LATER", movement = "LESS", screen = "MORE"),
            portrait("2026-08-09"),
            portrait("2026-08-10")
        )
        val d1 = LocalPortraitDigest.build(rows)!!
        val d2 = LocalPortraitDigest.build(rows)!!
        assertEquals("同输入同 id（确定性幂等）", d1.id, d2.id)
        assertEquals("本周节律小结", d1.title)
        assertTrue(d1.body.contains("最接近"))
        assertTrue(d1.body.contains("变化较明显"))
        assertFalse(containsBlockedVocabulary(d1.title))
        assertFalse(containsBlockedVocabulary(d1.body))
        assertEquals(16, d1.id.length)
    }

    @Test
    fun digestSkipsDimensionsWithoutValues() {
        // 只有 RHYTHM 有数据：最接近与变化较明显都落在 RHYTHM（其余维度 total=0 不参与）
        val rows = (0 until 3).map { i ->
            DailyPortraitDto(
                date = "2026-08-${8 + i}",
                status = "READY",
                confidence = "MEDIUM",
                baselineDays = 7,
                dimensions = mapOf("RHYTHM" to PortraitDimensionDto("SIMILAR", "active_start_minute", 0.0))
            )
        }
        val digest = LocalPortraitDigest.build(rows)!!
        assertEquals("最接近：作息；变化较明显：作息。", digest.body)
    }

    @Test
    fun messageParseFailClosed() {
        assertEquals(
            null,
            MessageRepository.parseMessages("""{"message": null}""")
        )
        assertEquals(
            null,
            MessageRepository.parseMessages("not-json")
        )
        val parsed = MessageRepository.parseMessages(
            """{"message": {"id": "abc123", "title": "本周节律小结", "body": "最接近：作息；变化较明显：移动。"}}"""
        )
        assertEquals("abc123", parsed?.id)
        assertEquals("本周节律小结", parsed?.title)
    }

    @Test
    fun subscriptionStatusTextSemantics() {
        val now = 1_000_000_000_000L
        // 机构旧用户：null = 永不过期
        assertEquals("已订阅", com.yunjue.echo.mind.model.subscriptionStatusText(null, now))
        // 未来到期：剩余天数
        val future = now + 5 * 86_400_000L
        assertTrue(
            "未来到期应显示有效期与剩余天数",
            com.yunjue.echo.mind.model.subscriptionStatusText(future, now).contains("剩余 5 天")
        )
        // 已过期：续订提示
        assertEquals(
            "订阅已到期，请续订",
            com.yunjue.echo.mind.model.subscriptionStatusText(now - 1L, now)
        )
    }
}

/** 每晚提醒时刻计算测试。 */
class UxHelpersTest {

    @Test
    fun delayToNextEveningMs() {
        val now = java.time.LocalDateTime.of(2026, 8, 14, 18, 0)
        // 18:00 → 当晚 21:00 = 3 小时
        assertEquals(3 * 60 * 60 * 1000L, com.yunjue.echo.mind.data.EveningReminderWorker.delayToNextEveningMs(now))
        // 21:00 整 → 次日 21:00 = 24 小时
        val atEvening = java.time.LocalDateTime.of(2026, 8, 14, 21, 0)
        assertEquals(24 * 60 * 60 * 1000L, com.yunjue.echo.mind.data.EveningReminderWorker.delayToNextEveningMs(atEvening))
        // 22:00 → 次日 21:00 = 23 小时
        val after = java.time.LocalDateTime.of(2026, 8, 14, 22, 0)
        assertEquals(23 * 60 * 60 * 1000L, com.yunjue.echo.mind.data.EveningReminderWorker.delayToNextEveningMs(after))
    }
}
