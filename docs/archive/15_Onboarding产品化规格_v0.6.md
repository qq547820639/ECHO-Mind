# Onboarding 产品化规格（v0.6 收口）

> 作者：产品经理 许清楚（Xu）｜版本：v0.6｜状态：收口规格（产品化，非新功能）
> 上游输入：`docs/01_PRD.md`、`docs/01b_PRD增量_v0.6.md`、`docs/02b_架构收口设计_v0.6.md`、`backend/app/api/routes.py`、`android/.../ui/OnboardingScreen.kt`、`android/.../AppPreferences.kt`、`android/.../AppContainer.kt`
> 边界铁律（全文档适用）：**非诊断、非医疗**。被动行为数据不得推断情绪/自杀意图/精神疾病；不回归主动签到/情绪日记/PHQ-GAD 范式；原始数据不上云。

---

## 0. 目标与范围

### 0.1 目标

把当前"单页堆叠、暴露内部标识"的首次使用流程，产品化为**多步骤渐进披露（progressive disclosure）+ 七态状态机 + 服务端激活同步**的正式 Onboarding：

1. 真实用户只接触机构**激活码/邀请码**，绝不接触 `u_demo`、内部 `user_id`、`access_token`、机构配置令牌等内部标识；
2. 本地完成状态与服务端激活状态**强一致**，断网可继续、恢复后自动补完，且不重复创建 user/consent；
3. 分项 consent 可独立授予、可随时撤回；拒绝可选权限不影响核心功能；
4. 全部文案守住"非诊断、非医疗、非紧急服务"边界。

### 0.2 不在范围

- 不做新功能（不加诊断、治疗、药物建议、情绪识别）。
- 不回归主动签到/情绪日记/量表录入范式（入口保持 410 Gone）。
- 不改动 L0 之后的人工接管工作台核心流。
- 不引入第三方账号体系（机构 IAM/SSO/MFA 属外部门禁，本规格只做激活码短凭证过渡）。

### 0.3 现状问题（依据代码核实）

| # | 问题 | 证据 |
|---|---|---|
| 1 | Onboarding 单页堆叠全部字段，真实用户需手填 `试点用户 ID`（默认 `u_demo`）与 `机构配置令牌（试点环境）`，暴露内部标识，且 `u_demo` 硬编码为默认值 | `OnboardingScreen.kt` L38-40、`AppPreferences.kt` L25 |
| 2 | 无"激活码 → 服务端验证"链路；机构绑定仅是把字符串写进 SharedPreferences，`accessToken` 由用户手填（虽加密存储但来源错误） | `OnboardingScreen.kt` L103-105 |
| 3 | 无七态状态机；`onboardingCompleted` 为单布尔位，无法表达"已绑定未同步/激活失败/离线待补完"等中间态 | `AppPreferences.kt` L16-18 |
| 4 | 本地完成即置位，未与服务端 user/consent 存在性对齐；重复提交无幂等护栏（后端已有 event_id 幂等，但客户端未利用） | `OnboardingScreen.kt` L136；`routes.py` L353-361 幂等仅 L0 有 |
| 5 | 后端缺少面向真实用户的激活交换端点；`POST /users` 仅 admin/professional 可建，`POST /onboarding/consents` 需先有 JWT principal，客户端当前无法在"未激活"状态下合法调用 | `routes.py` L271-299、L302-329 |

---

## 1. Onboarding 状态机规格（七态）

### 1.1 状态定义

| 状态 | 定义 | 本地持久化表示 | 服务端对应 |
|---|---|---|---|
| `NOT_STARTED` | 新安装，未开始 | `onboarding_state=NOT_STARTED` | 无 user |
| `ACTIVATING` | 已提交激活码，正在与服务端交换 | `onboarding_state=ACTIVATING` + 临时 request 记录 | 交换请求进行中 |
| `ACTIVATION_FAILED` | 激活交换失败（无效码/网络失败/服务端拒绝） | `onboarding_state=ACTIVATION_FAILED` + 错误类型 | 无 user 或 user 存在但未完成激活 |
| `BOUND` | 激活成功：租户绑定 + user 身份 + 短时凭证已安全存储 | `onboarding_state=BOUND` + user_id + 凭证密文 | user 已创建（external_ref=激活码/设备） |
| `CONSENT_PENDING` | 绑定完成，分项 consent / L0 已在本地记录，服务端确认未完成 | `onboarding_state=CONSENT_PENDING` + 本地 consent/L0 证据 | user 存在；consent/L0 部分或未同步 |
| `READY_OFFLINE` | 本地全部 Onboarding 步骤完成，可离线使用核心功能；服务端激活确认待网络恢复 | `onboarding_state=READY_OFFLINE` + `onboardingCompleted=true` | user 存在；consent/L0 待同步确认 |
| `READY` | 本地与服务端一致：user + 全部必选 consent + L0(eligible) 均在服务端确认 | `onboarding_state=READY` + `onboardingCompleted=true` + `serverActivated=true` | user + consent + L0 齐全 |

