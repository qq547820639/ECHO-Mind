package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.presence.EchoIdentityGenome
import com.yunjue.echo.mind.presence.identityDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 21 §14 — Identity Continuity Eval：
 *
 * 同一用户 Day 1 / Day 30 / Day 90 / Day 180 必须明显是同一个 ECHO；
 * 同用户长期距离必须显著小于不同用户间距离
 * （same-user long-term distance < different-user distance）。
 */
class IdentityContinuityEvalTest {

    private val dayAnchors = listOf(1, 30, 90, 180)

    private fun genomesAt(profile: QaProfileSpec, dayIndex: Int): EchoIdentityGenome {
        val t = QaTimeline(profile)
        return t.snapshotAt(dayIndex).identity
    }

    @Test
    fun sameUserAcrossMonthsIsSameEcho() {
        for (profile in QaProfiles.ALL) {
            val genomes = dayAnchors.map { genomesAt(profile, it) }
            // 与 Day 1 参照比较
            val ref = genomes.first()
            for ((i, day) in dayAnchors.withIndex()) {
                val d = identityDistance(ref, genomes[i])
                assertTrue(
                    "$profile Day1→Day$day 距离应 < 0.06（实际 ${"%.4f".format(d)}）",
                    d < 0.06f,
                )
            }
            // 相邻锚点间也要近
            for (i in 1 until genomes.size) {
                assertTrue(
                    "$profile 相邻锚点距离 < 0.04（实际 ${"%.4f".format(identityDistance(genomes[i - 1], genomes[i]))}）",
                    identityDistance(genomes[i - 1], genomes[i]) < 0.04f,
                )
            }
        }
    }

    @Test
    fun sameUserDistanceIsBelowDifferentUserDistance() {
        var maxSameUser = 0f
        var minDifferentUser = Float.MAX_VALUE

        // 同用户最远距离
        for (profile in QaProfiles.ALL) {
            val genomes = dayAnchors.map { genomesAt(profile, it) }
            for (i in genomes.indices) {
                for (j in i + 1 until genomes.size) {
                    maxSameUser = maxOf(maxSameUser, identityDistance(genomes[i], genomes[j]))
                }
            }
        }
        // 不同用户最近距离
        val profiles = QaProfiles.ALL
        for (i in profiles.indices) {
            for (j in i + 1 until profiles.size) {
                val gi = genomesAt(profiles[i], 90)
                val gj = genomesAt(profiles[j], 90)
                minDifferentUser = minOf(minDifferentUser, identityDistance(gi, gj))
            }
        }

        assertTrue(
            "同用户长期距离（${"%.4f".format(maxSameUser)}）应显著小于不同用户距离（${"%.4f".format(minDifferentUser)}）",
            maxSameUser < minDifferentUser * 0.5f,
        )
    }

    @Test
    fun identityColorAndStructureAreStableAcrossGrowth() {
        val t = QaTimeline(QaProfiles.A_STABLE)
        val day1 = t.snapshotAt(1)
        val day90 = t.snapshotAt(90)
        val day180 = t.snapshotAt(180)

        val f1 = QaTimeline.computeFrame(day1)
        val f90 = QaTimeline.computeFrame(day90)
        val f180 = QaTimeline.computeFrame(day180)

        // 身份决定的色相跨半年不变（同一 ECHO 的视觉血缘）：
        // 背景色 value 分量随当日 activity 呼吸（合法），hue 分量必须恒定（暗色量化容差 0.05）。
        assertTrue("背景色相 Day1=Day180", hueOf(f1.backgroundCenterColor).closeTo(hueOf(f180.backgroundCenterColor)))
        assertTrue("背景色相 Day1=Day90", hueOf(f1.backgroundCenterColor).closeTo(hueOf(f90.backgroundCenterColor)))
        // 强调色 RGB 分量（s/v 固定，仅 alpha 随状态变）跨半年完全一致
        assertEquals("强调色 RGB Day1=Day180", f1.accentColor and 0x00FFFFFF, f180.accentColor and 0x00FFFFFF)
        // 身份结构参数完全不变（安装种子决定）
        assertEquals(day1.identity.coreTopology, day180.identity.coreTopology, 1e-6f)
        assertEquals(day1.identity.symmetryTendency, day180.identity.symmetryTendency, 1e-6f)
        assertEquals(day1.identity.orbitGeometry, day180.identity.orbitGeometry, 1e-6f)
        // 成熟度塑形开放度（成长可见，但不重置身份）
        assertNotEquals("成熟度应改变核心开放度", f1.coreRadiusFraction, f180.coreRadiusFraction)
    }

    /** ARGB → hue（0..1）；暗色背景的 hue 提取容差 0.05。 */
    private fun hueOf(argb: Int): Float {
        val r = (argb ushr 16 and 0xFF) / 255f
        val g = (argb ushr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta <= 1e-4f) return 0f
        val hue = when (max) {
            r -> (g - b) / delta % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        return (hue / 6f + 1f) % 1f
    }

    private fun Float.closeTo(other: Float): Boolean {
        val d = kotlin.math.abs(this - other) % 1f
        return minOf(d, 1f - d) < 0.05f
    }

    @Test
    fun allProfilesHaveVisiblyDistinctFrames() {
        val colors = QaProfiles.ALL.map { QaTimeline.computeFrame(QaTimeline(it).snapshotAt(90)).backgroundCenterColor }
        // 7 个 profile 的安装种子不同 → 主色应全部互异（帧渲染使用种子散列色相）
        assertEquals("7 个用户主色互异", 7, colors.toSet().size)
    }
}
