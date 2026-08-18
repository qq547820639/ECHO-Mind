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
                0 to 3047018046388533548L,
                3 to 2260570464605937018L,
                7 to -1583933021619688630L,
                28 to -705977391979649256L,
                90 to -4949677588732952812L,
                180 to -5816084826397578336L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to -3807062739093048920L,
                3 to 3592071187704863881L,
                7 to 7122126307033476496L,
                28 to 4583545417973819039L,
                90 to -796162799421656849L,
                180 to 4021137911797746253L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to -442259832836037632L,
                3 to -3008802345041977463L,
                7 to -6606415092817562417L,
                28 to 2623288186237537963L,
                90 to 7017667372498603723L,
                180 to -6574654304690053093L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to -4617110067381514319L,
                3 to 8557158365734888610L,
                7 to 4631335034000727602L,
                28 to -105646302560099046L,
                90 to 660621732244806830L,
                180 to 72147742667487715L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to 7558235573065098126L,
                3 to -6672606976441890677L,
                7 to 6190481324409264027L,
                28 to 6960208465409903276L,
                90 to -5509047520833254028L,
                180 to -58875287367104237L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to -7411454617506238432L,
                3 to 6553979555688717689L,
                7 to -8530530922601249118L,
                28 to 3661750031714664640L,
                90 to -3011227836466288163L,
                180 to -3381478549586203483L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to -340339223780908638L,
                3 to -8260388236439760558L,
                7 to 6186270390300499524L,
                28 to -8291700914446654419L,
                90 to -9172935837686813990L,
                180 to 1322767827243739063L,
            ),
        )
    }
}
