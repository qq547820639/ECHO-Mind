# Journey Emotional Value Eval（ERA 24 / Batch 4）

> Journey 技术架构已存在；本批次回答「用户愿意回来看的原因是什么」——
> 时间的形状，不是图表；1-3 个真变化，不是周报。

## 1. Journey 不只是数据浏览器（§38）

新增 `JourneyStory` 层：期间故事 / 年故事 / 时间地标三件套全部由「时间的形状」
（河段 + 显著变化 + 地标）生成；fixture 驱动验证冲刺期/出差期/稳定期的真实形状。

## 2. Period Story（§39）

`buildPeriodStory`：滑动窗口检测 → 合并去重 → **最多 1-3 个真正重要的变化**；
无变化 → 「这段时间的节奏很平稳，没有特别大的变化。」（一句，不硬凑）。
锚定：E 冲刺期 = 1 个变化（「2026-03-03 前后…有明显变化（你提到过的项目冲刺期间）」）；
A 半年 = 平稳无变化；7 个 profile 全部 ≤3 变化。

## 3. Significant Change Algorithm（§40）

`detectSignificantChanges` 综合：
- **视觉距离**（12 维聚合参数距离）+
- **方向性偏差**（(维度,取值) 占比翻转 ≥0.4 且一侧主导 ≥0.5——捕捉「屏幕时间增加 0.07→0.86」这类纯视觉距离看不到的行为级变化；早期基线噪声的无序标签被排除）+
- **持续时间**（窗口 ≥14 天，单日异常不构成变化）+
- **上下文**（命中上下文时期 → 标注「特殊时期」，不是节奏异常）+
- **置信度**（窗口有效画像天数占比）。
同一变化跨滑动窗口 → 合并（间隔 ≤2 窗口且变化面重叠）。

## 4. Memory Landmarks（§41）

`buildLandmarks`：第一次基线成熟 / 节奏明显变化 / 持续特殊上下文 / 用户确认的重要阶段，
全部带日期与自然语言文本；fixture 锚定（A 基线成熟、D 出差时期、E 冲刺变化）。

## 5. Year View / Year Story（§42）

年视图 + `journeyYearStory`：一年中的几个主要阶段。
锚定：E 半年 → 「冬：平稳。 春：项目冲刺。 夏：节奏发生过 1 次明显变化。」
（不是 12 个月报告）；`detectMajorShifts` 同步升级为视觉距离+方向性偏差+合并。

## 6. Emotional Restraint（§43）

`storyRestraintCheck`：故事文案禁「艰难/痛苦/糟糕/熬过/低谷…」式自动情绪判断；
7 个 profile 全部故事 0 命中。行为变化只描述行为。

## 7. Visual Memory River 质量（修复）

**修复 DRIFT 误报**：旧 `isDriftStep` 只看单步无符号距离——稳定用户每周聚合的随机晃动
被误判成漂移（A fixture 实测 5 段仅 2 段 STABLE）。改为 `isDriftChain`：
连续两步都在漂移区间且 12 维 delta 点积 >0（方向一致）；A 恢复以 STABLE 为主。

## 8. Historical Reconstruction（fixture 驱动）

7 个 profile × Day 90：canonical 编解码往返 → 历史重建帧与当日渲染帧完全一致
（ULTIMATE PRODUCT TEST 的历史回看锚点）。

## 9. 门禁

`:feature:qa` 84 测试全绿（Batch 1-4 累计）；app 844（跑批）；detekt 干净。
