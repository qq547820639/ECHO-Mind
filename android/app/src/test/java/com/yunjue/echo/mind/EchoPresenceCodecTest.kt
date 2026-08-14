package com.yunjue.echo.mind

import com.yunjue.echo.mind.presence.EchoIdentityGenome
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.presence.EchoPresenceCodec
import com.yunjue.echo.mind.presence.EchoPresenceState
import com.yunjue.echo.mind.presence.RhythmState
import com.yunjue.echo.mind.sensing.SensingRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ERA 3：Presence 快照编解码回归（Wallpaper/Dream 只读该快照，fail-closed 解析）。
 */
class EchoPresenceCodecTest {

    private fun sampleState() = EchoPresenceState(
        updatedAt = Instant.ofEpochSecond(1753632000),
        sensingStatus = SensingRuntimeStatus.ACTIVE,
        maturity = EchoMaturity.KNOWN,
        rhythmState = RhythmState(activityLevel = 0.62f, rhythmDelta = 0.1f, regularity = 0.55f, coverage = 0.71f),
        confidence = 0.68f,
        identityGenome = EchoIdentityGenome(seed = 987654321L, accentHue = 0.6f),
    )

    @Test
    fun roundTripPreservesState() {
        val encoded = EchoPresenceCodec.encode(sampleState())
        val decoded = EchoPresenceCodec.decode(encoded) ?: error("decode 不应失败")
        assertEquals(sampleState(), decoded)
    }

    @Test
    fun decodeIsFailClosed() {
        assertNull(EchoPresenceCodec.decode(null))
        assertNull(EchoPresenceCodec.decode(""))
        assertNull(EchoPresenceCodec.decode("garbage"))
        assertNull(EchoPresenceCodec.decode("v9|1|2|3"))
        // 未知枚举名 → null（向前兼容失败即中性占位）
        val badStatus = sampleState().let {
            EchoPresenceCodec.encode(it).replace("ACTIVE", "WEIRD_STATE")
        }
        assertNull(EchoPresenceCodec.decode(badStatus))
    }

    @Test
    fun snapshotContainsNoNarrativeText() {
        // 快照只含数值与枚举，绝不含叙事文字（锁屏 Public Safe 由构造保证）
        val encoded = EchoPresenceCodec.encode(sampleState())
        for (word in listOf("焦虑", "情绪", "压力", "安静", "活跃")) {
            assertTrue("快照不应含叙事词：$word", !encoded.contains(word))
        }
    }
}
