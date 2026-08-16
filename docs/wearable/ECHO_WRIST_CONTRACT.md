# ECHO WRIST CONTRACT — ERA 33 · ECHO Wrist / Second Body

> 产品定义冻结。文档权威顺序：产品宪法 > 冻结契约 > STATUS > 架构文档 > CHANGELOG。
> 本文件是 wearable 面的唯一契约源；实现细节见各模块源码。

## 1. 产品宪法（本次永久冻结）

```
# PHONE = BRAIN
# WRIST = BODY + PRESENCE SURFACE
# THERE IS ONLY ONE ECHO
```

- 手机拥有：Observation Ground Truth / Personal Baseline / Portrait / EchoSelfModel /
  Memory / Corrections / Context / Personal Intelligence / Journey / Identity Source of Truth /
  Actions Runtime。
- 手环拥有：PUBLIC_SAFE Presence projection / surface-specific visual state /
  temporary Presence cache / explicit wrist interaction / vendor device capability /
  limited wrist observations / low-disturbance haptics。
- 手环不得拥有：Memory / SelfModel / Journey / AI Provider / API Key / Identity Genome /
  长期个人数据库 / 私有 Correction 文本 / 私有 Context / 私有 Narrative。

手环不是 "ChatGPT on Wrist"、不是 Stress Monitor、不是 Mood Tracker、不是 Dashboard。
它是 **SAME ECHO / SECOND BODY**。North Star 不是"ECHO 支持小米手环了"，而是"ECHO 有身体了"。

## 2. 架构冻结例外

- 当前 11 module 体系冻结；本项目只新增**一个**产品边界 module：`:feature:wearable`
  （`android/feature/wearable/`），然后重新冻结。
- `:feature:wearable` 只依赖 `:core:model` + `:core:ports`。
- 禁止依赖：`:app` / Room / MemoryRepository / JourneyRepository / AiProviderManager /
  Android Xiaomi SDK 实现。
- vendor adapter 属于 `:app` adapter 层（`app/.../wearable/`，XiaomiWearVendorBoundary）。
- 不因 wearable 再拆 wear-core / wear-protocol / wear-xiaomi / wear-ans / wear-ui 等 module。

## 3. 能力事实源

- `docs/wearable/XIAOMI_BAND10_CAPABILITY_MATRIX.md`（官方文档核实，2026-08-16）是唯一能力事实源。
- 状态：`SUPPORTED_PUBLIC / SUPPORTED_REQUIRES_VENDOR_SDK / UNSUPPORTED_PUBLIC / UNKNOWN /
  BLOCKED_EXTERNAL_VERIFICATION`；能力测试以 Matrix 为源，绝不猜。
- 关键事实：Band 10 = 1.72" AMOLED **212×520**；accelerometer/pressure 前台订阅支持；
  vibrate 仅 short/long（无 pattern）；interconnect 需 Android 同包名同签名；
  传感器无后台运行（官方后台接口仅 audio/request/geolocation）；Android 侧官方穿戴 SDK v1.4
  存在（连接/电量/充电/佩戴/睡眠 + launchWearApp + sendMessage），SDK 本体未获得。

## 4. Wear Protocol v1

- Envelope：WearPresenceEnvelope / WearObservationEnvelope / WearActionEnvelope /
  WearAckEnvelope / WearCapabilityEnvelope。
- 全部含：schemaVersion(=1) / messageId / generatedAt / source（PHONE|XIAOMI_BAND）；
  Presence 额外含 revision + expiresAt。
- 要求：small / versioned / forward compatible（未知字段忽略）/
  idempotent / out-of-order safe / duplicate safe（messageId 去重 + revision 单调）。
