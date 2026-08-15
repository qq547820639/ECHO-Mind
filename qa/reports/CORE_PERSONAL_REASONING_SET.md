# Core Personal Reasoning Set（BATCH 2 §20）

> ERA 31 BATCH 2 第 1 项：从现有题库中选出最重要的 **26 条**作为
> Core Personal Reasoning Set。其余问题继续存在测试，但不再分散开发注意力。
> 代码事实源：`QaQuestionBank.CORE_PERSONAL_IDS` / `CORE_PERSONAL`（android/feature:qa）。

## 选择标准

每一条必须至少命中一项：

1. **只有我的 ECHO 才可能回答**（答案必须来自这个人的 Baseline/History/Context/Memory/Corrections）；
2. **Final Acceptance 场景**（Day 30「最近是不是越来越晚」/ Day 90「纠正真的被使用」/
   Day 180「这半年我有什么变化」）；
3. **回答质量可人审**（Evidence correctness / Context correctness / Personal usefulness / ECHO voice 四层）。

## 集合（26 条，8 主题）

| 主题 | 问题 | 理由 |
|---|---|---|
| 节律漂移 | q001 最近我是不是越来越晚？ | Day 30 验收原题 |
| | q004 最近一个月我明显变晚了吗？ | 漂移显著性人审 |
| | q005 这段时间我的晚上结束时间有什么趋势？ | 漂移第二锚点（结束时间） |
| | q006 我的活跃起点和半年前一样吗？ | Day 180 验收同构 |
| 周质量 | q007 为什么这个星期特别碎？ | 碎片化原题 |
| | q009 这一周和上一周有什么变化？ | 周环比 |
| | q010 这个星期为什么看起来这么零散？ | 碎片化变体（context 必需） |
| 月对比 | q013 这个月和上个月最大的区别是什么？ | 月对比原题 |
| | q015 上个月和这个月我的屏幕时间差多少？ | 具体数字可核验 |
| | q017 最近两个月我最大的改变是什么？ | context 必需的月对比 |
| 周末 vs 工作日 | q019 周末和平时有什么变化？ | 原题 |
| | q021 工作日和周末我的节奏差多少？ | 可量化 |
| | q023 我的周末和工作日像两个人吗？ | 个人化表达（User truth 边界） |
| 相似日 | q025 最近哪几天最像今天？ | 原题 |
| | q028 今天最像最近什么时候的我？ | 个人化变体 |
| 稳定性 | q029 我最近稳定了吗？ | 原题 |
| | q031 我最近是不是波动很大？ | 稳定性反方向 |
| | q032 和上个月比，我这个月更规律了吗？ | 规律性趋势 |
| 今天为什么不一样 | q033 为什么你觉得今天不一样？ | 原题（Explanation 主链） |
| | q035 今天的状态和平时有什么不同？ | 状态对比 |
| | q037 今天为什么这么碎？ | 单日碎片解释 |
| 上下文/纠正 | q038 我说过最近在出差，这有没有影响？ | Correction reuse 验收原题 |
| | q040 我纠正过你的那次，后来你改了吗？ | 纠正闭环可验证 |
| | q042 我确认过周末会晚起，你的观察一致吗？ | User confirmed 优先级 |
| | q043 你还记得我确认过的那些事情吗？ | What ECHO Knows 记忆面 |
| 长期变化 | q014 这个月我比上个月更晚睡了吗？ | Day 180「这半年变化」的月度同构锚 |

## 使用方式（BATCH 2 后续项）

1. **真实回答人审**：对这 26 条逐条跑真实链路（QuestionClassifier → ContextRanker → Grounding →
   叙事/确定性降级），回答按 Answer Quality 四层（A Evidence / B Context / C Personal usefulness / D ECHO voice）打分记录；
2. **Correction → Future Reasoning 闭环**：q038/q040/q042 为闭环验收用例（correction 后同一问题
   回答必须自然融入 context，不是机械复述「你之前说过……」）；
3. **Grounding overreach 检查**：q023/q036 类主观问题验证 Evidence before interpretation 边界；
4. 其余 17 条核心 + 全量变体保留在 regression，不进本轮人审优先级。

## 反过度工程约束

- 本集合是**选择**，不是新 QA framework——没有新测试基建、没有新评分系统；
- 变体（v_please/v_echo/v_qmark/v_casual）不进入本集合（同 Expected Evidence，重复评阅无信息量）。
