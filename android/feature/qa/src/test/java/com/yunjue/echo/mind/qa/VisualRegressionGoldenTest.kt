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
        feed(frame.textureFamily)
        feed(frame.contrast)
        frame.extraRings.forEach { feed("${it.radiusFraction},${it.alpha}") }
        frame.particles.forEach { p ->
            feed("${p.x},${p.y},${p.radiusFraction},${p.alpha},${p.streakLength},${p.streakDirX},${p.streakDirY}")
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
                0 to 1669986756197806128L,
                3 to -3519019148247797043L,
                7 to 507182913284167094L,
                28 to -6694603047645385794L,
                90 to 3872031034459735819L,
                180 to 7621979488207323922L,
            ),
            "PROFILE_B_NIGHT_OWL" to mapOf(
                0 to -244897554995760997L,
                3 to 6653888985586845277L,
                7 to -8050449222511522585L,
                28 to -2422357479064826762L,
                90 to -9164365190693916417L,
                180 to 6488824926315496164L,
            ),
            "PROFILE_C_IRREGULAR" to mapOf(
                0 to -3649532375274039188L,
                3 to 3198974423186129409L,
                7 to -5659993300333025793L,
                28 to 3050314129770243300L,
                90 to -5306239397707646606L,
                180 to -6626916932635316952L,
            ),
            "PROFILE_D_TRAVEL" to mapOf(
                0 to 3685232399730296287L,
                3 to -8106371701180884489L,
                7 to 7207409188845085459L,
                28 to -4082715309619861362L,
                90 to 5394654868756790802L,
                180 to -4776920813325993366L,
            ),
            "PROFILE_E_PROJECT_CRUNCH" to mapOf(
                0 to 5016293378218045123L,
                3 to 5385476005824193965L,
                7 to -3397350885913279956L,
                28 to 7461543173701993710L,
                90 to -7804897181299741766L,
                180 to -4864461286184926862L,
            ),
            "PROFILE_F_LOW_DATA" to mapOf(
                0 to -6282835688936304473L,
                3 to 6074665271136645373L,
                7 to -3295192136292986241L,
                28 to 6184185341677663071L,
                90 to 3471352108219402081L,
                180 to 6982349254142242401L,
            ),
            "PROFILE_G_WEEKEND_DIFFERENT" to mapOf(
                0 to -8024581698350048516L,
                3 to -446541527559493373L,
                7 to 1612456852995724661L,
                28 to -543810697796165897L,
                90 to 3113550895566961084L,
                180 to -8509596057000690899L,
            ),
        )
    }
}
