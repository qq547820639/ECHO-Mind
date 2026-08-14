# ECHO Mind v0.9.0 — v2 应用壳接管（ECHO / Journey / Me）发布说明

> 状态：`pilot-candidate` · 版本事实源：`scripts/version_source.json`
> 一句话：新的 Personal Intelligence 内核正式接管用户体验——一级导航收敛为三世界，运行时统一，AI 链路具备完整证据与纠错闭环。

## 本版本新增（Master Prompt v2 第一/二轮全部落地）

### 应用壳接管（v2 §3/§7）
- 一级导航从「今天/能力/旅程/我的」收敛为 **ECHO / Journey / Me**；
- 旧「能力」Tab 删除：基础行动在 ECHO Scene 内执行，订阅能力在 Scene「更多能力（订阅）」分区展开；`SkillListScreen`（无调用方）删除；
- 危机入口（紧急 FAB）常驻——IA 精简绝不隐藏安全资源。

### EchoRuntimeCoordinator（v2 §13）
- Sensing 六态（系统真实权限为唯一事实来源）/ Presence 组装 / Provider 轻量状态统一广播；
- ECHO Scene 顶部仅「非 ACTIVE」状态可见（STARTING / SYSTEM_PAUSED + 查看原因）——信任透明，非工程噪音。

### EchoSceneUiState + Progressive Why（v2 §16/§46/§47）
- 单一 UI 状态 + 纯函数装配器（一句话 fallback 链、L2 建议门槛、依据来源集中裁决）；
- Why 三层：一句话 → Scene 内 facts 展开 →「查看更多 → Journey」（z_score/coverage 不暴露在第一视觉）。

### Provider UX 收口（v2 §34/§35/§39）
- Me → AI Intelligence：Current provider / Model / Status 概览 + **测试连接**（认证 → 模型可用性 → 结构化输出 → 基础请求四步，逐步明细展示）+ 更换 Provider / 断开连接；
- 初次 AI 非阻塞提示：未配置 Provider 时轻量卡片「连接一个 AI…」[连接 AI] [以后再说]（dismiss 持久化，不阻塞主界面）。

### Context Compiler 真实数据检索（v2 §42）
- ContextPolicy 实例化：每个任务带 `timeWindowDays`（EvidencePolicy）与 `allowedMemoryTypes`（MemoryPolicy 白名单）；
- `EchoContextRetriever`：Task → 策略 → 端侧画像/基线/时间线/记忆**实际检索**（本地只读，失败降级空证据 + fallback 链）。

### Conversation Evidence grounding（v2 §51/§52）
- Ask ECHO 每条回答附「依据」：**参考了什么**（数据源清单）+ **没有使用**（麦克风/通知正文/精确位置）双清单；
- 每条回答可反馈：像我 / 不太像 + 8 个快速原因 → **Correction Memory**（用户自述最高优先，进入未来 Context Compiler）。

### Journey 全尺度（v2 §61）
- 天 / 周 / 月 / **季（90 天）** / **年（365 天）** 五个时间尺度；季/年按 30 天视觉聚合（backend 画像窗口上限 90 → 365，openapi 重新导出）。

## 验证（本环境实测）

| 项 | 结果 |
|---|---|
| Android `testDebugUnitTest` | **449 项全绿** |
| Android `assembleDebug` / `lintDebug` / `detekt` | PASS（0 errors / 新代码 0 告警） |
| backend `pytest -q` | **1071 收集全绿（1070 passed + 1 skipped）** |
| 版本一致性（v0.9.0 全链路 + Room v9 + openapi 365 天边界） | PASS |

## 已知边界（最终产品边界）

- 订阅单档 standard；基础行动免费（Scene 内行动集）。
- Ask ECHO 会话不持久化（Memory ≠ 聊天记录）。
- Android 手机是 v1 产品形态；多设备属下一产品周期。
- Affective Intelligence：契约（`AFFECTIVE_CONTRACT.md`）冻结，实现需契约 §8/§9/§10 前置（模型验证/隐私审查/错误恢复）完成后启用。
- 外部发布门（真机构建、渗透测试、合规审批、真实试点）完成前，不得标记生产上线。
