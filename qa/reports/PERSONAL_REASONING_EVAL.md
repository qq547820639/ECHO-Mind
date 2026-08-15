# Personal Reasoning Quality Eval（ERA 22 / Batch 2）

> 回答必须越来越像「只有我的 ECHO 才能回答」。本报告为 Batch 2 的量化门与已修复缺陷。
> 全部 eval 位于 `:feature:qa`，确定性可重放（同一 fixture 恒同结果）。

## 1. Personal Question Evaluation Set（§24/§25）

- 题库 **115 条**（8 主题：节律漂移 / 周质量 / 月对比 / 周末分化 / 相似日 / 稳定性 / 今天为什么不一样 / 上下文与纠正召回），每条携带 Expected Evidence：期望任务 + 时间窗 + baseline/correction/context/confirmed 标志 + 期望证据类别。
- 覆盖 Master Prompt 的 8 条示例问题（q001/q007/q013/q019/q025/q029/q033/q038）。

## 2. Question Classifier（§26）

- 从「关键词先命中先得」升级为结构化规则分类器（意图优先 / 显式周月总结 / 今天解释 / 用户自述召回 / 纵向趋势 / 个人问题 / fallback），deterministic、零依赖、provider neutral，fallback 保留。
- **分类准确率 94.8%（109/115）**（门 ≥85%）。
- 关键行为锚点：「我说过/记得」→ ANSWER_PERSONAL_QUESTION（触发 CORRECTION/CONTEXT 检索）；「这个月和上个月的区别」→ 纵向趋势而非月报；「我该怎么办」→ 行动类。

## 3. Context Retrieval Recall（§27）

离线 eval：Query → 候选证据（D 出差 Day 90 真实 fixture 画像时间线 + 出差场景记忆，输入顺序确定性打乱）→ Expected top-k。

| 指标 | 结果 | 门 |
|---|---|---|
| Recall@5 | **1.0** | 1.0 |
| Recall@10 | **1.0** | 1.0 |
| CorrectionRecall | **1.0** | 1.0 |
| ContextExceptionRecall | **1.0** | 1.0 |
| UserConfirmedRecall | **1.0** | 1.0 |
| 排序与输入顺序无关 | **true** | true |

**发现并修复（生产）**：`FIND_LONGITUDINAL_PATTERN`/`SUMMARIZE_WEEK`/`SUMMARIZE_MONTH` 的 allowed 集合缺少 `USER_CORRECTIONS`——CORRECTION 记忆映射为该类别后被编译层过滤，**用户纠正对纵向问题从未到达模型**（§28 直接违约）。已把 USER_CORRECTIONS 加入三个任务 allowed；同时编译预算改为「记忆先行占用」（观察证据再多也不得把用户自述挤出 token 预算）。

## 4. Correction Reuse（§28）

端到端（EchoContextRetriever 假端口 + EchoContextCompiler）：
- 纠正前：「最近我是不是越来越晚？」编译上下文不含「出差」；
- 用户纠正「不太像——我最近在出差…」写入 CORRECTION + CONTEXT 记忆；
- 下一次相似问题：纠正排第 1、Context 进 top5、编译上下文含用户原话与「出差」，usedSources 含 USER_CORRECTIONS/CONTEXT_EXCEPTIONS；
- Day 90（出差窗口早已结束）再问「我之前跟你说过我在出差」仍检索到。

## 5. Grounding claim-evidence compatibility（§29）

- 新增规则：状态断言词（压力/疲惫/沮丧/失眠/心烦/崩溃/心情不好/情绪不好/累垮/撑不住）只有「用户自己说过」（correction/context_exception/memory 证据原文含该词）才允许出现在回答里。
- 负例锚定：证据只是「屏幕使用比平常晚 40 分钟」→「你最近压力很大。」**必须失败**；正例：用户自述「我最近压力很大…」→ 允许引用。7 个断言词全覆盖矩阵测试。
- 中性行为句（晚一些/零散/多）不要求自述支撑——行为语言与状态断言边界清晰。

## 6. Narrative Distillation + Persona Stability（§30/§31）

- 新增 `NarrativeDistiller`：剥招牌句（句首填充 + 句尾「希望/如果/请问/基于」从句）、压感叹号、您→你、最多 2 句/80 字、确定性；已接入 `AiNarrativeService` 三条链路（now/answer/longitudinal）。
- **Provider-neutral persona 门**：同一意思的 6 种 Provider 风格（官方腔/口语感叹/客服敬语/AI 招牌/简洁/冗长）蒸馏后**全部收敛到同一句**「你最近开始得比平常晚一些。」——换模型时语言能力可变，克制程度与表达节奏不变。

## 7. 本轮门禁

- `:feature:qa` 61 测试全绿（含 Batch 1 的 fixture/identity/season/wallpaper 门）；
- backend 1076 passed（上一轮全量，本轮无后端变更）；
- app 全量单元测试（跑批中）+ detekt 干净。
