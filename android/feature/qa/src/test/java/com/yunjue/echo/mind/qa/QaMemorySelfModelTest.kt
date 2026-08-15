package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemorySensitivity
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.PatternState
import com.yunjue.echo.mind.memory.RetentionClass
import com.yunjue.echo.mind.memory.buildSelfModel
import com.yunjue.echo.mind.memory.consolidateObservations
import com.yunjue.echo.mind.memory.contextExceptionContent
import com.yunjue.echo.mind.memory.contextExceptionInfo
import com.yunjue.echo.mind.memory.echoKnowsLines
import com.yunjue.echo.mind.memory.promotePatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 23（Batch 3）— Memory / Self Model 质量门：
 * §32 EchoSelfModel 六域 / §33 晋升生命周期 / §34 置信合成 /
 * §35 矛盾标记不覆盖 / §36 合并 / §37 敏感度。
 */
class QaMemorySelfModelTest {

    private val dayMs = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun observation(
        id: String,
        content: String,
        createdAt: Long = t0,
        confidence: Float = 0.8f,
    ) = EchoMemory(
        id = id, userId = "qa-user", type = MemoryType.OBSERVATION, content = content,
        source = "observation-core", confidence = confidence, createdAt = createdAt,
        lastConfirmedAt = createdAt, importance = 30, retentionClass = RetentionClass.SHORT_TERM,
        provenance = "observation:v1",
    )

    private fun correction(id: String, content: String, createdAt: Long = t0) = EchoMemory(
        id = id, userId = "qa-user", type = MemoryType.CORRECTION, content = content,
        source = "ask-echo", confidence = 0.9f, createdAt = createdAt, lastConfirmedAt = createdAt,
        importance = 80, retentionClass = RetentionClass.LONG_TERM, provenance = "correction:v1",
        sensitivity = MemorySensitivity.SENSITIVE,
    )

    // ===== §33 晋升生命周期 =====

    @Test
    fun promotionWalksObservedCandidateConfirmed() {
        val content = "工作日晚间结束时间持续后移"
        val one = promotePatterns(listOf(observation("o1", content)), emptyList(), emptyList(), emptyList(), t0)
        assertEquals(PatternState.OBSERVED, one.single().state)
        assertEquals(1, one.single().occurrenceCount)

        val two = promotePatterns(
            listOf(observation("o1", content), observation("o2", content)),
            emptyList(), emptyList(), emptyList(), t0,
        )
        assertEquals(PatternState.CANDIDATE, two.single().state)

        val three = promotePatterns(
            listOf(observation("o1", content), observation("o2", content), observation("o3", content)),
            emptyList(), emptyList(), emptyList(), t0,
        )
        assertEquals(PatternState.CONFIRMED, three.single().state)
        assertTrue("确认置信 ≥ 0.5", three.single().confidence >= 0.5f)
    }

    @Test
    fun confirmedIsNotPermanentTruth() {
        // §33：确认后仍被持续重新评估——纠正可以把它打回 WEAKENING
        val content = "工作日 09:10 左右开始活跃"
        val obs = (1..4).map { observation("o$it", content) }
        val confirmed = promotePatterns(obs, emptyList(), emptyList(), emptyList(), t0)
        assertEquals(PatternState.CONFIRMED, confirmed.single().state)

        val weakened = promotePatterns(
            obs, confirmed, listOf(correction("c1", "不太像：工作日 09:10 左右开始活跃只是最近在赶项目")),
            emptyList(), t0,
        )
        assertEquals("一次纠正 → WEAKENING", PatternState.WEAKENING, weakened.single().state)
        assertTrue("纠正后置信显著下调", weakened.single().confidence < confirmed.single().confidence)

        val conflicting = promotePatterns(
            obs, weakened,
            listOf(correction("c1", "不太像：工作日 09:10 左右开始活跃只是最近在赶项目"),
                correction("c2", "再次确认：工作日 09:10 左右开始活跃是特殊时期")),
            emptyList(), t0,
        )
        assertEquals("两次纠正 → CONFLICTING", PatternState.CONFLICTING, conflicting.single().state)
    }