> 说明：`READY_OFFLINE` 与 `READY` 对用户均表现为"可进入应用"；区别只在服务端确认位 `serverActivated`。进入 `READY` 后，日常离线属 `SyncState` 域（`synced/pending/offline/...`），不再回退 Onboarding 状态。

### 1.2 状态转换（合法边）

```
NOT_STARTED ──提交激活码──► ACTIVATING ──交换成功──► BOUND
     ▲                          │
     │                          ├──交换失败/无效码──► ACTIVATION_FAILED
     │                          │                        │
     │                          │                    ┌───┴───┐
     │                          │                    │ 重试   │（改码/重试）
     │                          │                    └───▲───┘
     │                          └────────────────────────┘
     │
BOUND ──完成本地 consent/L0（尝试上传）──► CONSENT_PENDING
     │                                        │
     │                                        ├──服务端全部确认──► READY
     │                                        └──离线/未确认，本地步骤已全完成──► READY_OFFLINE
     │
CONSENT_PENDING ──网络恢复+自动补传+服务端确认──► READY
READY_OFFLINE   ──网络恢复+自动补传+服务端确认──► READY
```

转换规则：

- `NOT_STARTED → ACTIVATING`：用户输入非空激活码并确认（年龄门、边界确认先通过）。
- `ACTIVATING → ACTIVATION_FAILED`：交换返回无效码/已被使用/服务端拒绝，或网络失败且重试达阈值（建议 3 次）仍失败。
- `ACTIVATION_FAILED → ACTIVATING`：用户更换激活码或点"重试"。
- `ACTIVATING → BOUND`：`POST /v1/onboarding/activate` 成功，返回 `user_id` + 短时凭证；凭证经 `FieldCipher` 加密写入 `AppPreferences.accessToken`，`user_id` 写 `AppPreferences.userId`（**均来自服务端响应，不由用户输入**）。
- `BOUND → CONSENT_PENDING`：进入 consent 步骤后，本地先落库 consent/L0 证据（含 SHA-256），随即尝试上传；无论上传成败都进入 `CONSENT_PENDING`（未确认态）。
- `CONSENT_PENDING → READY`：服务端确认全部必选 consent + L0(eligible)（通过同步结果或 `GET /onboarding/consents/latest` + L0 回读比对）。
- `CONSENT_PENDING → READY_OFFLINE`：本地全部步骤完成但服务端未确认（网络不可用/确认待重试）。
- `READY_OFFLINE → READY`：网络恢复，激活同步 Worker 自动补传且服务端确认，`serverActivated=true`。
- 禁止边：`BOUND → READY`（跳 consent）；`ACTIVATING → READY`（跳绑定）；`CONSENT_PENDING → BOUND`（倒退）；`READY → 任何 Onboarding 态`（不倒退；服务端数据被清空的极端场景走"重新激活"单独流程并需人工/机构确认）。

### 1.3 每状态 UI 呈现

| 状态 | UI |
|---|---|
| `NOT_STARTED` | 欢迎页：产品一句话说明 + 年龄门（18+）+ 边界确认 + "开始"按钮 |
| `ACTIVATING` | 激活中页：进度指示 + 文案"正在验证机构激活信息…"；禁用重复提交 |
| `ACTIVATION_FAILED` | 错误页：失败原因分类文案（无效激活码/已被使用/网络异常），"重新输入激活码"与"重试"按钮；**不暴露 HTTP 状态码/内部错误** |
| `BOUND` | 绑定成功页：显示机构名称（来自服务端响应，不手填），进入产品边界说明与 consent 步骤 |
| `CONSENT_PENDING` | consent 分项页 + L0 说明页 + 紧急联系人 + 被动感知说明（多步渐进）；顶部显示轻量"正在确认你的选择…"（未阻断） |
| `READY_OFFLINE` | 完成页变体：进入应用，同时显示非阻断提示条"部分确认将在网络恢复后自动完成，你的数据仍安全保存在本机" |
| `READY` | 完成页：进入应用；后续由 `TodayScreen` 展示正常状态 |

