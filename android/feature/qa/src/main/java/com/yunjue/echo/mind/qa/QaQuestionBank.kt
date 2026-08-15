package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.ReasoningTaskId

/**
 * ERA 22 §24/§25 — Personal Question Evaluation Set。
 *
 * 每条用例携带 Expected Evidence（不是评价文案像不像，而是检查：
 * 是否检索正确时间窗 / 是否使用正确 baseline / 是否考虑 correction /
 * 是否考虑 context exception / 是否引用正确证据类别）。
 *
 * 8 个主题 × 核心问题 + 确定性变体 → ≥100 条。
 */
object QaQuestionBank {

    data class ExpectedEvidence(
        val task: ReasoningTaskId,
        /** 期望检索的时间窗（天）。 */
        val timeWindowDays: Int,
        val needsBaseline: Boolean = false,
        val needsCorrection: Boolean = false,
        val needsContextException: Boolean = false,
        val needsUserConfirmed: Boolean = false,
        /** 期望出现在 top-k 的证据类别。 */
        val keyCategories: Set<DataSourceCategory> = emptySet(),
    )

    data class QuestionCase(
        val id: String,
        val question: String,
        val expected: ExpectedEvidence,
    )

    private fun expect(
        task: ReasoningTaskId,
        windowDays: Int,
        baseline: Boolean = false,
        correction: Boolean = false,
        context: Boolean = false,
        userConfirmed: Boolean = false,
        categories: Set<DataSourceCategory> = emptySet(),
    ) = ExpectedEvidence(task, windowDays, baseline, correction, context, userConfirmed, categories)

