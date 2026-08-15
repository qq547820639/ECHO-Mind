# ERA 31 Round 28 报告（Scene 早期状态去仪表化：进度条与覆盖率条退役）

> 日期：2026-08-15。

## 走查发现

对 Scene 全部画像九态做仪表盘审计（R12 走查覆盖 READY 态，早期态漏检），发现
WARMING_UP / EARLY_BASELINE / LOW_CONFIDENCE 三态直接违反 §9/§13：

1. **BaselineProgress**：「已积累 3/7 天，基线即将成型」+ LinearProgressIndicator
   进度条——§13 明令「不要等级 UI / 进度条」；
2. **CoverageRow**：「今日已学习 42%」+ 覆盖率进度条——纯工程指标（coverage_score）
   变成用户第一屏的仪表盘（§9 第一视觉永远是 ECHO，不是 Metrics）。

v0.7.4 的「它在记录」获得感其实由**当天事实句**承担（「今天累计屏幕互动 126 分钟」）
——进度条与覆盖率是叠加的工程冗余，不是产品需要。

## 修复

1. EchoSceneScreen：移除两处状态分支中的 BaselineProgress + CoverageRow 调用；
2. 删除 `BaselineProgress` / `CoverageRow` 两个 composable 及 6 个废弃 import；
3. core:model 的 `baselineProgressText` / `todayCoveragePercent` 纯函数与单测保留
   （规格锚点，零 UI 消费者，删除与否属后续清理决策，本轮不做）。

## 回归

- `EchoSceneContentSmokeTest`：WARMING_UP 态改为锁「事实句在、进度条/覆盖率不在」；
  EARLY_BASELINE 同样负向锁定。
- `EchoPortraitStatesSmokeTest`：随组件删除移除 3 个用例（BaselineProgress ×1 +
  CoverageRow ×2）。

## 实测

- Android 1024 全绿（-3：删除组件的旧锚点，行为锁转移至 Scene 负向断言）+
  detekt + `:app:lintDebug` PASS。
