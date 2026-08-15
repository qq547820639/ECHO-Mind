# ERA 31 Round 32 报告（关键验收契约锁：UI 建议同步 + 纠正复用全链）

> 日期：2026-08-15。

## 背景

R11–R31 的每项修复都有局部回归，但两条**跨表面关键验收**没有任何测试守护——
若未来某轮改 UI 文案或记忆管线，验收会无声断掉：

1. **§22 Correction Reuse 关键验收**（Personal AI 的关键验收）此前只有分件测试
   （service 桥 / provider 标签 / 引擎族），没有「纠正 → 未来回答」的 production
   全链锚点；
2. **UI 建议 ↔ 引擎同步**（R17 修复的「建议即诱饵」缺陷）没有契约锁——建议文案
   与引擎覆盖表是两个文件两套字符串。

## 新增验收锁（app/src/test）

1. **`CorrectionReuseAcceptanceTest`（Robolectric production 全链）**：
   真实 Room + 真实 MemoryRepository + 真实画像数据源 + 真实提供者——
   用户「不太像 + 旅行」→ 未来「我说过最近在出差，这有没有影响？」→ 回答
   自然融入「旅行」上下文 + 依据如实标注 CONTEXT_EXCEPTIONS；
2. **`AskEchoSuggestionContractTest`（纯 JVM）**：
   EchoConversationLayer 的两条建议文案必须离线确定性可答（thin-data 诚实回答
   也算答）；改 UI 文案必须同步本测试或入表。

## 实测

- Android 1028 全绿（+2）+ detekt + `:app:lintDebug` PASS。
