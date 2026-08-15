package com.yunjue.echo.mind.qa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Batch 8 — 视觉回归黄金集（7 profile × 6 锚点日 APP 帧哈希）。
 *
 * 锁定「同一输入恒同帧」的跨版本视觉血缘：任何渲染/映射/身份的改动
 * 都必须是有意为之并同步更新黄金值（更新前先跑其他产品质量门）。
 */
class VisualRegressionGoldenTest {

    /** 帧摘要哈希（FNV-1a 64：粒子坐标/颜色/半径全部纳入）。 */
    private fun frameHash(snap: QaDaySnapshot): Long {
        val frame = QaTimeline.computeFrame(snap)
        var hash = -3750763034362895579L // FNV offset
        fun feed(v: Any) {
            val bytes = v.toString().toByteArray()
            for (b in bytes) {
                hash = hash xor (b.toLong() and 0xFF)
                hash *= 1099511628211L
            }
        }
        feed(frame.backgroundCenterColor)
        feed(frame.backgroundEdgeColor)
        feed(frame.accentColor)
        feed(frame.coreRadiusFraction)
        feed(frame.ringRadiusFraction)
        feed(frame.ringAlpha)
        frame.particles.forEach { p -> feed("${p.x},${p.y},${p.radiusFraction},${p.alpha}") }
        return hash
    }

    private fun goldenFor(profile: QaProfileSpec, day: Int): Long {
        val snap = QaTimeline(profile).snapshotAt(day)
        return frameHash(snap)
    }

    @Test
    fun allFortyTwoAnchorFramesMatchGoldenHashes() {
        val mismatches = mutableListOf<String>()
        for (profile in QaProfiles.ALL) {
            for (day in QaProfiles.SNAPSHOT_DAYS) {
                val expected = GOLDEN[profile.id]?.get(day)
                val actual = goldenFor(profile, day)
                if (expected != null && expected != actual) {
                    mismatches += "${profile.id} day$day expected=$expected actual=$actual"
                }
            }
        }
        assertEquals("视觉回归黄金集漂移（改渲染必须有意为之并同步更新黄金值）：\n" + mismatches.joinToString("\n"),
            emptyList<String>(), mismatches)
    }

    companion object {
        /** 黄金值（2026-08 Product Quality Era R8 生成；渲染改动需同步更新）。 */
        val GOLDEN: Map<String, Map<Int, Long>> = mapOf(
            "PROFILE_A_STABLE" to mapOf(
                0 to 1971569938039394187L,
                3 to 9164492421526947268L,
                7 to 1073082752663521134L,
                28 to 849186746776370409L,
                90 to 5433353950693572231L,
                180 to 6211416433378784131L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to -3638772498787147426L,
                3 to 7267552435732451710L,
                7 to -3961035773630084523L,
                28 to 6724371751830065499L,
                90 to 7080790262660994938L,
                180 to 6149063886892077663L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to -6203479018106862038L,
                3 to -7603533728091904883L,
                7 to -2615580432758446265L,
                28 to -658346588006363393L,
                90 to -2339096625341711894L,
                180 to 3622304070038244768L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to -6424405438305328925L,
                3 to 2451580284964866236L,
                7 to 2878645960916976528L,
                28 to 2980914234489123275L,
                90 to -4557669451211553838L,
                180 to -8433763979841321594L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to -1538055773110379003L,
                3 to 1449026529900089696L,
                7 to 1334427920504146215L,
                28 to 1423226970099077605L,
                90 to -6378318493011656774L,
                180 to -5929800774354633276L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to 5952604763572871744L,
                3 to -3350423384949706738L,
                7 to -7069116712671922883L,
                28 to -4696373632052244132L,
                90 to 5307368343374899224L,
                180 to 8494806639602706893L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to -593834902951483716L,
                3 to -6390340846329501296L,
                7 to 2238485181663348016L,
                28 to -387636121066257754L,
                90 to 4351930825307015375L,
                180 to -3816704137912649086L,
            ),
        )
    }
}
