# ERA 31 Round 6 报告（BATCH 4 收口 / BATCH 5 起步）

> 日期：2026-08-15。

## 1. BATCH 4 §32/§33 — Significant Change / Landmark 复核结论

- **§40/§43 领域层已达标**（ERA 24 已建）：显著变化需同时满足视觉距离/方向性偏差阈值 +
  窗口置信度；上下文时期标注为特殊阶段；故事情感词守卫（storyRestraintCheck）。无需改动。
- **发现真实缺口：`buildLandmarks` 在生产零消费**——时间地标（基线成熟/明显变化/上下文时期/
  用户确认阶段）算出来了但 UI 从不展示。Day 90 验收「Journey 有几段值得看的时间」缺展示面。

**修复（本轮）**：
- `JourneyMemoryState`/`JourneyUiState` 增 `landmarks`（仅 YEAR 尺度装配，与年视图同生命周期，
  避免每尺度重算）；
- `YearViewSection` 底部渲染「时间地标」安静列表（视觉四季仍是第一层，地标是第二层锚点）；
- 回归：`JourneyUiStateAssemblyTest.yearScaleCarriesTimeLandmarksNotOtherScales`。

## 2. BATCH 4 §34 — Year View 结论

`journeyYearStory`（年故事文案）在生产零消费——**有意保持**：YearViewSection 已按
「Visual > Narrative」渲染四季视觉帧 + 转变/上下文文字行，年故事文案与其重复；
函数保留供 QA 评估叙事质量（Part 56：不增加更多 AI summary 展示面）。

## 3. BATCH 5 §50/§51 — Time-to-ECHO 指标建立

`qa/reports/TIME_TO_ECHO.md`：
- 六段分解（人因 3 段 + 机器 3 段）；机器段合计 ≈2.21s（Awakening 2200ms + 装配 <10ms + 首帧 <1ms）；
- 审计确认 §49 授权完成不停留（AwakeningScreen 自动过渡，无 Continue/DONE 页）；
- 审计确认 §51 首帧不等待 Provider/Backend/Memory/Journey（combine 全本地 StateFlow）；
- 回归锚点：`AWAKENING_DURATION_MS` internal 化 + smoke 断言 ≤3s。

## 4. 实测

见最终计数（预期 +1 装配测试 +1 苏醒预算断言）。
