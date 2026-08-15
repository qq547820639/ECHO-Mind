# BATCH 2 §21 — Personal Reasoning 人类质量 Review（R3）

> 方法：对 Core Set 26 条跑 **production 回答引擎**（PersonalAnswerEngine，经 QaAskEcho 薄适配器——
> 捕获的回答 = 无 Provider/离线时用户真实所见），人审锚点 A·Day180 / D·Day90 / F·Day180。
> 机器捕获：`qa/reports/personal_answer_review/answers.md`（确定性重放，`PersonalAnswerReviewHarnessTest`）。
> 评价四层：A Evidence correctness / B Context correctness / C Personal usefulness / D ECHO voice。

## 0. 本轮最重要的产品变化

**Ask ECHO 在没有任何 AI Provider 时也能回答「只有我的 ECHO 才可能回答」的问题。**
此前 `answerQuestion` 无 Provider 直接返回「还没有连接 AI」——付费墙把核心个人问答挡死了
（违反免费核心承诺与 gate #5）。现在：

- `PersonalAnswerEngine`（:feature:intelligence，纯函数）覆盖 24/26 条 Core Set；
- `AiNarrativeService` 新增 `deterministicPersonalAnswer` 钩子：无 Provider / 离线 / Provider 失败
  三条路径都先尝试确定性个人回答，再落到旧诚实文案；
- 生产接线：`DeterministicPersonalAnswerProvider`（窗口化 181 天画像+聚合 / CONTEXT 记忆 /
  CORRECTION 记忆 / Presence 季节漂移）→ IntelligenceContainer → AppContainer；
- **QaAskEcho 从「QA 重写产品」变为引擎的薄适配器——QA 测的就是产品**（QA mirror 审计第三项提前闭环）。

## 1. 覆盖率

引擎覆盖 **24/26**；2 条诚实交回 AI 路径：q042/q043（用户确认偏好核对——下一轮补 USER_CONFIRMED/PREFERENCE 输入）。

## 2. 审查发现并修复的缺陷（真实回答驱动，非想象指标）

| # | 缺陷 | 层 | 修复 |
|---|---|---|---|
| 1 | q005「晚上结束时间有什么趋势」却回答活跃起点（证据与问题错位） | A | 新增 `END_DRIFT` 族：结束时间两半窗口中位数对比，证据只说结束时间 |
| 2 | q015「屏幕时间差多少」在小差异时回答「很接近」不给数字（问题要数字） | C | 新增 `SCREEN_MONTH_DELTA` 族：无论差多少都给出两月数字 |
| 3 | q007/q009/q010 周总结类问题完全未覆盖（「为什么最近这么碎」是最高价值问题之一） | — | 新增 `WEEK_SUMMARY` 族：本周 vs 上周起点/屏幕/零散日三线 |
| 4 | q037「今天为什么这么碎」拿最强维度（SCREEN_TIMING LATER）顶替碎片化问题 | B | 新增 `WHY_TODAY_FRAGMENTED`：只允许 DAY_STRUCTURE 作答；今天不碎就诚实说「不算特别零散」 |
| 5 | travel 文案「最近一次出差 已经结束了」双空格 | D | 文案修复 |
| 6 | 分钟格式化截断 vs 四舍五入（09:15/09:16 漂移） | A | `roundToInt` 对齐 |
| 7 | q015「多 0 分钟」生硬 | D | 「几乎一样」分档 |
| 8 | 出差问题只认「出差」上下文（E 用户问出差却答「没有找到」） | C | 上下文窗口泛化到任意 label（项目冲刺等用户自述期） |

## 3. 抽查评分（修复后）

| 问题 | A | B | C | D | 备注 |
|---|---|---|---|---|---|
| q001 最近越来越晚 | ✅ | ✅ | ✅ | ✅ | 数字证据 08:53→08:54，诚实 |
| q005 结束时间趋势 | ✅ | ✅ | ✅ | ✅ | 修复后证据只说结束时间 |
| q007 这周为什么碎 | ✅ | ✅ | ✅ | ✅ | 零散日计数三线 |
| q015 屏幕差多少 | ✅ | ✅ | ✅ | ✅ | 总是给数字 |
| q019 周末区别 | ✅ | ✅ | ✅ | ✅ | 起床+屏幕两线，克制 |
| q025 最像今天 | ✅ | ✅ | ✅ | ✅ | z 距离排序 + 日期 |
| q029 稳定吗 | ✅ | ✅ | ✅ | ✅ | 漂移提示只在 >0.3 出现（不矛盾） |
| q033 今天为什么不一样 | ✅ | ✅ | ✅ | ✅ | 命名最强维度 + z |
| q037 今天为什么碎 | ✅ | ✅ | ✅ | ✅ | 不碎就诚实 |
| q038 出差影响 | ✅ | ✅ | ✅ | ✅ | 窗口内/结束/无记录三态 |
| q040 纠正了什么 | ✅ | ✅ | ✅ | ✅ | 回放纠正内容（生产接 CORRECTION 记忆） |
| q006/q014 半年/月 | ✅ | ✅ | ⚠️ | ✅ | q014「更晚睡」答起点+屏幕（未答入睡时刻——本地无该维度，诚实边界） |

## 4. 已知边界（下一轮）

1. q042/q043（用户确认偏好核对）未覆盖——需要 USER_CONFIRMED/PREFERENCE 记忆输入；
2. q014「晚睡」只有结束时间代理，无「入睡时刻」维度（本地数据边界，不伪造）；
3. 引擎问法为严格表（canonical + Core Set 变体）；自由措辞仍走 AI 路径——这是有意的克制
   （Part 23：不做 keyword patch），下一轮观察真实用户措辞再决定是否扩展；
4. 周/月/半年回答不含 Life Season 上下文（后续与 Journey 解释链对齐时补）。

## 5. R4 增补（BATCH 2 收口）

- **覆盖率 26/26**：新增 CONFIRMED_RECALL（q043，回放 USER_CONFIRMED 记忆）与
  CONFIRMED_WEEKEND_CHECK（q042，用户自述 vs 周末观察对拍——观察一致时报数字，
  不一致时「按你的说法继续看」，被动证据不投票击败用户 §28）。
- **Grounding overreach 门禁**（§24 落地）：PersonalAnswerReviewHarnessTest 对全部 26×3 回答
  强制 `containsBlockedVocabulary` + 监视语言双门禁——确定性层的
  「False personal interpretation rate」从此有回归锚点。
- Production 接线：USER_CONFIRMED 记忆 → DeterministicPersonalAnswerProvider → 引擎输入。
