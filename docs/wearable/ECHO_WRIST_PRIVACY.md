# ECHO WRIST PRIVACY — 手环隐私边界

> 冻结契约（ERA 33）。隐私失败 = hard FAIL（自动测试扫描所有 Band payload）。
> 决策顺序：User trust > Personal truth > Privacy > Safety > One-ECHO continuity。

## 1. NEVER SEND TO BAND

任何 Band payload 禁止包含：

| 类别 | 例子 |
|---|---|
| API key | `sk-…`、`AIza…`、任何 apiKey 字段 |
| provider credential | Bearer token / password / client_secret / authorization |
| provider base URL | baseUrl / endpoint 配置 |
| raw Memory content | 记忆原文 |
| raw Correction text | 用户纠错原文 |
| private Context | 私密上下文 |
| private Narrative | 私密叙事 |
| raw audio | 音频数据 |
| notification body | 通知内容 |
| precise location | 经纬度/定位 |
| Identity secret seed | 身份种子 |
| 有隐私含义的数据库 ID | 数据库主键等 |

手环只获得：**渲染和当前交互所必需的 PUBLIC_SAFE projection**。

## 2. 投影链（Phone → Band）

```
EchoPresenceState (+ optional public evidence)
        ↓ WearPresenceProjector（结构白名单：identity/moment/maturity/surface）
WearProjection
        ↓ WearablePrivacyProjector（headline 允许清单 + 敏感内容扫描）
WearPresenceEnvelope → WearMessageCodec → interconnect
```

- 结构保证：`WearPresenceEnvelope` 类型系统上不存在私密字段；
- 防御保证：`WearablePrivacyProjector.scanPayload` 对每个 payload 递归扫描
  敏感键名黑名单 + 敏感值模式，命中即 hard FAIL（测试对运行时所有出站 payload 执行）；
- 协议层（`WearMessageCodec` / `wear_protocol.js`）只序列化白名单字段。

## 3. VISUAL_FIRST / 克制的 publicHeadline

- 手环默认 **VISUAL_FIRST**：publicHeadline 为 null。
- 用户主动 WHY 时才生成一条 headline，且必须 ∈ 固定允许清单（硬编码）：
  - 学习期："初见。" / "我开始看到一些属于你的节奏。"
  - KNOWN+："今天开始得比通常晚一些。" / "今天很安静。" / "今天的节奏很活跃。" /
    "我开始认识通常的你了。"
- 禁止："你最近压力很大。" / "你连续几天异常。" / "你昨晚睡得很糟。" /
  用户私人 Correction / 任何 privateNarrative / affectiveState 派生文本。

## 4. 手环端缓存边界

- 手环只缓存：current PUBLIC_SAFE Presence（identity/moment/maturity/surface + headline）。
- 持久化仅此一份（`echo_presence_v1`）；断连/过期 → Identity 保留，Moment 降级。
- 手环端无：Memory / SelfModel / Journey / AI Provider / API Key / 长期个人数据库 /
  私密文本 / raw 传感器样本。

## 5. 原始生理数据（ANSWatch 与任何 raw 传感器）

- raw EDA / PPG / temperature / IMU：local only / short retention / no cloud /
  no Memory / no Journey raw storage。
- 长期保存只允许 policy 认可的 derived evidence。
- raw export：用户显式动作。
- 手环加速度计只发窗口 summary；raw samples 仅 Debug + 显式诊断同意。

## 6. 佩戴 / 睡眠 / 设备状态语义

- NOT_WORN → 腕上观察 quality = unavailable；**绝不**把"无运动"当"用户静止"
  （not worn → UNKNOWN）。
- sleep：官方能力 + 真机验证 + 用户显式授权三重门；只做 sleep vs stationary 区分 /
  日界质量 / 休息上下文；禁止 sleep → mental state / stress label。
- battery / charging / connection = DEVICE_HEALTH：禁止进入 Portrait / Memory / SelfModel。

## 7. 观察 ≠ Presence（写入边界）

- 5 秒 Wrist observation → Memory：绝对禁止（结构上不存在该路径，
  `WearablePolicy.wristObservationWritesMemory` 恒 false）。
- 腕上观察在手机只进入中立日志（内存有界、进程死亡即清），promotion 门
  需真机验证后才可能打开；打开后也只进 Moment-level neutral evidence，
  不直接改 Identity Genome / Baseline / Life Season。

## 8. 自动隐私测试（hard FAIL）

`WearablePrivacyProjectorTest`：API key / raw Memory / Correction / private Context /
provider config / notification / audio / location / identity seed 逐项命中违规；
`WearableRuntimeTest.everyOutboundPayload_isPublicSafe`：运行时所有出站 payload 扫描通过；
`ArchitectureBoundaryTest.wearableProtocolNeverSerializesPrivateState`：
协议/投影/编解码层源码不得引用 privateNarrative / affectiveState / EchoMemory。
