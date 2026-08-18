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
        feed(frame.ambientField.centerColor)
        feed(frame.ambientField.edgeColor)
        feed(frame.frontMembrane.color)
        feed(frame.coreCavity.radiusFraction)
        feed(frame.frontMembrane.radiusFraction)
        feed(frame.frontMembrane.alpha)
        feed(frame.structuralRings.size)
        feed(frame.longFilaments.size)
        feed(frame.localFragments.size)
        feed(frame.coreKnots.size)
        frame.structuralRings.forEach { ring -> ring.points.forEach { feed("${it.x},${it.y},${it.alpha}") } }
        frame.coreKnots.forEach { feed("${it.x},${it.y},${it.radiusFraction},${it.alpha}") }
        frame.particles.forEach { p ->
            feed("${p.x},${p.y},${p.radiusFraction},${p.alpha},${p.kind},${p.color}")
        }
        return hash
    }

    private fun goldenFor(profile: QaProfileSpec, day: Int): Long {
        val snap = QaTimeline(profile).snapshotAt(day)
        return frameHash(snap)
    }

    @Test
    fun allFortyTwoAnchorFramesMatchGoldenHashes() {
        val mismatches = mutableListOf<String>()
        val actualMap = LinkedHashMap<String, Map<Int, Long>>()
        for (profile in QaProfiles.ALL) {
            val byDay = LinkedHashMap<Int, Long>()
            for (day in QaProfiles.SNAPSHOT_DAYS) {
                val expected = GOLDEN[profile.id]?.get(day)
                val actual = goldenFor(profile, day)
                byDay[day] = actual
                if (expected != null && expected != actual) {
                    mismatches += "${profile.id} day$day expected=$expected actual=$actual"
                }
            }
            actualMap[profile.id] = byDay
        }
        if (mismatches.isNotEmpty()) {
            // 打印可粘贴的黄金值表（有意改渲染后用它更新 GOLDEN；先跑其他产品质量门再更新）
            println("=== GOLDEN REGEN ===")
            for ((id, byDay) in actualMap) {
                println("\"$id\" to mapOf(")
                for ((day, hash) in byDay) {
                    println("    $day to ${hash}L,")
                }
                println("),")
            }
        }
        assertEquals("视觉回归黄金集漂移（改渲染必须有意为之并同步更新黄金值）：\n" + mismatches.joinToString("\n"),
            emptyList<String>(), mismatches)
    }

    companion object {
        /**
         * 黄金值（ECHO Organism Quality Pass 后重生成：真实 Lab 彩度 + LCh hue 蓝紫锚定 +
         * 拓扑 v4（非闭合环/碎片 24-40/粒子壳层收敛）+ 呼吸 8.2–10.2s / 自转 30–55min +
         * AGL mask B 深度通道 + 有机暗腔谐波。有意改渲染，同步更新黄金值）。
         */
        val GOLDEN: Map<String, Map<Int, Long>> = mapOf(
            "PROFILE_A_STABLE" to mapOf(
                0 to 5473820547046315695L,
                3 to 8809768241631964629L,
                7 to -2829992596683578242L,
                28 to 3794299456768968402L,
                90 to 6917089540143827340L,
                180 to 4343198096148028642L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to -2729769070633727727L,
                3 to -1058044373057689436L,
                7 to -2443169104249783454L,
                28 to -7434121026275244977L,
                90 to -4467931510106013005L,
                180 to -6062591307602332424L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to 1655415090365077665L,
                3 to 8550520073685715355L,
                7 to 837031737572581687L,
                28 to -647236851216087042L,
                90 to 1072506913698088942L,
                180 to -4691646243866459290L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to 6963547666204445788L,
                3 to 6869894840508205503L,
                7 to 6985931269232768834L,
                28 to -8314820516130336827L,
                90 to -4177589258081725351L,
                180 to 3488979138204924703L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to 844786674713499644L,
                3 to -1340433292336335441L,
                7 to -7448584328983426410L,
                28 to -2729507404834867037L,
                90 to -605906705192193354L,
                180 to -3637379914744004025L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to -5092003507759433868L,
                3 to 169263782290188658L,
                7 to 6411257560164332370L,
                28 to -4718387380045935865L,
                90 to 8874519560228374955L,
                180 to 974842509581843573L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to 3554559839168027659L,
                3 to -5402627301635732828L,
                7 to 3333401786748455409L,
                28 to 1321621516596039862L,
                90 to -2674369152464266922L,
                180 to 5833323441124317685L,
            ),
        )
    }
}
