package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.model.PortraitDimensionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * ERA 31 BATCH 2 — PersonalAnswerEngine 单元测试（production 引擎 = QA oracle 同源）。
 *
 * 锁定的产品语义（对应 Answer Quality A Evidence / B Context / D Voice）：
 * 证据先行、数据不足诚实、中性词表、不认识的问题返回 null（不硬凑）。
 */
class PersonalAnswerEngineTest {

    private fun day(
        date: LocalDate,
        start: Int?,
        screen: Double = 300.0,
        dims: Map<String, PortraitDimensionDto> = emptyMap(),
    ) = PersonalDayFacts(
        date = date,
        activeStartMinute = start,
        activeEndMinute = start?.plus(900),
        screenOnMinutes = screen,
        lateScreenMinutes = 40.0,
        activeHourSpread = 0.5,
        movementIndex = 1.0,
        dimensions = dims,
        hasPortrait = dims.isNotEmpty(),
    )

    private fun dims(vararg pairs: Pair<String, String>) = pairs.associate { (k, v) ->
        k to PortraitDimensionDto(value = v, metric = null, z = null)
    }

    /** 带 z 值的维度（相似日检索需要真实 z 距离）。 */
    private fun dimsZ(vararg pairs: Pair<String, Double>) = pairs.associate { (k, z) ->
        k to PortraitDimensionDto(value = "SIMILAR", metric = null, z = z)
    }

    private val epoch = LocalDate.parse("2026-01-05") // Monday

    private fun series(
        days: Int,
        start: (Int) -> Int?,
        screen: (Int) -> Double = { 300.0 },
        dimsFor: (Int) -> Map<String, PortraitDimensionDto> = { emptyMap() },
    ): List<PersonalDayFacts> =
        (0 until days).map { i -> day(epoch.plusDays(i.toLong()), start(i), screen(i), dimsFor(i)) }

    private fun inputs(days: List<PersonalDayFacts>, drift: Float = 0f, corrections: List<String> = emptyList()) =
        PersonalAnswerInputs(
            dayIndex = days.size - 1,
            days = days,
            contextWindows = emptyList(),
            seasonDrift = drift,
            userCorrections = corrections,
        )

    @Test
    fun driftingLaterDetectsRealShiftWithEvidence() {
        // 前 45 天 09:00，后 45 天 09:45（+45min）→ 明显变晚 + 证据含分钟数
        val days = series(90, start = { i -> if (i < 45) 540 else 585 })
        val answer = PersonalAnswerEngine.answer("最近我是不是越来越晚？", inputs(days))!!
        assertTrue("回答应确认变晚：${answer.text}", answer.text.contains("明显更晚"))
        assertTrue("证据含起止分钟：${answer.evidence}", answer.evidence.contains("09:00") && answer.evidence.contains("09:45"))
    }

    @Test
    fun driftingLaterHonestWhenInsufficientData() {
        val days = series(10, start = { 540 })
        val answer = PersonalAnswerEngine.answer("最近我是不是越来越晚？", inputs(days))!!
        assertTrue("数据不足必须诚实：${answer.text}", answer.text.contains("还没攒够"))
    }

    @Test
    fun driftingLaterSaysStableWhenNoRealShift() {
        val days = series(90, start = { i -> 540 + i % 5 * 2 }) // 小噪声无趋势
        val answer = PersonalAnswerEngine.answer("最近我是不是越来越晚？", inputs(days))!!
        assertTrue("无趋势应说差不多：${answer.text}", answer.text.contains("差不多"))
    }

    @Test
    fun weekendVsWeekdayComparesWeekendAgainstWeekday() {
        // 90 天：工作日 09:00 / 周末 10:00；屏幕工作日 300 / 周末 420
        val days = series(90, start = { i ->
            when (epoch.plusDays(i.toLong()).dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> 600
                else -> 540
            }
        }, screen = { i ->
            when (epoch.plusDays(i.toLong()).dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> 420.0
                else -> 300.0
            }
        })
        val answer = PersonalAnswerEngine.answer("周末和平时有什么变化？", inputs(days))!!
        assertTrue("周末晚起：${answer.text}", answer.text.contains("晚起 60 分钟"))
        assertTrue("周末屏幕更多：${answer.text}", answer.text.contains("多 120 分钟"))
        assertTrue("证据含两端数字：${answer.evidence}", answer.evidence.contains("09:00") && answer.evidence.contains("10:00"))
    }

