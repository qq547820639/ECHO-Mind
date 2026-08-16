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
        /** 黄金值（ERA 31 R1 生成：sceneRandom index 熵修复 + 帧模型消费纹理族/轨道几何/结构环/对比度）。 */
        val GOLDEN: Map<String, Map<Int, Long>> = mapOf(
            "PROFILE_A_STABLE" to mapOf(
                0 to -4932603199420414005L,
                3 to -4159758944046632020L,
                7 to 1196829234767883845L,
                28 to -540233612281671048L,
                90 to -4933399914285784102L,
                180 to -2665717585839627836L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to 3512445165336543324L,
                3 to -338366303148680410L,
                7 to -9095904767921762688L,
                28 to -7059604406997008461L,
                90 to 5984419141591177161L,
                180 to 1286566988796735577L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to 6191128551376491257L,
                3 to -6718074818125645770L,
                7 to 5482906981971248716L,
                28 to 256553283131701317L,
                90 to 5652318223923359935L,
                180 to 2355801739135257075L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to 4910420804512709084L,
                3 to -3374152334170159796L,
                7 to 7876204926987382555L,
                28 to 8762172178753099431L,
                90 to -4863255748587113921L,
                180 to 3323587081800399678L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to 1365408853587917215L,
                3 to 2329636536875397812L,
                7 to -2313599425448976813L,
                28 to 1757657678169745423L,
                90 to 1244878684959869869L,
                180 to -4598466376122949459L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to -5018115487663455255L,
                3 to 4139518522125876186L,
                7 to -1278097741409431024L,
                28 to -4190328338156815862L,
                90 to -2461482593702795065L,
                180 to -3502600487266976680L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to 6227233698772701873L,
                3 to -3565415430377798674L,
                7 to -124873136932804623L,
                28 to -7557827707774970432L,
                90 to 1340770257861729633L,
                180 to -4493905691882997111L,
            ),
        )
    }
}
