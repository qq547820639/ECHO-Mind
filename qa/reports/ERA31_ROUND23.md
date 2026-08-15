# ERA 31 Round 23 报告（Day-7 Why 可懂：开始活跃的「变化」直接说分钟数）

> 日期：2026-08-15。

## 走查发现

走查 Day-7 验收（用户第一次点「为什么？」时能理解 ECHO 为什么变化），发现最关键
事实行缺了「变化」：

- Why 层「开始活跃」证据行渲染为 `今天：10:14 / 平常：约 09:21`，**delta 留空**——
  用户必须自己心算两个时刻相差多少。§11 的 canonical 示例明确要求
  「10:14 通常 09:21 **+53 min**」这一层。
- 后端 `explain.py` 与 Android `LocalPortraitEngine` 镜像一致地缺这一列
  （契约一致但产品同缺）。

## 修复（镜像两端同改，跨语言契约保持）

1. **Android `LocalPortraitEngine.buildFacts`**：「开始活跃」的 deltaText 说成人话——
   `晚 N 分钟` / `早 N 分钟` / `和近期接近`（阈值与 RHYTHM 维度同源：
   `max(MIN_ABS_DELTA[active_start_minute]=10, |median|·MIN_REL_DELTA=5%)`）。
2. **后端 `explain.py build_facts`**：同款 delta_text（词面逐字一致，镜像不漂移）。

## 回归

- Android `LocalPortraitGoldenTest.startTimeDeltaIsHumanReadableMinutes`（+1）：
  基线圆周中位数 490（active_start 为 CIRCULAR_METRICS）→ 09:00 =「晚 50 分钟」、
  08:20 =「和近期接近」、07:30 =「早 40 分钟」三档锚点。
- 后端 `test_full_chain_later_rhythm`：11:00 vs 08:00 基线 → `delta_text == "晚 180 分钟"`。

## 实测

- Android 1025 全绿（+1）+ detekt + `:app:lintDebug` PASS；
- backend pytest 1077 passed + 1 skipped（既有用例内 +1 断言）全绿 + ruff 0 + mypy strict 0。