    /** 核心问题（8 主题，手写）。 */
    private val CORE: List<QuestionCase> = listOf(
        // ===== 1. 节律漂移（later/earlier） =====
        QuestionCase("q001", "最近我是不是越来越晚？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true, context = true)),
        QuestionCase("q002", "我最近是不是开始得更早了？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true, context = true)),
        QuestionCase("q003", "我的起床时间在慢慢后移吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),
        QuestionCase("q004", "最近一个月我明显变晚了吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),
        QuestionCase("q005", "这段时间我的晚上结束时间有什么趋势？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),
        QuestionCase("q006", "我的活跃起点和半年前一样吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),

        // ===== 2. 周质量（碎片化） =====
        QuestionCase("q007", "为什么这个星期特别碎？",
            expect(ReasoningTaskId.SUMMARIZE_WEEK, 7, baseline = true, context = true)),
        QuestionCase("q008", "这周我是不是比平时忙乱？",
            expect(ReasoningTaskId.SUMMARIZE_WEEK, 7, baseline = true)),
        QuestionCase("q009", "这一周和上一周有什么变化？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 14, baseline = true)),
        QuestionCase("q010", "这个星期为什么看起来这么零散？",
            expect(ReasoningTaskId.SUMMARIZE_WEEK, 7, baseline = true, context = true)),
        QuestionCase("q011", "最近一周我比平时更集中了吗？",
            expect(ReasoningTaskId.SUMMARIZE_WEEK, 7, baseline = true)),
        QuestionCase("q012", "这周的工作日是不是特别紧张？",
            expect(ReasoningTaskId.SUMMARIZE_WEEK, 7, baseline = true, context = true)),

        // ===== 3. 月对比 =====
        QuestionCase("q013", "这个月和上个月最大的区别是什么？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true, context = true)),
        QuestionCase("q014", "这个月我比上个月更晚睡了吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),
        QuestionCase("q015", "上个月和这个月我的屏幕时间差多少？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),
        QuestionCase("q016", "这个月的工作日节奏跟上个月一样吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),
        QuestionCase("q017", "最近两个月我最大的改变是什么？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true, context = true)),
        QuestionCase("q018", "这个月比上个月动得多还是少？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),

        // ===== 4. 周末 vs 工作日 =====
        QuestionCase("q019", "周末和平时有什么变化？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 60, baseline = true)),
        QuestionCase("q020", "我周末是不是比工作日懒散很多？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 60, baseline = true)),
        QuestionCase("q021", "工作日和周末我的节奏差多少？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 60, baseline = true)),
        QuestionCase("q022", "周末我会更晚开始活跃吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 60, baseline = true)),
        QuestionCase("q023", "我的周末和工作日像两个人吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 60, baseline = true)),
        QuestionCase("q024", "周末我的屏幕时间和工作日差多少？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 60, baseline = true)),

        // ===== 5. 相似日检索 =====
        QuestionCase("q025", "最近哪几天最像今天？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 28, baseline = true)),
        QuestionCase("q026", "今天像上周的哪一天？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 14, baseline = true)),
        QuestionCase("q027", "有没有哪几天和今天节奏很像？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 28, baseline = true)),
        QuestionCase("q028", "今天最像最近什么时候的我？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 28, baseline = true)),

        // ===== 6. 稳定性 =====
        QuestionCase("q029", "我最近稳定了吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 28, baseline = true)),
        QuestionCase("q030", "这段时间我的作息稳不稳定？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 28, baseline = true)),
        QuestionCase("q031", "我最近是不是波动很大？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 28, baseline = true)),
        QuestionCase("q032", "和上个月比，我这个月更规律了吗？",
            expect(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, 90, baseline = true)),

        // ===== 7. 今天为什么不一样 =====
        QuestionCase("q033", "为什么你觉得今天不一样？",
            expect(ReasoningTaskId.EXPLAIN_CURRENT_STATE, 7, baseline = true, context = true)),
        QuestionCase("q034", "今天为什么开始得比平时晚？",
            expect(ReasoningTaskId.EXPLAIN_CURRENT_STATE, 7, baseline = true, context = true)),
        QuestionCase("q035", "今天的状态和平时有什么不同？",
            expect(ReasoningTaskId.EXPLAIN_CURRENT_STATE, 7, baseline = true, context = true)),
        QuestionCase("q036", "为什么今天这么反常？",
            expect(ReasoningTaskId.EXPLAIN_CURRENT_STATE, 7, baseline = true, context = true)),
        QuestionCase("q037", "今天为什么这么碎？",
            expect(ReasoningTaskId.EXPLAIN_CURRENT_STATE, 7, baseline = true, context = true)),

        // ===== 8. 上下文/纠正召回 =====
        QuestionCase("q038", "我说过最近在出差，这有没有影响？",
            expect(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 28, context = true, correction = true,
                categories = setOf(DataSourceCategory.CONTEXT_EXCEPTIONS, DataSourceCategory.USER_CORRECTIONS))),
        QuestionCase("q039", "我之前跟你说过我在出差，还记得吗？",
            expect(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 28, context = true, correction = true,
                categories = setOf(DataSourceCategory.CONTEXT_EXCEPTIONS, DataSourceCategory.USER_CORRECTIONS))),
        QuestionCase("q040", "我纠正过你的那次，后来你改了吗？",
            expect(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 28, correction = true,
                categories = setOf(DataSourceCategory.USER_CORRECTIONS))),
        QuestionCase("q041", "你说过我在冲刺期，现在还在吗？",
            expect(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 28, context = true,
                categories = setOf(DataSourceCategory.CONTEXT_EXCEPTIONS))),
        QuestionCase("q042", "我确认过周末会晚起，你的观察一致吗？",
            expect(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 60, baseline = true, userConfirmed = true,
                categories = setOf(DataSourceCategory.PREFERENCES, DataSourceCategory.PORTRAIT_HISTORY))),
        QuestionCase("q043", "你还记得我确认过的那些事情吗？",
            expect(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 28, userConfirmed = true,
                categories = setOf(DataSourceCategory.PREFERENCES))),
    )

    /** 变体模板：对核心问题的确定性改写（同 Expected Evidence）。 */
    private val VARIANTS: List<Pair<String, (String) -> String>> = listOf(
        "v_please" to { q -> "告诉我，$q" },
        "v_echo" to { q -> "ECHO，$q" },
        "v_qmark" to { q -> q.trimEnd('？') + "？" },
        "v_casual" to { q -> q.replaceFirst("我", "俺").replaceFirst("？", "呢？") },
    )

    /** 全量题库（核心 + 变体 → ≥100）。 */
    val ALL: List<QuestionCase> = buildList {
        CORE.forEach { add(it) }
        CORE.take(24).forEach { case ->
            VARIANTS.forEach { (suffix, transform) ->
                val variant = transform(case.question)
                if (variant != case.question) {
                    add(QuestionCase("${case.id}$suffix", variant, case.expected))
                }
            }
        }
    }

    /**
     * ERA 31 BATCH 2 §20 — Core Personal Reasoning Set（26 条最高价值问题）。
     *
     * 从 CORE 43 条中精选：每一条都直接命中「只有我的 ECHO 才能回答」+
     * Day 30/90/180 验收场景。人审/回答质量 review（Answer Quality A-D 四层）
     * 以本集合为优先对象，其余题目继续存在测试但不分散开发。
     * 选择记录与理由见 qa/reports/CORE_PERSONAL_REASONING_SET.md。
     */
    val CORE_PERSONAL_IDS: Set<String> = setOf(
        // 节律漂移（Day 30 验收：「最近是不是越来越晚？」）
        "q001", "q004", "q005", "q006",
        // 周质量 / 碎片化（「为什么最近这么碎？」）
        "q007", "q009", "q010",
        // 月对比（「这个月和上个月最大的变化？」）
        "q013", "q015", "q017",
        // 周末 vs 工作日（「周末和平时有什么区别？」）
        "q019", "q021", "q023",
        // 相似日（「最近哪几天和今天最像？」）
        "q025", "q028",
        // 稳定性（「最近稳定下来了吗？」）
        "q029", "q031", "q032",
        // 今天为什么不一样（「为什么今天不一样？」）
        "q033", "q035", "q037",
        // 上下文 / 纠正召回（「上次我说在出差」「我纠正过你什么？」）
        "q038", "q040", "q042", "q043",
        // 长期变化（Day 180 验收：「这半年我有什么变化？」对应半年趋势）
        "q014",
    )

    fun byId(id: String): QuestionCase = ALL.first { it.id == id }

    /** Core Personal Reasoning Set 展开（人审顺序稳定）。 */
    val CORE_PERSONAL: List<QuestionCase> = CORE.filter { it.id in CORE_PERSONAL_IDS }
}