    @Test
    fun contradictionHistorySurvivesAcrossRounds() {
        // §35：与既有模式合并时保留历史矛盾计数（不静默覆盖）
        val content = "周末比工作日晚起约两小时"
        val obs = (1..4).map { observation("o$it", content) }
        val confirmed = promotePatterns(obs, emptyList(), emptyList(), emptyList(), t0)
        val weakened = promotePatterns(obs, confirmed, listOf(correction("c1", "周末比工作日晚起约两小时不准确")), emptyList(), t0)
        assertEquals(1, weakened.single().contradictionCount)
        // 下一轮即便纠正列表为空，历史矛盾计数保留
        val nextRound = promotePatterns(obs, weakened, emptyList(), emptyList(), t0)
        assertEquals("历史矛盾计数不丢失", 1, nextRound.single().contradictionCount)
        assertEquals("状态不被静默恢复", PatternState.WEAKENING, nextRound.single().state)
    }

    @Test
    fun staleContradictedPatternGoesOutdatedAndCanRevive() {
        val content = "工作日晚间结束时间持续后移"
        val obs = (1..4).map { observation("o$it", content) }
        val confirmed = promotePatterns(obs, emptyList(), emptyList(), emptyList(), t0)
        val weakened = promotePatterns(obs, confirmed, listOf(correction("c1", "工作日晚间结束时间持续后移不准确")), emptyList(), t0)
        // 60+ 天无新证据且曾有矛盾 → OUTDATED
        val later = t0 + 90 * dayMs
        val outdated = promotePatterns(obs, weakened, emptyList(), emptyList(), later)
        assertEquals(PatternState.OUTDATED, outdated.single().state)
        // 新的确认证据 → 复活为 CONFIRMED
        val revived = promotePatterns(obs + observation("o5", content, createdAt = later), outdated, emptyList(), emptyList(), later)
        assertEquals(PatternState.CONFIRMED, revived.single().state)
    }

    // ===== §34 置信合成 =====

    @Test
    fun confidenceGrowsWithCountAndSpanAndDropsWithWeakEvidence() {
        val content = "晚间的屏幕互动集中在深夜"
        val base = promotePatterns(
            (1..3).map { observation("o$it", content, createdAt = t0, confidence = 0.8f) },
            emptyList(), emptyList(), emptyList(), t0,
        ).single()

        val spanned = promotePatterns(
            (1..3).map { observation("o$it", content, createdAt = t0 + it * 20 * dayMs, confidence = 0.8f) },
            emptyList(), emptyList(), emptyList(), t0 + 60 * dayMs,
        ).single()
        assertTrue("时间跨度 ≥14 天应提高置信", spanned.confidence > base.confidence)

        val weak = promotePatterns(
            (1..3).map { observation("o$it", content, createdAt = t0, confidence = 0.3f) },
            emptyList(), emptyList(), emptyList(), t0,
        ).single()
        assertTrue("弱证据一致性拖低置信", weak.confidence < base.confidence)
    }