    @Test
    fun mostSimilarDaysFindsClosestDays() {
        val days = series(30, start = { i -> if (i in 26..29) 620 else 540 }, dimsFor = { i ->
            if (i >= 26) dims("RHYTHM" to "LATER", "STABILITY" to "SLIGHTLY_DIFFERENT")
            else if (i in 20..22) dims("RHYTHM" to "LATER", "STABILITY" to "SLIGHTLY_DIFFERENT")
            else dims("STABILITY" to "VERY_SIMILAR")
        })
        val answer = PersonalAnswerEngine.answer("最近哪几天最像今天？", inputs(days))!!
        // 20..22 的维度与 26..29 相同 → 最相似应落在 20-22
        val found = listOf("01-25", "01-26", "01-27").any { it in answer.text } // 20..22 → 01-25..01-27
        assertTrue("相似日应落在维度相同段：${answer.text}", found)
    }

    @Test
    fun similarDaysEvidenceSpeaksHumanNotZDistance() {
        // ERA 32：q025/q028 证据不再泄露 z 距离工程数值（同类缺陷：R25 whyToday / R40 QA 快照）
        val days = series(30, start = { 540 }, dimsFor = { i -> dimsZ("RHYTHM" to 1.0 + i * 0.1) })
        val answer = PersonalAnswerEngine.answer("最近哪几天最像今天？", inputs(days))!!
        assertTrue("证据是人话排序：${answer.evidence}", answer.evidence.contains("更接近今天"))
        assertTrue("不泄露 z 距离：${answer.evidence}", !answer.evidence.contains("z"))
    }

    @Test
    fun stabilityUsesStabilityRatioAndSeasonDrift() {
        val days = series(14, start = { 540 }, dimsFor = { i ->
            if (i < 2) dims("STABILITY" to "CLEARLY_DIFFERENT") else dims("STABILITY" to "VERY_SIMILAR")
        })
        val stable = PersonalAnswerEngine.answer("我最近稳定了吗？", inputs(days, drift = 0.1f))!!
        assertTrue("大多数接近 → 稳定：${stable.text}", stable.text.contains("算稳定的"))
        assertTrue("漂移低 → 不出现漂移提示：${stable.text}", !stable.text.contains("漂移"))
        val drifting = PersonalAnswerEngine.answer("我最近稳定了吗？", inputs(days, drift = 0.5f))!!
        assertTrue("漂移高 → 补充提示：${drifting.text}", drifting.text.contains("漂移"))
    }

    @Test
    fun stabilityMonthCompareAnswersTheQuestionAsked() {
        // ERA 32：q032 曾错挂单窗口 STABILITY（答「最近两周算稳定」而非月间对比）
        // 60 天：前 28 天波动（6/28 接近），后 28 天规律（28/28）→ 必须答「这个月更规律了」
        val days = series(60, start = { 540 }, dimsFor = { i ->
            if (i < 32) {
                if (i % 5 == 0) dims("STABILITY" to "VERY_SIMILAR") else dims("STABILITY" to "CLEARLY_DIFFERENT")
            } else {
                dims("STABILITY" to "VERY_SIMILAR")
            }
        })
        val answer = PersonalAnswerEngine.answer("和上个月比，我这个月更规律了吗？", inputs(days))!!
        assertTrue("回答月间对比：${answer.text}", answer.text.contains("这个月比上个月更规律了"))
        assertTrue("证据含两个月占比：${answer.evidence}",
            answer.evidence.contains("这个月") && answer.evidence.contains("上个月"))
        assertTrue("不再错答单窗口稳定性：${answer.text}", !answer.text.contains("最近两周"))

        val early = PersonalAnswerEngine.answer("和上个月比，我这个月更规律了吗？", inputs(series(30, start = { 540 })))!!
        assertTrue("不足两个月诚实：${early.text}", early.text.contains("不到两个月"))
    }

    @Test
    fun monthVsMonthComparesTwoMonths() {
        // 60 天：前 30 天 09:00/300min，后 30 天 09:30/390min
        val days = series(60, start = { i -> if (i < 30) 540 else 570 }, screen = { i -> if (i < 30) 300.0 else 390.0 })
        val answer = PersonalAnswerEngine.answer("这个月和上个月最大的区别是什么？", inputs(days))!!
        assertTrue("月对比含晚与屏幕：${answer.text}", answer.text.contains("晚一些") && answer.text.contains("屏幕时间比上个月多"))
    }

