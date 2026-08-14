package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.containsBlockedVocabulary
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_BODY
import com.yunjue.echo.mind.presence.PRESENCE_COPY_SEED_TITLE
import com.yunjue.echo.mind.presence.echoMaturity
import com.yunjue.echo.mind.presence.presenceSeedRuntimeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ERA 1/2：ECHO Presence 领域模型纯函数回归。
 * 成熟度映射（Master Prompt PART 66）与 Day-0 SEED 文案（PART 65）。
 */
class EchoPresenceStateTest {

    @Test
    fun maturityFollowsBaselineDays() {
        assertEquals(EchoMaturity.SEED, echoMaturity(0))
        assertEquals(EchoMaturity.DISCOVERING, echoMaturity(1))
        assertEquals(EchoMaturity.DISCOVERING, echoMaturity(2))
        assertEquals(EchoMaturity.EMERGING, echoMaturity(3))
        assertEquals(EchoMaturity.EMERGING, echoMaturity(6))
        assertEquals(EchoMaturity.KNOWN, echoMaturity(7))
        assertEquals(EchoMaturity.KNOWN, echoMaturity(27))
        assertEquals(EchoMaturity.MATURE, echoMaturity(28))
        assertEquals(EchoMaturity.MATURE, echoMaturity(120))
    }

    @Test
    fun negativeBaselineDaysClampToSeed() {
        assertEquals(EchoMaturity.SEED, echoMaturity(-1))
        assertEquals(EchoMaturity.SEED, echoMaturity(Int.MIN_VALUE))
    }

    @Test
    fun maturityProgressIsMonotonic() {
        for (days in 0..40) {
            assertEquals(echoMaturity(days), echoMaturity(days))
            if (days > 0) {
                assert(
                    echoMaturity(days).ordinal >= echoMaturity(days - 1).ordinal
                ) { "成熟度必须单调不减：day $days" }
            }
        }
    }

    @Test
    fun seedRuntimeTextShowsObservedMinutes() {
        assertEquals("正在了解今天的节律 · 已观察 12 分钟", presenceSeedRuntimeText(12))
        assertEquals("正在了解今天的节律 · 已观察 0 分钟", presenceSeedRuntimeText(0))
        // 负值 clamp 到 0（时钟异常兜底）
        assertEquals("正在了解今天的节律 · 已观察 0 分钟", presenceSeedRuntimeText(-5))
    }

    @Test
    fun seedCopyIsPresenceNotInterpretation() {
        // SEED 是陪伴表达，不是画像输出：不含任何心理推断词
        assertFalse(containsBlockedVocabulary(PRESENCE_COPY_SEED_TITLE))
        assertFalse(containsBlockedVocabulary(PRESENCE_COPY_SEED_BODY))
        assertFalse(containsBlockedVocabulary(presenceSeedRuntimeText(3)))
        // 不伪造个性判断：不出现「比平时/比平常」
        assertFalse(PRESENCE_COPY_SEED_BODY.contains("比平时"))
        assertFalse(PRESENCE_COPY_SEED_BODY.contains("比平常"))
    }
}