### 1.4 本地完成状态与服务端激活状态的同步机制

**一致性原则**：本地 `onboarding_state` 是 UI 的即时来源；服务端 user/consent/L0 存在性是权威来源；二者通过**激活同步 Worker** 收敛，收敛过程**幂等、自动、不重复创建**。

1. **触发时机**：App 启动、`ACTIVATING`/`CONSENT_PENDING`/`READY_OFFLINE` 状态下网络恢复（ConnectivityManager 回调）、SyncWorker 运行前。
2. **同步流程**：
   a. 读取本地 `onboarding_state` 与已存 `user_id`；
   b. 若为 `BOUND`/`CONSENT_PENDING`/`READY_OFFLINE`：调用 `GET /v1/onboarding/consents/latest?user_id=...` 与服务端核对必选 consent（`psychological_data`、`passive_sensing`（若授权）、`voice_features`（若授权））；
   c. 比对 L0：调用 `POST /v1/onboarding/l0` 用**本地 event_id** 幂等提交（服务端已有同 event_id 则返回 `idempotent_replay: true`）；
   d. 上传缺失的 consent 记录（`POST /v1/onboarding/consents`，携带本地 event_id 幂等）；
   e. 全部确认 → `serverActivated=true`，状态推进 `READY`。
3. **不重复创建保证**：
   - 激活交换：`POST /v1/onboarding/activate` 以 `activation_code + device_id` 幂等——同一码/设备重复调用返回同一 `user_id`（服务端 upsert user by external_ref，`routes.py` L295-297 已对 external_ref 冲突返回 409，激活端点改为幂等 upsert 语义）；
   - consent：每条本地 consent 携带唯一 `event_id`，重复上传服务端返回既有对象（对齐 `create_consent` + audit 幂等约定）；
   - L0：本地生成唯一 `event_id`，服务端 `create_l0` 已实现幂等重放（`routes.py` L357-361）。
4. **失败策略**：单次同步失败不重置状态、不要求用户重填；进入后台退避重试（复用 SyncWorker 指数退避与 Retry-After 处理）；`ACTIVATION_FAILED` 仅在激活交换阶段出现，且不自动重试至阈值以上。
5. **端到端一致性断言（验收）**：注入假网络，走完"绑定→本地 consent→断网→READY_OFFLINE→恢复网络"序列后，服务端 user/consent/L0 各恰有一条记录（`idempotent_replay` 计数=0 新增、无重复行）。

---

## 2. 多步骤渐进披露流程设计

> 目标：每步只问该问的问题、只披露当前需要的风险，总时长中位 ≤5 分钟（对齐 PRD 成功指标）。