- 序列化：org.json 文本（手机 Kotlin `WearMessageCodec` 与手环 JS `wear_protocol.js` 逐字段一致）。
- **Presence 禁止直接序列化整个 EchoPresenceState**：只发
  identity{topology,symmetry,orbit,motion,texture,colorFamily,accent} +
  moment{flow,coherence,density,turbulence,brightness} + maturity +
  surface{motionLevel,lowPower,reducedMotion,motionSummaryEnabled,hapticsEnabled} +
  optional{publicHeadline,availableActions}。

### 4.1 Revision / TTL

- revision 手机唯一事实源，单调增长（`WearRevisionStore` 持久化，进程死亡继续单调）。
- 手环：revision < cached → ignore；== cached → **只更新显示字段**（headline/actions/surface）
  并续期（WHY 语义）；> cached → 全量更新。
- expiresAt = generatedAt + 15min；断连且过期 → 保留 Identity，Moment 缓慢降级
  QUIET / LOW_CERTAINTY；不显示红色 ERROR；不随机生成新的 ECHO；不主动震动提醒断连。
- 时钟偏移容差 ±1min。

### 4.2 Phone → Band 更新触发（禁止按帧同步）

仅：Band app opened / initial connect / reconnect / Presence 语义 revision 改变 /
Action changed / privacy state changed / explicit refresh；Moment 更新 rate limited
（请求类触发最小间隔 10s）。

### 4.3 Band → Phone（v1 白名单，无 generic command bus）

REQUEST_CURRENT_PRESENCE / REQUEST_WHY / START_BREATHING / STOP_ACTION / START_PAUSE / ACK /
WRIST_OBSERVATION。

### 4.4 Production wiring（Application scoped，唯一启动点）

- `EchoMindApplication.container → AppContainer 组合 → WearableContainer.start() →
  WearableRuntime.start(applicationScope)`；禁止 Activity / Screen / Me UI 负责启动。
- `start()` 幂等；进程存活期间 runtime 存活；进程死亡后下次组合自动恢复
  （revision 经 WearRevisionStore 持久化，不因进程死亡断裂）。
- 入站消息自动接线：`platform.inboundMessages` 由 runtime `start()` 自动 collect
  （codec → dedupe → schema validation → dispatch）；生产代码不手工调用
  `onMessageFromBand()`（保留为 internal/test entry）。
- 出站：connection → 当前 Presence 投影推送；Presence 语义 revision → rate-limited 推送；
  REQUEST_CURRENT_PRESENCE → 立即最新投影；REQUEST_WHY → PUBLIC_SAFE 响应；
  Action state 变化 → Wrist surface 更新。禁止动画帧同步。

## 5. SAME ECHO 视觉

- `WearPresenceProjector`：EchoPresenceState → 低维 WearProjection（纯函数，双端一致）。
- 共享：topology family / symmetry / orbit / motion personality / texture family /
  color family / maturity。
- surface-specific difference 只包括：geometry simplification / animation budget /
  privacy / screen shape（212×520 狭长屏）/ power budget。
- Vela renderer：few shapes / opacity / scale / translation / simple orbit /
  simple gradients / slow organic movement；像生命体，不是科技 HUD 或 music visualizer。
- Idle 明显降低更新（2s），transition 更高刷新；battery first。
- **Vela 运行时渲染约束（官方模拟器 R4 实测，preflight 锁定，违反即整页不渲染）**：
  1. 页面响应式数据必须 `private:`（`data:` 无响应式绑定）；
  2. app 生命周期钩子是 `onCreate`（app 级 `onInit` 静默跳过 → 应用不显示）；
  3. app 上下文**无模块 require**（模块级 require → 全部 app 钩子 call failed → 应用不显示；
     业务由 entry 页负责）；
  4. div 上的绑定 style 属性（多属性/整串绑定）整页不渲染 → 动态视觉用
     **class 绑定 + CSS keyframes**（官方 stack 容器 + 官方动画形态）；
  5. 路由用官方 `import router from '@system.router'`；
  6. 页面布局 `src/<page>/<component>.ux`；i18n `zh-CN.json`；
  7. 数值累加器禁止直接赋回 `.toFixed()` 结果（字符串污染 → 每 tick TypeError）。