    @Test
    fun monthVsMonthNoChangeShowsNumbers() {
        // ERA 32：「没有明显差别」也要给出具体对照数字——结论可验证
        val days = series(60, start = { 540 })
        val answer = PersonalAnswerEngine.answer("这个月和上个月最大的区别是什么？", inputs(days))!!
        assertTrue("接近结论：${answer.text}", answer.text.contains("很接近"))
        assertTrue("证据含具体数字：${answer.evidence}",
            answer.evidence.contains("09:00") && answer.evidence.contains("300"))
    }

    @Test
    fun whyTodayDifferentNamesTheStrongestDimension() {
        val days = series(20, start = { 540 }, dimsFor = { i ->
            if (i == 19) dims("RHYTHM" to "LATER", "STABILITY" to "CLEARLY_DIFFERENT")
            else dims("STABILITY" to "VERY_SIMILAR")
        })
        val answer = PersonalAnswerEngine.answer("为什么你觉得今天不一样？", inputs(days))!!
        assertTrue("命名最强维度：${answer.text}", answer.text.contains("开始活跃的时间比平时晚"))
        assertTrue("证据是人话维度（ERA 31 R25，不泄露 RHYTHM/z 分数）：${answer.evidence}", answer.evidence.contains("作息（偏晚）"))
    }

    @Test
    fun travelContextUsesUserContextOverRawObservation() {
        // 出差窗口 36..39（含今天，活跃窗口内）：起点 07:00（平时 09:00）
        val days = series(40, start = { i -> if (i in 36..39) 420 else 540 })
        val withContext = inputs(days).copy(
            contextWindows = listOf(PersonalContextWindow(36, 39, "出差")),
            baselineWakeMinute = 540.0,
        )
        val answer = PersonalAnswerEngine.answer("我说过最近在出差，这有没有影响？", withContext)!!
        assertTrue("应承认影响：${answer.text}", answer.text.contains("有影响"))
        // ERA 31 R38：语句流畅化（「出差的这几天」而非「出差 这几天」生硬空格）
        assertTrue("语句应流畅：${answer.text}", answer.text.contains("出差的这几天"))
        assertTrue("证据含窗口与分钟：${answer.evidence}", answer.evidence.contains("出差") && answer.evidence.contains("07:00"))
    }

    @Test
    fun travelContextOldContextIsTreatedAsEnded() {
        // ERA 32 R03：生产 CONTEXT 记忆只有开始日期——60 天前的上下文不得再当「这几天」
        // （此前 toDay 恒为今天，三个月前的出差也被回答成「这几天在出差的窗口里」）。
        val days = series(90, start = { i -> if (i in 28..30) 420 else 540 })
        val withContext = inputs(days).copy(
            contextWindows = listOf(PersonalContextWindow(28, 89, "出差")),
            baselineWakeMinute = 540.0,
        )
        val answer = PersonalAnswerEngine.answer("我说过最近在出差，这有没有影响？", withContext)!!
        assertTrue("旧上下文应视为已结束：${answer.text}", answer.text.contains("已经结束了"))
        assertTrue("证据不编造结束日：${answer.evidence}", answer.evidence.contains("已过去"))
        assertTrue("证据不含工程口径：${answer.evidence}", !answer.evidence.contains("~"))
    }

    /**
     * T5-P2-1：PersonalAnswerInputs 是公开 data class——contextWindows 脏下标
     * （负 fromDay/toDay、越界）不得抛异常，且诚实降级（不编造窗口外结论）。
     */
    @Test
    fun travelContextWithDirtyNegativeIndicesDoesNotThrow() {
        val days = series(40, start = { i -> if (i < 36) 540 else 420 })
        val dirty = inputs(days).copy(
            contextWindows = listOf(PersonalContextWindow(fromDay = -7, toDay = -2, label = "出差")),
        )
        val answer = PersonalAnswerEngine.answer("我说过最近在出差，这有没有影响？", dirty)!!
        // 负窗口 clamp 到 0 后走「已结束/数据不足」诚实路径（不崩溃、不编造进行中）
        assertTrue("负窗口 clamp 后仍给中性回答：${answer.text}", answer.text.isNotBlank())
    }

