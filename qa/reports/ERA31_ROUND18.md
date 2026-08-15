# ERA 31 Round 18 报告（§22 Correction Reuse：纠正 → 上下文推理桥梁）

> 日期：2026-08-15。

## 走查发现

走查 §22 关键验收场景（Personal AI 的核心验收：用户纠正后，未来理解明显改变）：

- ECHO：「最近你的节奏晚了。」→ 用户：「不太像，我最近在出差。」（原因 chip：旅行）
- 未来问：「我说过最近在出差，这有没有影响？」

生产链路审计结果：纠正只写 **CORRECTION 记忆**（confidence=1，importance=80），而确定性
引擎里 CORRECTION 只被 `correctionsRecall`（「我纠正过你的那次…」）机械回放——**上下文感知
回答族（TRAVEL_CONTEXT）只读 CONTEXT 记忆，完全看不到这条纠正**。也就是说：
「不太像 + 旅行」之后，「我说过最近在出差，这有没有影响？」仍然回答「我这里没有找到
出差相关的上下文」。§22 要求的「把 context 自然融入 reasoning」在确定性路径里是断的。

附带发现：上下文标签取 `content.take(24)`——结构化内容「特殊时期：旅行（出差）@2026-08-14」
会被截成「特殊时期：旅行（出差」混进回答，破坏 ECHO 语气。

## 修复

1. **纠正 → 上下文桥梁（EchoCorrectionService）**：上下文类纠正原因
   （工作/旅行/假期/身体不舒服/特殊事件）的「不太像」除写 CORRECTION 外，同时写入
   CONTEXT 记忆（同格式 `特殊时期：<kind>@<date>`，provenance `context-from-correction:v1`，
   confidence=1）→ 未来 TRAVEL_CONTEXT 族自然融入该上下文。非上下文原因（只是很专注/不想说/其他）
   不建上下文；3 天内同 kind 去重（防重复反馈堆叠，新周期纠正仍会新建）。
   两个入口（对话反馈 + Today 一句话「不太像」）行为一致。
2. **上下文标签人类化（DeterministicPersonalAnswerProvider）**：结构化 CONTEXT 记忆用
   `contextExceptionInfo` 的 kind 做标签（「旅行」），非结构化内容保留原 take(24) 兜底。

## 回归

- `EchoCorrectionServiceTest` +5：上下文原因建 CONTEXT（含日期/最高置信）；非上下文原因不建；
  画像纠正入口同样桥接；3 天同 kind 去重 + 新周期新建；「像我」永不建上下文。
- 新增 `DeterministicPersonalAnswerProviderTest`（Robolectric + 真实 Room + 真实画像数据源）：
  纠正桥写入的 CONTEXT 记忆在「我说过最近在出差，这有没有影响？」回答中以「旅行」标签
  自然呈现（不再出现「特殊时期」截断串）。

## 实测

- Android 1018 全绿（+6：app 861 / intelligence 22 / presence 24 / qa 111）+ detekt + `:app:lintDebug` PASS。