- 视觉 Fixtures（确定性测试）：SEED / DISCOVERING / KNOWN / MATURE / QUIET / ACTIVE /
  LOW_CONFIDENCE / DISCONNECTED / BREATHING。
- Vela simulator/toolchain：已用官方模拟器（`xiaomi_band_10` skin + vela-miwear-watch-5.0）
  完成安装/运行/渲染验证（`ECHO_WRIST_REAL_DEVICE_REPORT.md`）；
  **外部 simulator gate** 剩余：真机（`BLOCKED_EXTERNAL_BAND10_DEVICE`）上的
  人眼对照（topology / motion / texture / structure / identity continuity）。
- 手环性能预算：Vela 源码 ~112K、零第三方依赖、动画走 CSS keyframes（无 JS 高频 timer）、
  DOM 元素 ≤ 24（V3 §73 修订：organism = 2–4 loops + 1 hollow core + 8–12 粒子槽 + 文本；
  旧预算 ≤15 对应 3 元素 organism，已被 V3 same-ECHO 视觉规格取代）；禁止重量级 JS 库 /
  高频 timer / 大规模对象分配。

## 6. 动作所有权

Band UI → WearActionEnvelope → Phone → **EchoActionRuntime**（应用级单例）→
Action State → Band + Phone surfaces。Breathing 是同一个 Action，Pause 是同一个 Action。
手环不创建第二套 Action Engine。

## 7. 触觉宪法

- 默认 **# SILENT**。
- 绝对禁止：inferred stress → vibration / anomaly → vibration / AI suggestion → vibration /
  engagement → vibration。
- v1 只允许（显式）：tap confirmation / user-started breathing cadence /
  action completion confirmation；只使用官方确认存在的 Band10 能力 vibrate short|long；
  速率限制 ≥1.5s。
- **端到端开关**：`Me → WearablePrefs.hapticsEnabled → WearSurfaceParams.hapticsEnabled
  → envelope.surface → 手环 Presence 缓存 → 腕上 vibrateShort/vibrateLong 硬门`。
  hapticsEnabled=false（含默认）→ 手环端一切振动 no-op（Breathing/Stop/完成全静默）；
  不能只有 Android UI 看起来关了。降级（degraded）surface 继续携带该开关（不重置用户设置）。

## 8. 观察纪律

- 观察 ≠ Presence：Wear Observation → Observation Adapter / Evidence → approved fusion →
  Presence。禁止 Wear Frame 直接修改 EchoPresenceState。
- provenance 必须显式：PHONE / XIAOMI_BAND / ANSWATCH；wrist ≠ phone 语义，禁止简单相加。
- 加速度计只在 App 前台订阅；5–15s 窗口 summary（motionEnergy / movementClass /
  sampleCoverage / quality / window timestamps）；raw 仅 Debug + 显式诊断同意。
- pressure：v1 仅 CAPABILITY / DIAGNOSTIC。
- 佩戴（NOT_WORN）→ 腕上观察 quality = unavailable；不把"无运动"当"静止"（not worn → UNKNOWN）。
- 睡眠：官方能力 + 真机验证 + 用户显式授权三重门；只用于 sleep vs stationary 区分、
  日界质量、休息上下文；禁止 sleep → mental state / stress label。
- 设备状态严格分类：battery/charging/connection = DEVICE_HEALTH；wearing = OBSERVATION_QUALITY；
  sleep = OPTIONAL_NEUTRAL_CONTEXT。禁止 battery → Portrait、charging → Memory、
  connection → SelfModel。
- stock Band 持续能力来自 system/vendor companion state，不是 Vela JS 传感器守护（不 hack 保活）。
- First Promotion Policy：stock Band10 真机验证过的 Wrist Motion 才可作为
  Moment-level neutral evidence；不立即改 Identity Genome / Long-term Baseline / Life Season。

