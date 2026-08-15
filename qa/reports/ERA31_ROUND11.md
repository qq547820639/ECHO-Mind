# ERA 31 Round 11 报告（v0.10.0 后第一轮：Headline 口吻 + What ECHO Knows 人类语言）

> 日期：2026-08-15。Release Closure 之后回到产品主线：让用户第一眼看到的文字更像 ECHO。

## 1. §11 Headline 不是数据摘要（真实产品修复）

审计发现：production Scene headline 优先使用标签列表（`HEADLINE_MAP`：偏晚/多屏/晚屏…），
用户第一眼看到「偏晚 · 多屏」——正是 §11 明令禁止的数据摘要；而同一文件里已经存在
自然句 summary（「今天开始活跃的时间比你最近的习惯稍晚。」），却被排在标签之后。

修复（`assembleEchoSceneUiState`）：**确定性 headline 优先自然句 summary**，
标签列表降为兜底（无 summary 时）。AI 层去重逻辑不变。

- 回归：`EchoSceneUiStateTest.naturalSummaryWinsOverTagListHeadline`（新）+
  既有三用例语义更新（deterministicHeadlineFallsBack 改为「无 summary → 标签兜底」语义）。
- 影响面：本地/后端画像的 summary 均已存在 → 全部用户第一眼文案升级；
  snapshot/黄金套件零漂移（QA 路径不经过此装配器）。

## 2. §57「What ECHO Knows」comprehension — 人类语言复核

- `echoKnowsLines`：「已持续 N 次观察」→「看到过 N 次」——计数保留（信任证据），
  措辞去工程腔；其余行（我观察到/你告诉过我/你说过/有些出入）复核通过，不改。
- 用户控制复核（R4 审计延续）：纠正/确认/忘记/编辑/固定/暂停/关闭某类数据/删除 Memory/
  删除本地数据全部在位（ADR-073 第 5 轮已锚定）——无需新改动。

## 3. 实测

- Android **1005 全绿**（app 853 / intelligence 20 / presence 21 / qa 111）+ detekt + lint PASS；
- 快照/视觉黄金套件零漂移。