    @Test
    fun contextExceptionCoverageDiscountsConfidence() {
        val content = "活跃起点比平时早很多"
        val obs = (1..4).map { observation("o$it", content, createdAt = t0 + it * dayMs) }
        val plain = promotePatterns(obs, emptyList(), emptyList(), emptyList(), t0 + 4 * dayMs).single()
        // 覆盖日期 = 观察区间内的某天（确定性取自 t0 + 2 天）
        val coveredDate = java.time.Instant.ofEpochMilli(t0 + 2 * dayMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val covered = promotePatterns(
            obs, emptyList(), emptyList(),
            listOf(
                EchoMemory(
                    id = "ctx1", userId = "qa-user", type = MemoryType.CONTEXT,
                    content = contextExceptionContent("travel", "出差", coveredDate),
                    source = "ask-echo", confidence = 0.9f, createdAt = t0, lastConfirmedAt = t0,
                    importance = 70, retentionClass = RetentionClass.LONG_TERM, provenance = "context:v1",
                ),
            ),
            t0 + 4 * dayMs,
        ).single()
        assertTrue("特殊时期覆盖证据区间 → 置信打折", covered.confidence < plain.confidence)
    }

    // ===== §36 合并 =====

    @Test
    fun manyShortObservationsConsolidateIntoOne() {
        val obs = (1..20).map { observation("o$it", "最近晚结束", createdAt = t0 + it * 2 * dayMs) }
        val consolidated = consolidateObservations(obs, t0 + 50 * dayMs, minGroupSize = 4)
        assertEquals("20 条同内容 → 1 条合并记忆", 1, consolidated.size)
        assertTrue("合并内容携带时间跨度", consolidated.single().content.contains("过去"))
        assertEquals(20, consolidated.single().occurrenceCount)
        assertEquals(20, consolidated.single().sourceMemoryIds.size)
    }

    @Test
    fun smallGroupsAreNotConsolidated() {
        val obs = (1..3).map { observation("o$it", "偶尔晚结束") }
        assertTrue("少于 4 条不合并", consolidateObservations(obs, t0).isEmpty())
    }

    @Test
    fun consolidationCapsOutput() {
        val obs = buildList {
            for (g in 1..8) {
                for (i in 1..6) add(observation("o${g}_$i", "重复行为第 $g 类", createdAt = t0 + i * dayMs))
            }
        }
        assertEquals("最多合并 5 条", 5, consolidateObservations(obs, t0 + 30 * dayMs).size)
    }

    // ===== §32 EchoSelfModel + §45 knowsLines =====

    @Test
    fun selfModelBuildsSixDomainsFromMemories() {
        val memories = listOf(
            observation("o1", "工作日晚间结束时间持续后移"),
            observation("o2", "工作日晚间结束时间持续后移"),
            observation("o3", "工作日晚间结束时间持续后移"),
            EchoMemory(
                id = "ctx1", userId = "qa-user", type = MemoryType.CONTEXT,
                content = contextExceptionContent("travel", "出差", "2026-01-25"),
                source = "ask-echo", confidence = 0.9f, createdAt = t0, lastConfirmedAt = t0,
                importance = 70, retentionClass = RetentionClass.LONG_TERM, provenance = "context:v1",
            ),
            EchoMemory(
                id = "pref1", userId = "qa-user", type = MemoryType.PREFERENCE,
                content = "喜欢安静一点的视觉", source = "me", confidence = 0.8f,
                createdAt = t0, lastConfirmedAt = t0, importance = 40,
                retentionClass = RetentionClass.LONG_TERM, provenance = "pref:v1",
            ),
            correction("c1", "不太像：最近是在出差，不是长期变化"),
        )
        val model = buildSelfModel(memories, t0)
        assertTrue("rhythmPatterns 非空", model.confirmedPatterns.isNotEmpty())
        assertEquals("travel", model.contexts.single().kind)
        assertTrue("preferences 含视觉偏好", model.preferences.any { it.contains("视觉") })
        // ERA 31 BATCH 3：interactionPreferences 已删除（无生产消费方）；交互类偏好并入 preferences
        assertTrue("corrections 含纠正原文", model.corrections.single().contains("出差"))
    }

    @Test
    fun knowsLinesAreNaturalLanguageAndExcludeSensitiveCorrections() {
        val model = buildSelfModel(
            listOf(
                observation("o1", "工作日晚间结束时间持续后移"),
                observation("o2", "工作日晚间结束时间持续后移"),
                observation("o3", "工作日晚间结束时间持续后移"),
                correction("c1", "别把我最近的晚睡当成常态"),
            ),
            t0,
        )
        val lines = echoKnowsLines(model)
        assertTrue("knowsLines 非空", lines.isNotEmpty())
        assertTrue("≤ 6 行", lines.size <= 6)
        assertTrue("第一行是自然语言观察", lines.first().startsWith("我观察到："))
        assertFalse("纠正原文（敏感）不得出现在公开 knowsLines", lines.any { it.contains("晚睡") })
    }

    @Test
    fun emptySelfModelStillSpeaksHumbly() {
        val model = buildSelfModel(emptyList(), t0)
        val lines = echoKnowsLines(model)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("慢慢积累"))
    }

