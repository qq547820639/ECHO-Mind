# ERA 32 R08 — Old Me 深度审计（§32 StablePattern 生命周期）

> 2026-08-15。§32：三个月前的稳定规律已经变化后，ECHO 是否还把过去当现在。
> 审计对象：promotePatterns 生命周期 → EchoSelfModel → echoKnowsLines → What ECHO Knows 生产接线。

## 1. 审计结论（计算 + 接线 + 呈现三层）

| 层 | 现状 | 判定 |
|---|---|---|
| 生命周期计算 | CONFIRMED →（矛盾）WEAKENING → CONFLICTING →（陈旧+矛盾）OUTDATED；合并保留历史矛盾计数（不静默覆盖）；OUTDATED 遇新鲜确认证据可复活清零矛盾史；conflict 数/新鲜度/跨度/上下文折扣全部确定性 | ✅ §33/§34/§35 完整 |
| 生产接线 | `buildSelfModel` + `echoKnowsLines` 已在 WhatEchoKnowsSection 调用（此前审计曾误判为 QA-only，实为 grep 过滤漏检） | ✅ |
| 呈现诚实 | 有矛盾史的 OUTDATED → 「之前关于「X」的判断最近有些出入，我还在观察。」（R5 测试锚定，非现在时） | ✅ |

## 2. 发现并修复的真实缺陷（§32 的另一半）

**静默陈旧模式仍用现在时**：用户没有纠正过、只是行为悄悄变了——60+ 天无新观察的
CONFIRMED 模式（无矛盾信号）此前仍呈现「我观察到：X（看到过 N 次）」（现在时），
正是「把过去当现在」。修复：

- `echoKnowsLines` 对确认模式增加新鲜度阈值（60 天，与 `outdatedAfterDays` 同源）：
  陈旧 → 「**以前观察到**：X（看到过 N 次；最近没再看到）」；新鲜 → 保持「我观察到」。
- 无纠正矛盾也不伪装当前事实——ECHO 说「以前」，不是「现在」。

## 3. 回归

- `QaMemorySelfModelTest` +1：`staleConfirmedPatternWithoutCorrectionIsPresentedAsPast`
  （90 天陈旧无矛盾 → 「以前观察到…最近没再看到」，负向锁现在时）；
- 既有锚点全部保持（新鲜模式仍「我观察到」、OUTDATED「有些出入」、敏感纠正原文不出现在公开行）；
- 实测：QA / memory / app 三模块测试 + detekt + lint 全绿。

## 4. 下一轮

§32 两个分支（有矛盾陈旧 / 静默陈旧）都已用户可见地诚实呈现。环境内审计项持续收敛；
下一轮按 Delete Review + 答案复核复跑节奏执行，或按真实数据回流切换。
