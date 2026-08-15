# ERA 31 Round 37 报告（纠正/确认回放说人话：内部记忆格式退役）

> 日期：2026-08-15。

## 走查发现

走查「我纠正过你的那次，后来你改了吗？」与「你还记得我确认过的那些事情吗？」
两个回放回答，发现**记忆内部格式整串泄漏**到用户回答：

- 纠正回放：「你纠正过我：**画像反馈：不太像（原因：旅行）（原判断：是的，最近明显更晚。）**。…」
- 确认回放：「记得。你确认过：**问答反馈：像我（问：最近我是不是越来越晚？）**。…」

「画像反馈：不太像（原因：…）」是 MemoryRepository 的内部内容格式，
不是给用户读的话（§22：纠正复用要自然融入 reasoning，不是机械回放内部字段）。

## 修复（DeterministicPersonalAnswerProvider，engine 不动）

- `humanizeCorrection`：「画像反馈：不太像（原因：旅行）（原判断：…）」→
  「旅行——当时我说的是「是的，最近明显更晚。」」；
- `humanizeConfirmed`：「问答反馈：像我（问：…）」→「问「…」的回答」；
- 未知格式兜底 take(40)（旧记忆/外源格式不回退到整串泄漏）。

## 回归

- `DeterministicPersonalAnswerProviderTest.correctionRecallSpeaksHumanNotMemoryFormat`
  （+1）：含「旅行」+「当时我说的是」，且「画像反馈」内部格式不出现。

## 实测

- Android 1032 全绿（+1）+ detekt + `:app:lintDebug` PASS。