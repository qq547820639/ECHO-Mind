# ERA 31 Round 21 报告（What ECHO Knows 10 秒可读：人称与方向统一）

> 日期：2026-08-15。

## 走查发现

走查 Me → What ECHO Knows（gate #7：用户更清楚 ECHO 知道什么、不知道什么），
发现同一张卡片里**三种人称、两个方向**混杂：

1. **计数行是 ECHO 口吻**：「共 N 条：你确认过 X · 你告诉我的 Y · 我观察到 Z · 你的偏好 P …」
   （ECHO 对你说）。
2. **过滤 chip 是用户口吻且方向反了**：「我已确认的」「我的偏好」「**我告诉你的**」——
   CONTEXT 记忆是用户告诉 ECHO 的，chip 却写成「我告诉你的」（方向颠倒）；
3. **分组行又一套**：「我已确认的 / 我告诉你的 / 我的偏好」——与计数行用词不一致，
   用户无法把「共 12 条」里的数字对应到下面的行。

结果：信任页面上 ECHO 像在三个身份之间跳来跳去，10 秒读不懂（§10 安静可懂 / gate #7）。

## 修复

**单一词表 `MEMORY_TYPE_LABELS`（ECHO 口吻，chip 与分组行同源共用）**：

| 记忆类型 | 旧 chip | 旧分组行 | 新统一（chip = 分组行） |
|---|---|---|---|
| CONTEXT | 我告诉你的（方向反） | 我告诉你的 | **你告诉我的** |
| USER_CONFIRMED | 我已确认的 | 我已确认的 | **你确认过的** |
| PREFERENCE | 我的偏好 | 我的偏好 | **你的偏好** |
| CORRECTION | 你纠正过我的 | 你纠正过我的 | 你纠正过我的 ✓ |
| OBSERVATION | 观察到的事实 | 观察到的事实 | 观察到的事实 ✓ |
| DERIVED_PATTERN | 发现的模式 | 发现的模式 | 发现的模式 ✓ |
| TEMPORARY_INTERPRETATION | （无 chip） | 临时解释 | 临时解释（补 chip） |

与计数行同一人称（ECHO 对你说）；顺带补上 TEMPORARY_INTERPRETATION 的过滤 chip
（此前用户无法单独看「临时解释」）。「临时解释」与计数行「还在推测」的词面差异保留
（计数行是句内动词短语，分组行是名词标签，语义一致、无方向错误）。

## 回归

- `WhatEchoKnowsContentSmokeTest` +1：`memoryTypeLabelsSpeakInOneEchoVoice`——
  锁定「你告诉我的 / 你确认过的 / 你的偏好」chip 存在，且「我告诉你的 / 我已确认的 /
  我的偏好」在任何节点中不出现（人称与方向回归锚点）。

## 实测

- Android 1024 全绿（+1）+ detekt + `:app:lintDebug` PASS。
