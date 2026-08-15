# ERA 31 Round 19 报告（Journey 第一眼走查：免费用户叙事说人话 + 河流锚定真实数据）

> 日期：2026-08-15。

## 走查发现

以无 AI 免费用户走查 Journey（§30「看见自己的时间，不是阅读 AI 报告」/ gate #6），
发现两处真实缺陷：

1. **长期叙事是指标行**：无 AI 时 Journey 叙事 fallback = `portraitStabilitySummary`
   （「最接近：屏幕总量；变化较明显：作息」）——免费用户唯一的 Journey 叙事是一条
   指标摘要，读起来像数据报告，不像「我的时间」；指标行本应属于 Evidence Layer
   （那里确实也保留了它）。
2. **DAY 河流锚定设备时钟**：7 天视觉河流用 `LocalDate.now()` 取窗口——感知滞后
   （权限暂停/数据晚到）时，河流右端渲染「今天/昨天」的空占位圆圈，把「ECHO 没在看」
   的视觉噪音放在用户第一眼，而不是展示有记录的最后 7 天。

## 修复

1. **`journeyNaturalSummary`（feature:journey）**：与 `portraitStabilitySummary`
   同源统计（最接近 = SIMILAR 比例最高；变化较明显 = 非 SIMILAR 最多），但输出自然句：
   「过去 28 天里，你的作息最接近平常，变化较明显的是屏幕时段。」
   单一维度退化场景输出「过去 N 天里，你的节奏整体比较平稳。」（不再自相矛盾）。
   接入 `JourneyRepository.narrativeFor` 的确定性 fallback 与 JourneyScreen 的
   ifBlank 兜底；Evidence Layer 的指标行保持不变。
2. **DAY 河流锚定旅程实际最新一天**：`VisualMemoryRiver` 的 7 天窗口取
   `visualDays` 的最大日期（解析失败/无数据才回退 now）——有记录的最后 7 天
   才是「我的时间」，空占位不再占据第一视觉。

## 回归

- 新增 `JourneyNaturalSummaryTest` +4（自然句点维度 / 单维度平稳句 / 无数据诚实 /
  确定性）。
- `JourneyScreenSmokeTest.dayCellClickEmitsSelectDayForLatestRecordedDay`（原
  today 锚点语义反转锁定：点击的是旅程实际最新一天，而不是设备时钟的今天）。

## 实测

- Android 1022 全绿（+4）+ detekt + `:app:lintDebug` PASS。