    @Test
    fun travelContextWithNegativeToDayDoesNotThrow() {
        val days = series(40, start = { i -> if (i < 36) 540 else 420 })
        val dirty = inputs(days).copy(
            contextWindows = listOf(PersonalContextWindow(fromDay = 30, toDay = -5, label = "出差")),
        )
        val answer = PersonalAnswerEngine.answer("我说过最近在出差，这有没有影响？", dirty)!!
        assertTrue("负 toDay clamp 后仍给中性回答：${answer.text}", answer.text.isNotBlank())
    }

    @Test
    fun travelContextDayZeroWithWindowDoesNotThrow() {
        // dayIndex=0（刚认识）+ 声称窗口：窗口前无基线 → 诚实说数据不够
        val days = series(1, start = { 540 })
        val dirty = PersonalAnswerInputs(
            dayIndex = 0,
            days = days,
            contextWindows = listOf(PersonalContextWindow(fromDay = 0, toDay = 0, label = "出差")),
            seasonDrift = 0f,
        )
        val answer = PersonalAnswerEngine.answer("我说过最近在出差，这有没有影响？", dirty)!!
        assertTrue("day0 窗口回答诚实不崩：${answer.text}", answer.text.isNotBlank())
    }

    @Test
    fun travelContextHonestWhenNoWindow() {
        val days = series(40, start = { 540 })
        val answer = PersonalAnswerEngine.answer("我说过最近在出差，这有没有影响？", inputs(days))!!
        assertTrue("无上下文诚实：${answer.text}", answer.text.contains("没有找到"))
    }

    @Test
    fun halfYearChangeComparesSixMonths() {
        val days = series(181, start = { i -> if (i < 60) 540 else 570 }, screen = { i -> if (i < 60) 300.0 else 400.0 })
        val answer = PersonalAnswerEngine.answer("这半年我有什么变化？", inputs(days))!!
        assertTrue("半年晚起：${answer.text}", answer.text.contains("比半年前晚了约 30 分钟"))
        assertTrue("半年屏幕更多：${answer.text}", answer.text.contains("多了约 100 分钟"))
    }

    @Test
    fun halfYearNoChangeStillShowsRealNumbers() {
        // ERA 32：半年「非常接近」也要给出两端实际数字——「接近」要可验证
        val days = series(181, start = { 540 })
        val answer = PersonalAnswerEngine.answer("这半年我有什么变化？", inputs(days))!!
        assertTrue("稳定结论：${answer.text}", answer.text.contains("非常接近"))
        assertTrue("证据含具体数字：${answer.evidence}",
            answer.evidence.contains("09:00") && answer.evidence.contains("300"))
    }

    @Test
    fun correctionsRecallReplaysUserCorrections() {
        val days = series(10, start = { 540 })
        val answer = PersonalAnswerEngine.answer("我纠正过你的那次，后来你改了吗？",
            inputs(days, corrections = listOf("我最近在出差，不是变晚了")))!!
        assertTrue("回放纠正内容：${answer.text}", answer.text.contains("我最近在出差，不是变晚了"))
        val none = PersonalAnswerEngine.answer("我纠正过你的那次，后来你改了吗？", inputs(days))!!
        assertTrue("无纠正诚实：${none.text}", none.text.contains("还没有纠正过"))
    }

    @Test
    fun unknownQuestionReturnsNullForHonestAiFallback() {
        assertNull(PersonalAnswerEngine.answer("帮我写一首诗", inputs(series(5, start = { 540 }))))
    }

    @Test
    fun answerSourcesAreHonest() {
        // ERA 31 R24：依据双清单如实标注——上下文/纠正/确认回答不再一律「历史画像」
        val days = series(40, start = { i -> if (i in 20..39) 420 else 540 })
        val withContext = inputs(days).copy(contextWindows = listOf(PersonalContextWindow(20, 39, "出差")))
        val travel = PersonalAnswerEngine.answer("我说过最近在出差，这有没有影响？", withContext)!!
        assertTrue("上下文回答应标注你告诉我的特殊日期", travel.usedSources.contains(DataSourceCategory.CONTEXT_EXCEPTIONS))
        assertTrue("上下文回答同时用了画像历史", travel.usedSources.contains(DataSourceCategory.PORTRAIT_HISTORY))

        val corrections = PersonalAnswerEngine.answer(
            "我纠正过你的那次，后来你改了吗？",
            inputs(series(5, start = { 540 }), corrections = listOf("画像反馈：不太像（原因：旅行）")),
        )!!
        assertEquals(listOf(DataSourceCategory.USER_CORRECTIONS), corrections.usedSources)

        val noWindow = PersonalAnswerEngine.answer("我说过最近在出差，这有没有影响？", inputs(series(5, start = { 540 })))!!
        assertTrue("无上下文时不应谎称用了任何来源", noWindow.usedSources.isEmpty())

        val drift = PersonalAnswerEngine.answer("最近我是不是越来越晚？", inputs(series(90, start = { 540 })))!!
        assertEquals(listOf(DataSourceCategory.PORTRAIT_HISTORY), drift.usedSources)
    }

