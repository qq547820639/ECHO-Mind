# ECHO Mind v0.8.0 — Personal Ambient Intelligence 发布说明

> 状态：`pilot-candidate` · 版本事实源：`scripts/version_source.json`
> 一句话：ECHO Mind 从「被动感知 + Today/Trend 页面」演进为**生活在用户手机中的 Personal Ambient Intelligence**。

## 本版本新增（ERA 1-9 全量落地）

### ERA 1 — Constitution & Trust
- 产品宪法（`docs/product/ECHO_PRODUCT_CONSTITUTION.md`）与个人智能契约（Observed/Interpreted/Felt 三层真相 + Confidence 一等公民 + Privacy Budget）。
- Onboarding 重构：三步 + **ECHO Awakening**（无 DONE/「进入应用」；最后授权后自动苏醒进主页）。
- 统一 **SensingRuntimeStatus 六态**（NOT_AUTHORIZED/STARTING/ACTIVE/DEGRADED/SYSTEM_PAUSED/USER_PAUSED）：「关闭」只表示用户行为；系统故障绝不伪装成用户选择。
- Day-0 **SEED ECHO**（「初见」+ 已观察 N 分钟）；首页工程噪音清零；常驻通知「ECHO · 正在了解今天」。
- 感知自愈看门狗（15min Worker + 纯函数决策 + 服务 fail-closed 门控）。

### ERA 2 — ECHO Scene
- AmbientEngine：机器内部七态（QUIET/ACTIVE/DENSE/SLOW/LATE/TRANSITION/UNKNOWN）+ 中性状态向量（圆周 z，跨午夜正确）。
- Generative Visual Engine：12 维连续视觉参数 + 昼夜亮度曲线 + **确定性帧模型**（同一 identity/day/state/time 可复现）。
- Today 顶部生命场（Portrait-first）；`EchoPresenceState` + `EchoStateStore` 单一状态（分钟级更新、帧级渲染，两个时间尺度）。

### ERA 3 — ECHO Presence
- **动态壁纸**（WallpaperService）：只消费状态快照、不可见即停止渲染（0 CPU）、触摸涟漪；仅渲染视觉无文字 → 锁屏 Public Safe 由构造保证。
- **充电屏保**（DreamService）：时间 + 日期 + ECHO 字标（白名单内容）；脱离窗口自动停止渲染。
- Presence 控制中心（壁纸/屏保入口、动态程度、夜间模式、减少动画、应用内建议）+ 后台 Presence 刷新 Worker（15min 快照新鲜度）。

### ERA 4 — BYOM Intelligence
- `EchoReasoningProvider` 抽象 + OpenAI-compatible 实现（官方/第三方/自建网关/局域网端点）。
- ProviderStatus 十态状态机 + HTTP 映射纯函数；API Key 经 Android Keystore 加密存独立文件（不上传/不进备份/不回显）。
- Me → AI Intelligence 配置 UI（Base URL / 模型 / API Key masked、验证并连接、断开连接、数据发送提示）。

### ERA 5 — Context Compiler
- 十类 typed Reasoning Task × ContextPolicy（隐私硬边界：原始通知/音频/麦克风特征永不进入任何任务）。
- `EchoContextCompiler`：剔除禁止数据 → Privacy Budget 截断 → 最小上下文（Relevant context ≠ Maximum context）。
- Structured Output 校验/修复 + 词表门禁（画像 BLOCK 词对 AI 同样生效；监控语言；≤200 字）。
- **Fallback 链**：AI 叙事 → 确定性叙事 → 观察事实（AI 失败不破坏 ECHO）。

### ERA 6 — EchoSelfModel & Memory
- EchoMemory：七类记忆 × 四档保留期（7d/30d/365d/固定）+ 衰减/强化/过期/编辑/删除。
- Room v9 `echo_memories`（迁移 8→9 纯建表）；画像反馈「不太像」→ 8 个快速原因 → **Correction Memory**。
- Me → What ECHO Knows：分组展示 + 编辑/确认/忘记（用户四权齐全）。

### ERA 7 — Conversation
- Today「问 ECHO」对话层（从 Scene 展开；回答基于个人时间上下文 + 依据展示；会话仅存内存——Memory ≠ 聊天记录）。

### ERA 8 — Journey / Visual Memory River
- Trend 页重构为 **Journey（旅程 · 我的时间）**：天/周/月时间尺度。
- 视觉记忆河流：每天一个确定性视觉单元（CANONICAL_SNAPSHOT，不 AI 生图）；周/月视觉聚合；长期叙事（AI → 确定性综述 fallback）；图表降级为「查看依据」证据层。

### ERA 9 — Actions
- Intervention Policy L0-L3 分级（低置信/未知 → 仅视觉；L2 需 opt-in；L3 主动通知需 opt-in + 高置信 + ≥7 天间隔）。
- Scene 内行动：1 分钟呼吸（ECHO 生命场 8s 吸/呼周期）、短暂离开屏幕、「什么也不做」永远是合法选项；订阅能力分区展示。

### ERA 10 门槛
- `docs/intelligence/AFFECTIVE_CONTRACT.md`：可选情绪智能的独立版本化契约（契约满足前 `affectiveState` 恒 null）。

## 架构决策
- `docs/architecture/ADRS.md`：ADR-001~020（模型≠ECHO / BYOM 设备端 secret / 单一 EchoPresenceState / 共享渲染器 / Context Compiler 唯一通道 / Memory 生命周期 / Journey 确定性快照 / 干预分级 / 看门狗自愈 / 最终产品边界）。

## 验证（本环境实测）

| 项 | 结果 |
|---|---|
| Android `testDebugUnitTest` | **435 项全绿** |
| Android `assembleDebug` | PASS |
| Android `lintDebug` | PASS（0 errors） |
| Android `detekt` | PASS（新代码 0 告警） |
| backend `pytest -q` | **1071 项全绿（1070 passed + 1 skipped）** |
| 版本一致性（version_source/README/pyproject/versionName/manifest） | PASS |

## 已知边界（最终产品边界，ADR-020 已裁决）

- 订阅保持单档 standard（云端同步/长周期分析/专业支持/能力练习）；基础行动免费已由 Scene 内行动集承担。
- Ask ECHO 会话不持久化（Memory ≠ 聊天记录）。
- Android 手机是 v1 产品形态；多设备（Watch/Tablet/Desktop）属下一产品周期。
- Doze 下传感器仍可能被系统暂停：看门狗尽力自愈 + SYSTEM_PAUSED 诚实呈现（不申请电池豁免）。
- 外部发布门（真机构建、渗透测试、合规审批、真实试点）未完成前不得标记生产上线。
