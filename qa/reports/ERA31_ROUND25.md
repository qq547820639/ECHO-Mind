# ERA 31 Round 25 报告（Ask ECHO 证据说人话：z 分数与工程键退役）

> 日期：2026-08-15。

## 走查发现

继续 Ask ECHO 信任走查（R24 依据来源之后），审查回答的**证据行**（answer.evidence），
发现三处工程泄漏：

1. 「为什么今天不一样 / 为什么今天 ECHO 看起来不一样」→ 证据
   `维度 RHYTHM = LATER（z=1.2）`——原始维度键 + 枚举 + z 分数直接甩给用户；
2. 「今天不碎 / 今天和平时接近」分支 → `STABILITY = VERY_SIMILAR`；
3. 「最近稳定了吗」→ 证据尾部 `节律漂移 0.42`——无单位浮点对用户不可解读。

§11/§57：Headline 与证据都不是数据摘要；z 分数属于 Engineering Layer，
不属于「参考了」清单。

## 修复（PersonalAnswerEngine）

1. whyToday 证据 → 「今天差异最大的维度：作息（偏晚）」——`dimensionDisplayName` +
   `dimensionValueText`（core:model 单一词表，与 Scene facts 同源）；
2. 无差异分支 → 「整体节律：与平时接近」；
3. stability 证据 → `整体节律有轻微漂移 / 整体节律稳定`（与正文漂移句同一 0.3 阈值）。

## 回归

- `whyTodayDifferentNamesTheStrongestDimension` 断言更新：证据含「作息（偏晚）」，
  不泄露 RHYTHM/z 分数。
- QA 快照/回答捕获（answers.md、PROFILE_*.md）确定性重生成——证据文案变化随之更新
  （生成器自有，非手改）。

## 实测

- Android 1026 全绿 + detekt + `:app:lintDebug` PASS（本轮无新增测试——既有锚点
  语义更新锁新行为）。
