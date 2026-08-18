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
                0 to -965707645164287056L,
                3 to 9197311960022180733L,
                7 to 6179919190120822305L,
                28 to 8923888165716064134L,
                90 to 5185203257911434280L,
                180 to -8868815685587422700L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to -6627969303171779823L,
                3 to -6228636030418828187L,
                7 to 4020712353912670751L,
                28 to 5227658099182709694L,
                90 to -6009615661123486897L,
                180 to -3523762484450592554L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to -5845818874871671200L,
                3 to 7461291206924663471L,
                7 to 6692251711556167156L,
                28 to 214349292672834345L,
                90 to 3732976891239033140L,
                180 to -9068832354481980139L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to 8932181095648174204L,
                3 to -4992409994003019045L,
                7 to -1783635736202706660L,
                28 to 8396590719807880293L,
                90 to -7310453006574040892L,
                180 to -1009019744940000998L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to -4204542476048537745L,
                3 to -2870028854641250056L,
                7 to -2146545007330946880L,
                28 to -7702003626892436473L,
                90 to -2905919337618250696L,
                180 to 2547887370954725394L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to 4993870595445257379L,
                3 to 6103054220217829222L,
                7 to 8541015474651453314L,
                28 to -3538891105080557613L,
                90 to -6335491425560634855L,
                180 to -8012042577882208824L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to 4706705508030145995L,
                3 to -7252476491110600417L,
                7 to -4848292824419206726L,
                28 to 8322674600889230603L,
                90 to 3930227139254146553L,
                180 to -3286691583607781784L,
            ),
        )
    }
}