    @Test
    fun outdatedPatternIsPresentedAsChallengedNotCurrentTruth() {
        // §27 用户可见验收：人类模式改变后，ECHO 忘掉「过去的我」——
        // 旧模式 60+ 天无新证据且曾被纠正 → OUTDATED → knowsLines 说「有些出入」，
        // 不再把旧模式当当前事实说「我观察到」。
        val now = t0 + 90 * dayMs
        val oldPattern = "工作日晚间结束时间持续后移"
        val model = buildSelfModel(
            listOf(
                observation("o1", oldPattern),
                observation("o2", oldPattern),
                observation("o3", oldPattern),
                correction("c1", "$oldPattern——那是赶项目期间，不是常态"),
            ),
            now,
        )
        val lines = echoKnowsLines(model)
        assertTrue("旧模式必须以「有些出入」呈现：$lines", lines.any { it.contains("有些出入") })
        assertFalse("旧模式不得再以当前事实呈现：$lines",
            lines.any { it.contains("我观察到：") && it.contains("晚结束") })
    }

    @Test
    fun staleConfirmedPatternWithoutCorrectionIsPresentedAsPast() {
        // ERA 32 R08（§32 Old Me）：用户没纠正过的「静默变化」也不得把过去当现在——
        // 60+ 天无新观察的确认模式改说「以前观察到…最近没再看到」，不再用现在时。
        val now = t0 + 90 * dayMs
        val content = "工作日晚间结束时间持续后移"
        val model = buildSelfModel(
            listOf(
                observation("o1", content),
                observation("o2", content),
                observation("o3", content),
            ),
            now,
        )
        val lines = echoKnowsLines(model)
        assertTrue("静默陈旧模式应标注「以前观察到」：$lines",
            lines.any { it.startsWith("以前观察到：") && it.contains("最近没再看到") })
        assertFalse("静默陈旧模式不得以现在时呈现：$lines",
            lines.any { it.startsWith("我观察到：") && it.contains("晚结束") })
    }

    // ===== §37 敏感度 + 上下文例外质量 =====

    @Test
    fun correctionMemoriesDefaultToSensitive() {
        val c = correction("c1", "不太像")
        assertEquals(MemorySensitivity.SENSITIVE, c.sensitivity)
        assertEquals(MemorySensitivity.PERSONAL, observation("o1", "观察").sensitivity)
    }

    @Test
    fun contextExceptionRoundTripAndInvalidDates() {
        val withDate = contextExceptionInfo(contextExceptionContent("travel", "出差", "2026-01-25"))!!
        assertEquals("travel", withDate.kind)
        assertEquals("出差", withDate.note)
        assertEquals("2026-01-25", withDate.date)
        // 非法日期不伪造：无日期输出，解析为 null date
        val noDate = contextExceptionInfo(contextExceptionContent("travel", "出差", "not-a-date"))!!
        assertEquals(null, noDate.date)
        // 非该格式的普通内容 → null（不编造）
        assertEquals(null, contextExceptionInfo("今天状态不错"))
    }

    @Test
    fun travelFixtureDrivesContextMemoryQuality() {
        // D 的出差窗口（Day 20-27）→ CONTEXT 记忆携带日期 → 自模型上下文域可见
        val timeline = QaTimeline(QaProfiles.D_TRAVEL)
        val window = QaProfiles.D_TRAVEL.specialWindows.first()
        val content = contextExceptionContent("travel", window.label, timeline.dateOf(window.fromDay).toString())
        val model = buildSelfModel(
            listOf(
                EchoMemory(
                    id = "ctx1", userId = "qa-user", type = MemoryType.CONTEXT, content = content,
                    source = "fixture", confidence = 0.9f, createdAt = t0, lastConfirmedAt = t0,
                    importance = 70, retentionClass = RetentionClass.LONG_TERM, provenance = "fixture:v1",
                ),
            ),
            t0,
        )
        assertEquals("2026-01-25", model.contexts.single().date)
        assertTrue(echoKnowsLines(model).any { it.contains("出差") || it.contains("旅行") })
    }

}