    @Test
    fun visualQuestionExplainsTodayLikeWhyToday() {
        // ERA 31 R17：Ask ECHO 界面建议的「为什么今天 ECHO 看起来不一样？」
        // = 今天最强的节律差异维度（Why 层同源），离线确定性可答。
        val days = series(20, start = { 540 }, dimsFor = { i ->
            if (i == 19) dims("RHYTHM" to "LATER") else dims("STABILITY" to "VERY_SIMILAR")
        })
        val answer = PersonalAnswerEngine.answer("为什么今天 ECHO 看起来不一样？", inputs(days))!!
        assertTrue("视觉问题应命名今天的差异维度：${answer.text}", answer.text.contains("开始活跃的时间比平时晚"))
    }

    @Test
    fun questionVariantsNormalizeToSameFamily() {
        // 用户微调措辞（加「了」/半角问号/感叹号/无标点）应命中同一族，而不是掉进兜底
        val days = series(90, start = { i -> if (i < 45) 540 else 585 })
        val canonical = PersonalAnswerEngine.answer("最近我是不是越来越晚？", inputs(days))!!
        for (variant in listOf("最近我是不是越来越晚了?", "最近我是不是越来越晚", "最近我是不是越来越晚了")) {
            val answer = PersonalAnswerEngine.answer(variant, inputs(days))
            assertTrue("变体「$variant」应命中同一族", answer != null)
            assertEquals(canonical, answer)
        }
        // 稳定族同样归一（「了吗！」→ 去 ！/吗/了）
        val stableDays = series(14, start = { 540 })
        val stableCanonical = PersonalAnswerEngine.answer("我最近稳定了吗？", inputs(stableDays))
        assertEquals(stableCanonical, PersonalAnswerEngine.answer("我最近稳定了吗！", inputs(stableDays)))
        assertEquals(stableCanonical, PersonalAnswerEngine.answer("我最近稳定了吗", inputs(stableDays)))
    }

    @Test
    fun endDriftAnswersAboutEndTimeNotStartTime() {
        // 起点恒定 09:00，结束时间 22:00 → 23:00（+60min）→ 必须说结束时间变晚
        val days = series(90, start = { 540 }, dimsFor = { emptyMap() }).mapIndexed { i, d ->
            d.copy(activeEndMinute = if (i < 45) 1320 else 1380)
        }
        val answer = PersonalAnswerEngine.answer("这段时间我的晚上结束时间有什么趋势？", inputs(days))!!
        assertTrue("应说结束时间变晚：${answer.text}", answer.text.contains("结束时间在慢慢变晚"))
        assertTrue("证据是结束时间而非起点：${answer.evidence}", answer.evidence.contains("结束时间中位数 22:00 → 23:00"))
    }

    @Test
    fun screenMonthDeltaAlwaysGivesNumbers() {
        // 差 15 分钟（小于 30 阈值）也必须给出具体数字
        val days = series(60, start = { 540 }, screen = { i -> if (i < 30) 300.0 else 315.0 })
        val answer = PersonalAnswerEngine.answer("上个月和这个月我的屏幕时间差多少？", inputs(days))!!
        assertTrue("回答必须含数字：${answer.text}", answer.text.contains("15 分钟"))
        assertTrue("证据含两个月数字：${answer.evidence}", answer.evidence.contains("300 分钟") && answer.evidence.contains("315 分钟"))
    }