| 步骤 | 内容 | UI 要点 | 文案边界 |
|---|---|---|---|
| S0 欢迎与门禁 | 产品定位一句话 + 年龄门 + 边界确认 | 全屏欢迎；18+ 复选框；"我理解专业判断与危机处置由人工承担"；不可跳过 | 不承诺功效；不出现"治疗/治愈/诊断" |
| S1 机构绑定（激活码） | 输入机构**激活码/邀请码** | 单输入框 + 说明"激活码由你的机构提供"；**移除 user ID / access token / 机构配置令牌输入框** | 不要求、不展示任何内部标识 |
| S2 产品边界说明 | 是什么、不是什么 | 卡片式：支持工具 / 非诊断 / 非医疗 / 非紧急服务；危机时优先 110/120/机构值班 | 逐条固定文案，可做静态扫描锚点 |
| S3 分项 consent | 逐项授予 | 每项独立卡片+开关/勾选：`psychological_data`（核心必选）、`passive_sensing`（可选，默认关）、`voice_features`（麦克风，独立 consent，默认关）、`emergency_contact`（可选，填写时才需要）；每项显示版本号与"可随时撤回" | 麦克风单独说明"默认关闭、原始音频即时处理即丢弃、不上云"；不夸大收益 |
| S4 系统权限按需请求 | 与 S3 授权联动 | 仅当对应 consent 已授予才请求系统权限：通知使用权/通知（POST_NOTIFICATIONS）、传感器（BODY_SENSORS）、使用情况访问；逐项说明用途；**拒绝可选权限不阻断进入** | 每个权限说明用"用于"而非"必须"；不威胁性表述 |
| S5 L0 等级说明 | 说明筛选项与分流 | 先展示"为什么问这些"，再逐项勾选（当前危险 / 既往高风险 / 现实检验受损 / 物质影响 / 已有专业人员支持）；命中排除项 → 显示人工/安全支持入口，不进入普通服务 | 明确"这不是诊断，只是判断当前是否适合使用本工具" |
| S6 紧急联系人 | 可选填写 | 姓名/电话/关系 + 单独 consent（`emergency_contact`）；全部留空可跳过 | 仅说明"仅在危机人工接管范围内使用" |
| S7 被动感知说明 | 数据如何流转 | 图示/文案：端侧采集 → 5 分钟窗口派生特征 → 原始数据不落盘不上云 → 特征摘要上传；当前授权项汇总；"可随时在支持页关闭" | 不承诺"检测/监测你的情绪"；强调非诊断 |
| S8 完成页 | 汇总 + 进入 | 展示已授予项清单 + 状态（READY / READY_OFFLINE）；"进入应用"按钮；紧急入口常驻可见 | READY_OFFLINE 时显示非阻断待确认提示 |

### 2.1 步骤顺序与依赖

- S0 → S1（绑定）→ S2（边界）→ S3（consent）→ S4（权限）→ S5（L0）→ S6（紧急联系人）→ S7（被动感知说明）→ S8（完成）。
- S3 授权结果驱动 S4 权限请求集合；S6 仅在用户填表时要求 `emergency_contact` consent。
- S5 命中排除项：状态不进入 `READY`，跳转安全支持页（复用 `SafetyScreen`），Onboarding 终止于"受限"说明。

### 2.2 文案边界（全流程通用）

- 允许：支持/记录/趋势回顾/能力练习/人工支持入口/非诊断/非医疗/非紧急服务/可撤回/数据仍安全保存在本机。
- 禁止：诊断/治疗/治愈/康复承诺/医生替代/药物建议/情绪检测/心理评估结论/绝对化删除承诺。
- 所有禁止词纳入静态扫描（沿用宣称扫描门禁）。

---

## 3. 保留项清单（收口不可删）

| # | 保留项 | 现状依据 | 收口要求 |
|---|---|---|---|
| 1 | 18+ 年龄门 | `OnboardingScreen.kt` L57 | 保留并前置到 S0，作为进入前提 |
| 2 | 专业人员人工接管边界 | `docs/04`、L0 decision（`routes.py` L353-402） | 保留：L0 非 eligible → 人工/安全支持；危机链路仅由白名单来源触发（01b 契约点 1） |
| 3 | 紧急支持入口 | `SafetyScreen`、README FAQ | 保留且常驻（12356/110/120 + 机构值班）；Onboarding 完成页、主界面均可达 |
| 4 | 分项 consent | consent 类型：`psychological_data`/`passive_sensing`/`voice_features`/`emergency_contact` | 保留分项独立授予；服务端 `require_*_consent` 校验不变（412） |
| 5 | 可撤回 | 01b 契约点 3 原子撤回 | 保留：本地原子停止 + 撤回证据上传 + 后续 412；Onboarding 内明确告知可撤回 |

---

## 4. 服务端状态绑定设计

### 4.1 激活交换链路（activation code → 短时凭证）

```
机构侧生成激活码（一次性/限次，绑定 tenant，可预置或自声明 user）
        │
        ▼
Android 提交 POST /v1/onboarding/activate {activation_code, device_id, client_time}
        │
        ▼
Backend 验证激活码（有效/未过期/未用尽/属于某 tenant）
        │
        ▼
Backend upsert User（external_ref=code 或 device 绑定；幂等：同码/同设备返回同一 user_id）
        │
        ▼
Backend 签发短时凭证：JWT(sub=user_id, tenant_id, role=user, exp=短时)
        │
        ▼
响应 {user_id, tenant_name, access_token(短时), token_expires_at}
        │
        ▼
Android 用 FieldCipher 加密存储 access_token（AppPreferences.accessToken 已是密文路径），
user_id 存储由服务端响应写入（AppPreferences.userId）
```

