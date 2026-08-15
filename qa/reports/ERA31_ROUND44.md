# ERA 31 Round 44 报告（源码事实扫描假阳性根因：KDoc 与 fun 粘连修复）

> 日期：2026-08-15。

## 走查发现

R43 重生成的 SOURCE_REALITY_REPORT 出现 1 个 unresolved 候选
（`deriveIdentityGenome`，引用自 presence）。追查根因发现是**真实格式缺陷**：
R31 编辑 EchoIdentity.kt 时把 KDoc 结束符与函数声明粘连成一行
（`*/fun deriveIdentityGenome(`）——Kotlin 合法编译，但源码事实扫描器的
声明正则无法索引该函数 → 假阳性 unresolved。

## 修复

- `EchoIdentity.kt`：`*/fun` → `*/\nfun`（KDoc 与声明分行）；
- `generate_source_reality.py` 重生成报告：**unresolved=0**（候选清单「无」）。

## 实测

- Android 1032 全绿 + detekt + `:app:lintDebug` PASS；
- SOURCE_REALITY_REPORT.md 漂移门重生成后零 unresolved。