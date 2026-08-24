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
                if (expected != actual) {
                    // T8 P1-2：缺黄金值（expected==null）同样计入失败——新增 profile 忘加黄金值必须红
                    mismatches += if (expected == null) {
                        "${profile.id} day$day MISSING golden (actual=$actual) — 黄金集缺条目，须补值"
                    } else {
                        "${profile.id} day$day expected=$expected actual=$actual"
                    }
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
        assertEquals(
            "视觉回归黄金集漂移或缺条目（改渲染必须有意为之并同步更新黄金值；缺值=失败）：\n" +
                mismatches.joinToString("\n"),
            emptyList<String>(), mismatches,
        )
    }

    companion object {
        /**
         * 黄金值（ECHO Organism Quality Pass 后重生成：真实 Lab 彩度 + LCh hue 蓝紫锚定 +
         * 拓扑 v4（非闭合环/碎片 24-40/粒子壳层收敛）+ 呼吸 8.2–10.2s / 自转 30–55min +
         * AGL mask B 深度通道 + 有机暗腔谐波。有意改渲染，同步更新黄金值）。
         *
         * 本轮再生（深化迭代，四因素）：①T5-P2-5 genomeFromParams 不再以 0.47 常量覆盖
         * dayComposition——QA computeFrame 消费真实日构图指纹（此前 42 帧被压平为同相位）；
         * ②T2-P2-2 确定性盐分段（层间独立随机流；结构族——数量/半径/hue/lobe/chirality——不变）；
         * ③T2-P2-10 depth01 满幅映射（0..0.5→0..1，AGSL 深度雾分层修正，JVM 帧哈希不含 depth）；
         * ④P1-2 Long nanos 时基（canonical 12s 锚的 Float 舍入路径变化）。
         * 均为有意变更；统一声明见 .trae/specs/deepen-iteration-p2-ux/notes/LEDGER.md。
         */
        val GOLDEN: Map<String, Map<Int, Long>> = mapOf(
            "PROFILE_A_STABLE" to mapOf(
                0 to -2371052970191289708L,
                3 to -7582444780493313975L,
                7 to 7718909054208930825L,
                28 to -7507185147723817486L,
                90 to -6012878691730350822L,
                180 to -4985824099600049087L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to -2640874503757878291L,
                3 to 5302607127780226039L,
                7 to -17591373763819214L,
                28 to 3604313884685594038L,
                90 to 922181257981823549L,
                180 to 7611073684239586388L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to -2896882090818855327L,
                3 to 1568202181875776512L,
                7 to -1401870650487441632L,
                28 to 6017438356547649336L,
                90 to 9127251825106929627L,
                180 to -3900407792607780715L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to -9156805540013040755L,
                3 to -9037643146275439794L,
                7 to -202631976247800744L,
                28 to -4217550775759865346L,
                90 to 448254855990150679L,
                180 to 876396878009858285L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to -5764677253089175800L,
                3 to 7873481001293417884L,
                7 to -7160894110649462039L,
                28 to -8011941016740227122L,
                90 to 5114668812477230299L,
                180 to -1898181169506951995L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to -2614051673433409452L,
                3 to -1597510515551953477L,
                7 to -760850212965259918L,
                28 to 3085648287817969300L,
                90 to -4087625057528586335L,
                180 to 5690688181802461790L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to 1022161764904849044L,
                3 to 8581610524312626471L,
                7 to -2634196983447037160L,
                28 to 332182752148384166L,
                90 to -5640399083828602514L,
                180 to -2501423455247390288L,
            ),
        )
    }
}
