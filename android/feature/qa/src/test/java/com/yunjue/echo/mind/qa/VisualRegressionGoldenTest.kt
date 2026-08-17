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
         * 黄金值（V3 §H 语义核心重写后重生成：GenomeDeriver 退役，
         * 改为 EchoVisualMapper → VisualGenomeCompiler 唯一链 + 中性恒定身份；
         * 表面差异由 crop 亮度上限 + defaultQualityFor 质量预算承载）。
         */
        val GOLDEN: Map<String, Map<Int, Long>> = mapOf(
            "PROFILE_A_STABLE" to mapOf(
                0 to 2786289453408036824L,
                3 to -6504744376119538468L,
                7 to 674583441806594159L,
                28 to 239331487929078743L,
                90 to -1127580557505183261L,
                180 to -4185504494229815647L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to -1915032207954416805L,
                3 to -1574139531129134379L,
                7 to -6143624585181403810L,
                28 to -1939330660220557313L,
                90 to -1220899722346895876L,
                180 to -9055453483688398548L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to -4232242525122445563L,
                3 to -7729843092388103166L,
                7 to -3684885488972172419L,
                28 to -7862166962813932057L,
                90 to 2574950800277556260L,
                180 to -2925171788936207594L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to 4983677444429191754L,
                3 to -5566470602976013474L,
                7 to -8255880660159943118L,
                28 to -4225338641833632985L,
                90 to -4610284910155125874L,
                180 to 8697462115141847380L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to -6100520707160139590L,
                3 to -227919567771909025L,
                7 to -4531134291981227180L,
                28 to 3745054823640837287L,
                90 to -5601046616380232882L,
                180 to -531746324639263303L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to -1693610429000148997L,
                3 to -6131631667607230364L,
                7 to 9192411931819510321L,
                28 to -2301210621370853044L,
                90 to 1169095716556346878L,
                180 to 6744238456804120414L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to 6138605642487531181L,
                3 to -8289061297086584883L,
                7 to 8235834654905910853L,
                28 to 3682653816337529603L,
                90 to -6202708757600667142L,
                180 to -6236355802609244604L,
            ),
        )
    }
}
