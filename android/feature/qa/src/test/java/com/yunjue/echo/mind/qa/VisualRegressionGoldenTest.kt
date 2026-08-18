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
         * 本轮再生（审计修复）：P1-4 buildDailyComposition 开放度改由真实 maturity 驱动
         * （SEED→MATURE 成长视觉传导进日构图，day≥3 帧变化）+ P1-2 帧运动求值改 Long
         * nanos 时基（各周期先 mod 再转 Float，canonical 12s 锚的 Float 舍入路径随之变化）。
         * 视觉意图不变：仅修正成长开放度传导与大 uptime 相位精度。
         */
        val GOLDEN: Map<String, Map<Int, Long>> = mapOf(
            "PROFILE_A_STABLE" to mapOf(
                0 to -5924949369037277691L,
                3 to -7831890446957498069L,
                7 to 741078999638388851L,
                28 to -7670109804189219725L,
                90 to -5711627876226442298L,
                180 to 8858476470749948613L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to 5066765594635725869L,
                3 to 6248026494812006049L,
                7 to -6559277146227545301L,
                28 to -5802607663314462648L,
                90 to -7702691394111584636L,
                180 to -2345210406872999937L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to 6000535988749220743L,
                3 to 6073433465078876343L,
                7 to -628213372053809488L,
                28 to -8376361103957500868L,
                90 to 5955173373223291738L,
                180 to 3273105276932377051L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to -4674525490671313656L,
                3 to 6341478686748552544L,
                7 to -4236186243971329512L,
                28 to -6234808906294518021L,
                90 to -2656304412972396493L,
                180 to 2312804242064426045L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to -8872915584013655572L,
                3 to 7772815266078300482L,
                7 to 6990222377412345976L,
                28 to 4516423567065440918L,
                90 to -1076387452291026204L,
                180 to -9174377614777809450L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to -5092003507759433868L,
                3 to 3581824422224329621L,
                7 to -345501416373146789L,
                28 to -2785685915508624958L,
                90 to -5391598439112979072L,
                180 to 1768159244827918283L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to -4903146377064541604L,
                3 to 3236119138693893005L,
                7 to 6936691450037679464L,
                28 to -7251044413523166710L,
                90 to 6536096907692089129L,
                180 to 8785959373415320814L,
            ),
        )
    }
}