    @Test
    fun weekSummaryComparesThisWeekWithLastWeek() {
        // 本周起点 +30min、屏幕 +60min、零散日 4 天 vs 上周 1 天（dayIndex=27：上周=14..20，本周=21..27）
        val days = series(28, start = { i -> if (i < 14) 540 else 570 }, screen = { i -> if (i < 14) 300.0 else 360.0 },
            dimsFor = { i ->
                if (i in 24..27) dims("DAY_STRUCTURE" to "MORE_FRAGMENTED", "STABILITY" to "SLIGHTLY_DIFFERENT")
                else if (i == 15) dims("DAY_STRUCTURE" to "MORE_FRAGMENTED", "STABILITY" to "SLIGHTLY_DIFFERENT")
                else dims("STABILITY" to "VERY_SIMILAR")
            })
        val answer = PersonalAnswerEngine.answer("为什么这个星期特别碎？", inputs(days))!!
        assertTrue("应提到零散日变多：${answer.text}", answer.text.contains("零散的日子比上周多"))
        assertTrue("证据含零散日计数：${answer.evidence}", answer.evidence.contains("零散日 1 → 4"))
    }

    @Test
    fun weekSummaryNoChangeShowsNumbers() {
        // ERA 32：「没有明显变化」也给出三项具体对照数字
        val days = series(14, start = { 540 })
        val answer = PersonalAnswerEngine.answer("这一周和上一周有什么变化？", inputs(days))!!
        assertTrue("接近结论：${answer.text}", answer.text.contains("很接近"))
        assertTrue("证据含三项数字：${answer.evidence}", answer.evidence.contains("零散日 0 → 0 天"))
    }

    @Test
    fun fragmentedTodayQuestionIsHonestWhenNotFragmented() {
        val days = series(20, start = { 540 }, dimsFor = { i ->
            if (i == 19) dims("SCREEN_AMOUNT" to "MORE", "STABILITY" to "CLEARLY_DIFFERENT")
            else dims("STABILITY" to "VERY_SIMILAR")
        })
        val answer = PersonalAnswerEngine.answer("今天为什么这么碎？", inputs(days))!!
        assertTrue("今天不碎必须诚实，不能拿屏幕时间顶替：${answer.text}", answer.text.contains("不算特别零散"))
    }

    @Test
    fun confirmedRecallReplaysUserConfirmedFacts() {
        val days = series(10, start = { 540 })
        val withConfirmed = inputs(days).copy(userConfirmed = listOf("周末会晚起"))
        val answer = PersonalAnswerEngine.answer("你还记得我确认过的那些事情吗？", withConfirmed)!!
        assertTrue("回放确认内容：${answer.text}", answer.text.contains("周末会晚起"))
        val none = PersonalAnswerEngine.answer("你还记得我确认过的那些事情吗？", inputs(days))!!
        assertTrue("无确认诚实：${none.text}", none.text.contains("还没有确认过"))
    }

    @Test
    fun confirmedWeekendCheckAlignsUserTruthWithObservation() {
        // 工作日 09:00 / 周末 10:00 → 观察与用户自述一致
        val days = series(90, start = { i ->
            when (epoch.plusDays(i.toLong()).dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> 600
                else -> 540
            }
        })
        val answer = PersonalAnswerEngine.answer("我确认过周末会晚起，你的观察一致吗？", inputs(days))!!
        assertTrue("观察一致：${answer.text}", answer.text.contains("一致") && answer.text.contains("60 分钟"))
        // 工作日/周末相同 → 诚实地按用户说法继续看（User truth 不被被动证据投票击败）
        val flatDays = series(90, start = { 540 })
        val flat = PersonalAnswerEngine.answer("我确认过周末会晚起，你的观察一致吗？", inputs(flatDays))!!
        assertTrue("用户自述优先：${flat.text}", flat.text.contains("按你的说法继续看"))
    }

    @Test
    fun allCoreSetPhrasingsRouteToFamilies() {
        val covered = listOf(
            "最近一个月我明显变晚了吗？",
            "这段时间我的晚上结束时间有什么趋势？",
            "工作日和周末我的节奏差多少？",
            "我的周末和工作日像两个人吗？",
            "今天最像最近什么时候的我？",
            "我最近是不是波动很大？",
            "和上个月比，我这个月更规律了吗？",
            "上个月和这个月我的屏幕时间差多少？",
            "今天的状态和平时有什么不同？",
            "今天为什么这么碎？",
            "我之前跟你说过我在出差，还记得吗？",
            "我的活跃起点和半年前一样吗？",
        )
        val days = series(30, start = { 540 }, dimsFor = { dims("STABILITY" to "VERY_SIMILAR") })
        for (q in covered) {
            assertTrue("Core Set 问法应被引擎覆盖：$q", PersonalAnswerEngine.answer(q, inputs(days)) != null)
        }
    }
}