## 9. Memory / Journey 不变式

- **# ONE MEMORY**：手环不保存长期 Personal Memory；只缓存当前 PUBLIC_SAFE Presence。
- **# ONE JOURNEY**：不存在 Wrist Journey；Wrist Evidence 在手机统一进入
  Canonical Daily State / Significant Change / Journey（provenance 可显示"来自手环活动信号"）。
- 禁止：5 秒 Wrist observation → Memory。Sensor data 属于 Observation。
- v1 软件实现：腕上观察进入 `WristObservationLog`（内存有界、local only、短保留），
  promotion 门关闭（需真机验证）；不写 Memory / Journey / Portrait。

## 10. Me — ECHO on Wrist（手机入口）

一级：Device / Connection / ECHO Wrist availability / Wearing context permission /
Sleep context permission（能力存在时）/ Haptics / Privacy / Disconnect。
Advanced：protocol version / last sync / device capability / diagnostics。
每个权限只回答：为什么？ECHO 会得到什么？可以关闭吗？不写工程术语；不暴露 SDK 细节。

## 11. OSS / Proprietary 边界与外部阻塞

- OSS 构建不因缺 Xiaomi SDK 失败：WearablePlatformPort + Noop + Fake 适配器。
- 未拿到 SDK 禁止凭文档猜 class/interface；SDK AAR 不进 git（获取路径见 vendor boundary）。
- 生产签名材料不进仓库；interconnect 要求 Android/Vela 同包名同签名（文档化 + CI secret injection）。
- 允许保留的唯一阻塞标记：`BLOCKED_EXTERNAL_XIAOMI_SDK` / `BLOCKED_EXTERNAL_BAND10_DEVICE` /
  `BLOCKED_EXTERNAL_XIAOMI_THIRD_PARTY_CHANNEL` / `BLOCKED_EXTERNAL_AIOT_IDE_PACKAGING` /
  `BLOCKED_EXTERNAL_LONG_RUN_DEVICE_TIME` / `BLOCKED_EXTERNAL_PRODUCTION_SIGNING` /
  `BLOCKED_EXTERNAL_ANS_HARDWARE`
  （每个含：缺失资源 / 已完成测试 / 确切人工下一步 / 禁止的宣称 —— 见 Capability Matrix §4）。

## 12. 测试纪律

- Protocol：5 envelope 编解码 / unknown+future field / malformed / missing optional /
  unsupported schema / duplicate / out-of-order revision / expired / clock skew。
- Privacy：所有 Band payload 自动扫描，API key / Memory / Correction / private Context /
  provider config / notification / audio / location / identity seed → hard FAIL。
- Connection：first connect / disconnect / reconnect / duplicate / loss / reorder /
  phone process death / band process death / stale cache / refresh / expired；
  Identity continuity 必须保持。
- Capability：以 Matrix 为源（SUPPORTED/UNSUPPORTED/UNKNOWN）。
- Production integration：`WearableApplicationIntegrationTest`（:app）——
  AppContainer 同款装配 + FakeWearablePlatform + Fake Presence source +
  real WearableRuntime + real EchoActionRuntime；覆盖自动启动/自动入站消费/连接推送/
  WHY 往返/动作所有权/手机侧动作 → 腕上推送/观察 sink/disconnect-reconnect/
  重复消息/伪造 Presence/触觉管道。
- Manifest capability：`DeclaredVelaFeaturesTest` —— 声明即使用（MINIMUM CAPABILITY
  DECLARATION）；使用即声明（官方要求 feature 的模块）；未使用（system.fetch）禁止声明。
- 手环端 Node 静态测试：协议 parity / cache 纪律 / 视觉确定性 / 降级（无 Vela 工具链依赖；
  Vela 构建属外部 proprietary tool，不进 OSS CI）。