### 4.2 必须等机构/服务端确认（不可离线）

| 环节 | 原因 | 幂等键 |
|---|---|---|
| 激活码验证 | 需要服务端权威判定码有效性与归属租户 | `activation_code` |
| Tenant 绑定 + user 身份创建 | 决定租户隔离与数据归属 | `activation_code + device_id` |
| 短时凭证签发 | 后续所有 API 调用的鉴权前提 | 一次性签发 |
| 必选 consent / L0 的服务端落库确认 | 合规与安全链路的权威记录 | `event_id`（consent/L0） |

### 4.3 可离线完成（READY_OFFLINE 语义）

| 环节 | 说明 |
|---|---|
| 本地 consent 选择与证据计算 | 全部本地完成（含 SHA-256 证据哈希），写入 Room/outbox |
| 本地 L0 勾选与 decision 计算 | 本地规则可先行判断（阻断进入的本地门禁），服务端确认可后补 |
| 紧急联系人本地填写 | 本地加密存储，上传待网络恢复 |
| 系统权限申请 | 纯本地系统交互，与服务端无关 |
| 被动感知偏好设置 | 本地 DataStore，服务启动与否只看本地 consent ∧ flag（fail-closed） |

**READY_OFFLINE 语义**：`BOUND` 之后的所有 Onboarding 步骤均可在离线完成，本地先产生完整证据与可运行状态；服务端确认由激活同步 Worker 在恢复网络后幂等补完，期间不阻断用户进入应用，UI 明确提示"部分确认待网络恢复，数据仍安全保存在本机"。

### 4.4 安全要求

- 激活码不可出现在日志、审计明文、UI 回显；传输走 HTTPS（真机/试点禁止明文 HTTP）。
- 短时凭证 TTL 建议 ≤24h；过期后按 SyncState `blocked_by_auth` 处理（01b 契约点 9），提示重新激活或联系机构，而非自动长时间静默失效。
- 激活交换端点需限流（按 IP/设备）防暴力枚举激活码；激活码格式建议高熵（如 `XXXX-XXXX-XXXX`）。
- 本地存储：凭证密文走既有 `FieldCipher`（Android Keystore 派生），不新增明文字段。

---

## 5. 对现有代码的最小改动指引（供工程师）

| 文件 | 改动 |
|---|---|
| `OnboardingScreen.kt` | 拆分为多步向导；删除 userId/accessToken 输入框；保留 L0 判定纯函数；接入状态机 |
| `AppPreferences.kt` | 新增 `onboardingState` 枚举持久化；`userId` 默认值改为空（不再 `u_demo`）；新增 `serverActivated` 位 |
| `AppContainer.kt` | 注册激活同步 Worker / 状态机依赖；保持现有工厂不变 |
| `LocalRepository.kt` | 新增 `activate(code)`、`syncActivation()`（幂等补传 consent/L0）、`fetchActivationStatus()` |
| `SyncWorker.kt` | 在同步前执行 `syncActivation()` 若状态为 BOUND/CONSENT_PENDING/READY_OFFLINE |
| `backend/app/api/routes.py` | 新增 `POST /v1/onboarding/activate`（幂等 upsert user + 签发短时凭证）；consent/L0 幂等沿用 event_id |
| `backend/app/schemas.py` | 新增 `ActivationCreate/ActivationOut` |
| `backend/app/auth.py` | 复用 `create_access_token` 签发短时凭证（role=user） |

> 后端当前无激活端点（见 0.3 问题 5），本规格定义其契约；工程师实现时保持幂等与限流。

---

## 6. 验收要点（产品视角）

1. 状态机：七态纯函数全覆盖；禁止边触发返回错误；`READY_OFFLINE → READY` 在恢复网络后自动且只发生一次（无重复 user/consent）。
2. 流程：Onboarding 不再出现 `u_demo`/内部 user_id/access token/机构配置令牌输入；真实用户全程只见激活码。
3. 文案：静态扫描无诊断/治疗/功效承诺；README 与 pilot-pack 边界一致。
4. 撤回：Onboarding 内完成授权后，可在支持页撤回；撤回后 `POST /v1/features/ingest` 返回 412。
5. 离线：断网完成 S3-S7，进入 READY_OFFLINE；恢复后自动补传且幂等（服务端各记录唯一）。
