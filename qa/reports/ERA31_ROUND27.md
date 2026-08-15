# ERA 31 Round 27 报告（Journey 一条河流：河段故事并入主河流，第二条河流行退役）

> 日期：2026-08-15。

## 走查发现

Journey 长期存在**两条视觉记忆河流**（自 R19 起三次推迟的结构冗余）：

1. 顶部主河流（§30 第一视觉）：尺度选择（天/周/月/季/年）+ 点击选中 + 反馈标记；
2. 下方第二条「视觉记忆河流」行：全部历史的河段概览（平稳时期/节律漂移/… 44dp 缩略图）。

同一屏幕两条 ECHO 缩略图时间轴，视觉重复、故事割裂：交互在上一条，
故事种类在下一条（§10 信息压缩；§30 第一眼应该是「我的时间」一个故事，不是两个列表）。

## 修复（一条河流 = 时间线 + 故事）

1. 主河流的非 DAY 聚合格直接标注所属河段种类——新增纯函数
   `journeySegmentKindLabel(date, segments)`（feature:journey，日期区间包含查找）：
   标签从「第 3 周」变为「第 3 周 · 平稳时期」/「2026-06-01…2026-06-30 · 节律漂移」；
2. 删除第二条河流行 `VisualMemoryRiverRow`（约 40 行 UI）与其调用——Journey 屏
   序变为：一条河流（带故事标签）→ 叙事 → 阶段解释 → 年视图 → 那一天的回声。

## 回归

- `JourneyRiverTest.segmentKindLabelLocatesContainingSegment`（+1）：区间端点/区间内/
  区间外/空输入四档。
- 既有 JourneyScreenSmokeTest 全绿（无对第二条河流行的锚点）。

## 实测

- Android 1027 全绿（+1）+ detekt + `:app:lintDebug` PASS。
