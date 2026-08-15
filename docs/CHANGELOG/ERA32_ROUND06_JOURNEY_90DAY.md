# ERA 32 R06 — Journey 90 天测试落地（§41 + QA 快照镜像收口）

> 2026-08-15。§41 90-Day Test：真实用户 90 天后打开 Journey 必须至少能回答四问。
> 逐问核对 production 屏幕后补齐缺口；顺带把 QA 快照的 Journey「显著变化」从 z 分数镜像
> 改为 production 装配同源（QA 必须测 Production）。

## 1. §41 四问核对（修复前 → 修复后）

| 问题 | 修复前 production 现状 | 本轮动作 |
|---|---|---|
| Q1 这三个月大致经历了什么变化？ | 只有一句 journeyNaturalSummary（当前状态 vs 基线），无时间维度 | `buildPeriodStory`（§39 已存在但零消费方）接入 JourneyMemoryState/JourneyUiState/JourneyScreen——最多 3 个真正重要的变化，日期 + 方面 + 上下文标注 |
| Q2 什么时候变化最明显？ | 无（地标只在 YEAR 尺度） | 期间故事自带日期（「2026-06-19 前后，活跃起点前移方面有明显变化（你提到过的旅行期间）」） |
| Q3 有没有特殊阶段？ | 河流段标注（R27）+ YEAR 地标 | 已满足，不动（§39 克制：地标不提前到月尺度） |
| Q4 现在和一个月前哪里不一样？ | 无 | 新增 `compareNowWithMonthAgo`（§40 同一批组合原语：近 7 天 vs 30~23 天前；无变化诚实说「很接近」；不足 38 天不硬凑） |

## 2. 过程修复

- **数据不足不妄断「平稳」**：`buildPeriodStory` 增加 <28 天守卫（此前 7 天用户也会看到
  「这段时间的节奏很平稳」——false interpretation）。
- **工程键泄漏**（本轮新接线暴露的潜在缺陷）：`shiftedBehaviorAspects` 的主导值统计把
  SIMILAR 算进 mode → 未知组合落到 `dimensionShiftLabel` 的 else 分支泄漏原始维度键
  （快照里出现过裸 `MOVEMENT` 行）。修复：主导值只统计非 SIMILAR 取值 + 空值/未知组合跳过。
- **QA 快照镜像收口**：`QaProductSnapshot.journey` 的「最近 30 天显著变化」是 QA 自己的
  z 分数镜像近似——改为直接吃 `assembleJourneyMemoryState`（production），快照渲染
  期间故事 + 现在 vs 一个月前，与用户所见同源（`QA_MIRROR_AUDIT.md` 四项 mirror 全部闭环）。

## 3. 回归与证据

- `JourneyUiStateAssemblyTest` +2：90 天带变化 → 故事非空 + 月前对比行非空；平稳 90 天 →
  诚实「很接近」；10 天 → 故事为空（不妄断）。
- 7 profile 快照确定性重生成：Journey 段全部换成 production 行（如
  「2026-03-05 → 2026-04-05：湍流减弱」「屏幕时间增加 / 晚间屏幕更晚」），无工程键残留。
- 实测：Android 全模块单测 + detekt + lint 全绿。

## 4. 视觉审核声明

§42 人眼画廊评审本轮尝试以 read_image 执行，但当前模型不支持图像输入——维持外部待办
（`qa/visual-review/index.html` 人眼作答），STATUS Known Product Risks 不变。

## 5. 下一轮

Journey 90 天测试四问全部可答（Q3 由河流段标注 + YEAR 地标承担）。剩余批次均依赖外部
真实数据/真机（B/D/E/F）；在环境内可做的持续工作是定期复跑四层答案复核与 Delete Review。
