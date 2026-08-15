# ERA 31 Round 40 报告（QA 镜像产品快照同口径：z 分数不再泄漏）

> 日期：2026-08-15。

## 走查发现

完成 Me 全部分面走查（Subscription/Support 文案干净，§40 免费核心承诺保持）后，
做全仓缺陷类别终扫（英文标签泄漏 / 原始键 / z 分数 / AI 催促），发现最后一处：

**QA 产品快照的 Journey 证据行仍泄漏 z 分数**——`qa/reports/snapshots/PROFILE_*.md`
（给人审看的「产品说的话」预览）里出现「2026-01-06：活动量增加（z=16.9）」。
R25 已把生产引擎的 whyToday 证据人话化，但 QA mirror（QaProductSnapshot）的
变化日证据仍用旧格式——人审读到的产品预览与真实产品口径不一致。

## 修复

`QaProductSnapshot` 变化日证据行去掉「（z=…）」：
「2026-01-06：活动量增加（z=16.9）」→「2026-01-06：活动量增加」——
镜像与 production 同口径（R25）。

## 实测

- Android 1032 全绿 + detekt + `:app:lintDebug` PASS；
- QA 快照 7 profile 确定性重生成（z 分数行清理，生成器自有）。