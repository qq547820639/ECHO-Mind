# PRD 增量 v0.6：代码闭环、架构收口与用户体验深化

> 本文档是 `docs/01_PRD.md`（v0.2 被动感知范式）的**增量契约修正版**。仅覆盖本迭代的 9 个产品契约修正点；未提及的既有能力以代码实际行为为准。文档内所有【现状问题】均基于 main 分支代码（`bf9f778`）核实，不以可能漂移的历史文档为准。

## 0. 项目信息

| 项 | 值 |
|---|---|
| 版本 | v0.6（增量） |
| 语言 | 中文 |
| 产品范式 | 被动感知 + 自进化 Skill + 人工安全链路（v0.2 延续） |
| 技术栈 | Android（Compose + Kotlin）/ FastAPI / PostgreSQL / SQLite |
| 上游基线 | docs/01_PRD.md、docs/02_架构与数据流.md、docs/05_隐私安全与威胁模型.md、docs/03_API契约.md |
| 本迭代目标 | 修正被动 Safety 语义边界、统一 consent 中心、Skill 执行与治理契约化、DSR 删除矩阵化、信息架构与趋势/同步 UX 状态化 |
| 不在范围 | 不改动危机人工接管工作台核心流、不新增自动诊断/治疗方案、不做 iOS/小米手环/摄像头/后台录音 |

## 1. 契约修正点总览

| # | 契约点 | 优先级 | 主要影响面 |
|---|---|---|---|
| 1 | 被动 Safety 产品契约重定义 | P0 | backend safety.py/routes.py、Android SafetyEngine/LocalRepository/TodayScreen |
| 2 | 取消行为遥测→"情绪"错误语义映射 | P0 | backend profile.py、Android TrendScreen/Models |
| 3 | 统一"数据与感知" consent 中心 | P0 | Android SupportScreen/PassiveSensingService/PassiveSensingPrefs、backend routes.py |
| 4 | Skill 是 declarative capability 而非任意动态代码 | P0 | backend models/schemas/sanitizer、Android SkillCardHost |
| 5 | Skill 治理状态机（signed 门禁 + 重签 + 缓存失效） | P0 | backend routes.py/models.py、Android fetchSkills 缓存 |
| 6 | DSR 数据删除矩阵 | P0 | backend routes.py/models.py、Android SupportScreen |
| 7 | Android 信息架构（4 Tab 聚焦） | P1 | Android EchoMindApp/OtherScreens |
| 8 | 趋势页 UX 状态契约（七态） | P1 | Android TrendScreen、backend trends/narratives |
| 9 | 同步 UX 状态契约（SyncState） | P1 | Android SyncWorker/TodayScreen/SupportScreen |

---

## 2. 契约修正点 1：被动 Safety 产品契约重定义（P0）

### 【现状问题】

1. `backend/app/services/safety.py::evaluate_passive()` 对端侧派生的 **行为摘要文本** 做 `PASSIVE_RED_TERMS`（"自杀/自残/结束生命/活不下去/不想活"）红色匹配；命中即写入 `RiskSignal` 并 `open_escalation(trigger="passive_red_signal")`（`routes.py` L1105-1130）。
2. Android `SafetyEngine.evaluatePassive()` 在端侧对行为摘要做同语义匹配，命中即 `freezeGeneration=true` 并 enqueue `passive_red_signal` escalation（`LocalRepository.saveDerivedFeature` L281-291），且 `TodayScreen` 观察 `passiveSafety` RED 后切入 `SafetyScreen`。
3. 上述链路实质上是"从加速度/屏幕次数/通知次数/App usage 派生出的统计文本 → 推断自杀/自伤意图"，与 `docs/01_PRD.md` "被动 RED 一轮内冻结普通生成并创建 L3 事件"一致，属于必须修正的产品语义越界。
4. 该链路不做否定语境检查、不做人工复核门槛，命中即触发 L3 危机事件，存在误报与过度临床化风险。

### 【新契约定义】

- **禁止用途**：被动行为数据（加速度/陀螺仪/屏幕事件/通知/App usage/麦克风派生特征）**不得**用于推断：自杀意图、自伤意图、精神疾病、抑郁/焦虑诊断，也不得据此触发危机事件或冻结生成。
- **允许用途**（仅限以下非诊断用途）：
  - 数据覆盖度（是否有足够的采样窗口）；
  - 活动节律（活动量高低变化的时间分布）；
  - 屏幕互动变化（屏幕开启次数/时长的相对变化）；
  - 非诊断行为趋势（相比个人基线的变化）；
  - 是否值得向用户展示**中性关怀入口 / 人工支持入口**（如"你可以随时在支持页联系人工"，不得使用情绪/危机措辞）。
- **危机信号唯一来源**（白名单，其余一律不触发危机链路）：
  1. 用户主动点击"我现在需要帮助"（`help_requested`）；
  2. 明确安全自报流程：L0 准入 `current_danger`；用户主动输入文本经 `evaluate_text()` 命中 RED（保留现有文本规则，文本是用户主动表达，非行为推断）；
  3. 专业人员人工事件（professional/on_call 经工作台登记的事件）；
  4. 经临床批准的明确输入渠道（临床负责人签署的量表/评估，如 PHQ-9 第 9 项非零需人工复核——保持人工复核，不做自动危机判定）。
- **必须移除**：
  - 端侧 `SafetyEngine.evaluatePassive()` 及其在 `saveDerivedFeature` 中的调用与 `passiveSafety` RED 触发链路；
  - 后端 `evaluate_passive()` 在 `POST /v1/features/ingest` 中对派生特征的调用；`trigger="passive_red_signal"` 不再作为自动触发来源。
- **常驻入口**：一键可达的 人工支持/12356/110/120 入口始终保留（全局紧急 FAB + SUPPORT 页）。

### 【验收标准】（可自动化验证）

1. **单元测试**：`SafetyEngine.evaluatePassive` 删除或降级为恒 `NONE`；后端 `evaluate_passive` 不再被 `ingest` 路径调用（静态引用检查通过）。
2. **集成测试**：`POST /v1/features/ingest` 传入任意 summary（含 PASSIVE_RED_TERMS 词条与不含两种），响应 `escalation_id` 恒为 `null`，且不创建 `RiskSignal`、不创建 `trigger=passive_red_signal` 的 `Escalation`（DB 断言）。
3. **行为回归测试**：喂入纯行为摘要（如"过去5分钟活动量低，屏幕开启0次，收到0条通知"），不产生任何 red/yellow 信号。
4. **UI 测试**：`TodayScreen` 不再由 `passiveSafety` RED 切 `SafetyScreen`；`SafetyScreen` 仅由用户主动求助/L0/文本 RED/人工事件进入。
5. **入口不变量测试**：`shouldShowEmergencyFab()` 在非 SUPPORT tab 恒为 true；SUPPORT 页存在 12356/110/120 三个可拨号按钮。
6. **静态扫描**：仓库不存在"行为特征 → 自杀/自伤/抑郁/焦虑/诊断"的映射函数、规则词表或 UI 文案。

---

## 3. 契约修正点 2：取消行为遥测→"情绪"的错误语义映射（P0）

### 【现状问题】

1. `backend/app/services/profile.py::_mood_hint_from_summary()` 将派生摘要文本中的词（低落/焦虑/疲惫/压力/难过/失眠/烦躁/孤独 → "偏低"；开心/平静/放松/愉快/积极/充实 → "平稳偏积极"）映射为情绪标签；`build_daily_narrative()` 聚合出 `DailyNarrative.mood_hint`，`update_profile()` 写入 `traits.recent_mood_hint`。
2. Android `OtherScreens.kt::TrendScreen` 用 `moodHintToValue()`（平稳偏积极=4/平稳=3/偏低=2/未知=0）把叙事画成"情绪提示"折线，并展示"最近情绪 ${recentMoodHint}"、"近 7 天情绪提示"文案。
3. 该映射是"行为遥测 → 情绪"的伪精确语义，用户可能误读为情绪诊断/情绪变化结论，违背非诊断边界。

### 【新契约定义】

- **禁止输出**（UI、API、叙事、画像、文案一律不得出现）："你今天情绪低落""你的情绪平稳""你最近更积极""最近情绪 X"等表述。
- **允许输出**（非诊断表达）：活动节律、行为模式、数字互动节律、状态线索、数据覆盖度、相比个人基线的变化。
- **趋势页面必须包含免责文案**（固定常量，作为单测锚点）：
  > "这些趋势来自设备上的行为派生特征，不能知道或判断你的真实情绪。"
- **数据契约调整**：
  - `DailyNarrative.mood_hint` 字段废弃（迁移后置空/改名 `state_hint`，不再含情绪语义）；
  - `UserProfile.traits.recent_mood_hint` 移除；
  - `TrendScreen` 折线改为展示行为派生指标（活动节律/屏幕互动/覆盖度），删除伪精确"情绪评分折线"。

### 【验收标准】

1. **单元测试**：`_mood_hint_from_summary`、`moodHintToValue`、`buildTrendValues` 删除；不存在生成情绪标签的纯函数。
2. **API 测试**：`GET /v1/narratives` 响应不再含 `mood_hint`（或置空并标记 deprecated）；`GET /v1/profile/{id}` traits 不含 `recent_mood_hint`。
3. **文案扫描**：Android `strings.xml` 与 Kotlin 源码不含"情绪低落/情绪平稳/更积极/最近情绪/情绪提示"等禁止表述（免责文案除外）。
4. **UI 测试**：`TrendScreen` 不渲染情绪折线；页面含免责文案常量（暴露 internal 常量供断言，沿用 `PRACTICE_DEPRECATION_NOTICE` 模式）。
5. **迁移测试**：既有 `DailyNarrative` 数据迁移后 `mood_hint` 不再被任何读取路径使用。

---

## 4. 契约修正点 3：统一"数据与感知" consent 中心（P0）

### 【现状问题】

1. 被动感知总开关仅在 `OnboardingScreen` 一次性设置（`PassiveSensingPrefs.setPassiveSensingEnabled`），**无**事后修改入口；`SupportScreen` 只有麦克风开关。
2. `PassiveSensingService.start/stop` 无生产调用方（仅测试引用），实际不存在可用的"停止采集"用户路径。
3. 关闭采集不满足原子性：无"清空原始 buffer"（内存缓冲 + `sensor_samples` 表）、无 revoke evidence 写入、无撤回事件上传、无"后续零新特征"保证。
4. 网络不可用时的本地停止流程未定义。
5. Feature Flag 与用户 consent 的关系未定契约：`PassiveSensingService.isPassiveSensingEnabled()` 仅看 flag（默认 true），未校验用户 consent，存在 flag 语义覆盖用户撤回的风险。

### 【新契约定义】

- 在"支持与设置"（SUPPORT 页）新增**统一"数据与感知" consent 中心**，必须展示：
  1. 被动感知总开关；
  2. Activity/传感器状态（accel/gyro 是否采集中）；
  3. 屏幕事件状态；
  4. Notification access 状态；
  5. App usage access 状态；
  6. 麦克风状态；
  7. 最近成功采集时间；
  8. 最近成功同步时间；
  9. 当前是否离线；
  10. 本地待同步数量。
- **关闭总开关必须原子完成**（本地优先，不依赖网络）：
  1. 本地 `consent=false`（DataStore 持久化）；
  2. 停止服务（`PassiveSensingService.stop()` + 各 Collector stop + NotificationListener 停用）；
  3. 清空原始 buffer（内存缓冲 + `sensor_samples` 表清空）；
  4. 写 revoke evidence（`consent_type=passive_sensing, granted=false` + SHA-256 证据哈希）入 outbox；
  5. 上传撤回事件（`SyncWorker`，网络恢复后重试）；
  6. 后续零新特征：`FeatureExtractor` 不再调度，`POST /v1/features/ingest` 因 consent 撤回返回 412；
  7. UI 显示"已停止"。
- **网络不可用不能阻止本地停止**：步骤 1-4、6 全部本地完成；步骤 5 由 outbox 保证网络恢复后补传。
- **Feature Flag 不允许覆盖用户 consent**：采集前提 = `user_consent=true AND flag=true`。任一为 false 即不采集；flag 只能收紧（运营灰度），不能把已撤回的同意恢复为有效。
- 总开关与麦克风分项联动：总关 → 麦克风同时停用。

### 【验收标准】

1. **UI 测试**：SUPPORT 页含"数据与感知"区块，10 项状态全部存在且与本地存储/服务状态一致。
2. **原子性测试**（Robolectric/仪器）：执行关闭流程后断言：`consent=false` 已持久化；服务 stopped；`sensor_samples` 表行数=0；outbox 存在 `consent/passive_sensing/granted=false` 事件且 evidence 可重算；后续 `FeatureExtractor.extract` 不产出。
3. **离线测试**：断网执行关闭，断言步骤 1-4、6 完成、UI 显示"已停止"；恢复网络后撤回事件自动上传成功。
4. **后端联动测试**：关闭后 `POST /v1/features/ingest` 返回 412。
5. **Flag 优先级测试**：`consent=false + flag=true` → 不采集（412）；`consent=true + flag=false` → 暂停采集（410 语义保留）；两者均不可使已撤回同意恢复。
6. **状态准确性测试**：最近成功采集/同步时间、待同步数量与 DB 实际值一致（可注入时钟断言）。

---

## 5. 契约修正点 4：Skill 是 declarative capability 而非任意动态代码（P0）

### 【现状问题】

1. `Skill` 模型（models.py）仅有 `trigger_conditions / guardrails / steps`（steps 为 `list[dict]` 含 description），**无** `action_type / estimated_duration / completion_schema / safety_constraints` 执行契约字段。
2. Android `SkillCardHost` 的"开始"按钮 `onTrigger` 为**空操作**（`SkillCardHost(skill) {}`，`TodayScreen` 注释"Skill 执行流程不在本任务范围"）——每个可见"开始"按钮没有真实行为。
3. 卡片用 `WebView` 渲染（JS/文件/DOM 存储已关闭），存在可访问性（TalkBack）、字体缩放、暗色模式、加载性能、UI 一致性的已知短板。
4. 仓库无 `action_type` 白名单、无 eval/动态代码的显式禁令声明（当前未实现，但契约缺失）。

### 【新契约定义】

- **正式执行契约字段**（Skill 下发必须携带）：
  | 字段 | 说明 |
  |---|---|
  | `skill_id` | Skill 唯一 ID |
  | `version` | 语义版本号（int） |
  | `action_type` | 仅允许白名单枚举 |
  | `steps` | 有序步骤（每步 description + 可执行参数） |
  | `estimated_duration` | 预计耗时（秒） |
  | `completion_schema` | 完成/停止上报的 JSON Schema |
  | `safety_constraints` | 安全边界（含"不适立即停止"等强制项） |
- **action_type 白名单**（示例，最终由临床/内容负责人签署）：`guided_practice / breathing / reflection_prompt / info_card / stop`。白名单外一律不校验通过、不下发、不执行。
- **禁止**：`eval` / 动态下载代码执行 / WebView JS 执行 / 任意 URL 加载 / 任意 shell 执行。Skill 内容只能是声明式数据（JSON），不承载可执行代码。
- **每个可见"开始"按钮必须有真实行为**：点击后进入至少三态状态机（执行中 → 完成/停止），并按 `completion_schema` 上报结果；无真实行为实现的按钮不得渲染。
- **卡片渲染评估**：优先评估将 WebView 静态展示改为**原生 Compose Skill Card**，目标维度：可访问性/TalkBack、字体缩放、暗色模式、加载性能、UI 一致性。若评估结论保留 WebView，须输出书面理由且必须维持 JS 关闭、内容转义。

### 【验收标准】

1. **Schema 测试**：`SkillOut` 含上述 7 个执行契约字段；`action_type` 枚举校验——白名单外值 422 且不下发。
2. **静态扫描**：Android 源码禁止 `eval`、`setJavaScriptEnabled(true)`、动态下载代码执行、`ProcessBuilder`、任意 URL 加载进卡片；后端禁止在 Skill/Tool 内容中出现可执行代码字段。
3. **UI 行为测试**：枚举渲染出的每个"开始"按钮，点击后状态机非空（执行中→完成/停止）；不存在点击无响应的按钮（handler 引用非空断言）。
4. **可访问性测试**：TalkBack 可完整朗读卡片标题/步骤/按钮；字体缩放 200% 内容可读；暗色模式对比度满足 WCAG AA。
5. **性能测试**（若改造为 Compose）：首帧渲染耗时 ≤ WebView 基线（同一台测试机同数据）。
6. **上报测试**：执行完成后 `completion_schema` 校验通过并成功落库/上报。

---

## 6. 契约修正点 5：Skill 治理状态机（P0）

### 【现状问题】

1. 状态机 `draft→reviewed→signed→retired` 已实现（`SKILL_TRANSITIONS`，`routes.py` L1263），但 `DELIVERABLE_SKILL_STATUSES=("reviewed","signed")`（L1270）——**reviewed 状态也会下发给普通用户**，违背"只有 signed 才能下发普通用户"。
2. `Skill` 模型无 `signed_by / signed_at / policy_version / review_evidence / revision / supersedes_skill_id` 字段。
3. 内容变化无需重新签署：`transition_skill` 不校验 `content_hash` 是否变化；已 signed Skill 内容被改后仍可能维持 signed。
4. retire 后客户端缓存不会失效：Android `fetchSkills` 缓存 TTL 1 小时（无版本/ETag/revocation 机制），retired Skill 可能继续在旧缓存中展示。

### 【新契约定义】

- **状态机**：`draft → reviewed → signed → retired`（相邻转换，不能跳级、不能逆转，保留现有约束）。
- **下发门禁**：
  - 普通用户：**仅 `signed`** 可下发；
  - `reviewed` 仅限专业人员预览/QA/staging（professional/admin 角色可经专门端点查看，普通用户不可见）。
- **新增元数据**：`signed_by`（签署人角色/ID）、`signed_at`、`policy_version`（签署时策略版本）、`review_evidence`（审核证据引用）、`revision`（内容修订号）、`supersedes_skill_id`（取代的旧 Skill ID）。
- **内容变化必须重新签署**：任一内容字段（trigger_conditions/guardrails/steps/action_type/safety_constraints）变化后，状态必须回退 `draft`（或拒绝保留 signed）；必须重新走 reviewed → signed 才可再次下发。
- **retire 后客户端缓存失效**：
  - `GET /v1/skills` 返回 `etag`/`content_version`；
  - 客户端缓存须携带版本，拉取后清除已 retired 条目的本地缓存；
  - 提供 revocation 机制（如 `GET /v1/skills/revoked` 返回已撤销 skill_id 列表，或推送通知），使缓存中的 retired Skill 立即失效。

### 【验收标准】

1. **下发门禁测试**：普通 user `GET /v1/skills` 仅返回 `signed`；`reviewed` 对普通 user 不可见（空/过滤）；professional/admin 经预览端点可读 `reviewed`。
2. **状态机测试**：`draft→reviewed→signed` 成功；`draft→signed` 409；`signed→reviewed` 409；`retired` 后从列表消失且不可恢复。
3. **重签测试**：已 signed Skill 修改内容（content_hash 变化）→ 状态自动回 draft（或写操作被拒）；未重签不得再次 signed；重签后 `signed_at/policy_version/revision` 更新且 `supersedes_skill_id` 指向旧版本。
4. **签名完整性测试**：`signed` 状态必须同时具备 `signed_by + signed_at + policy_version + review_evidence`，缺任一视为无效不下发。
5. **缓存失效测试**：retire Skill → `GET /v1/skills` 响应不含该 skill 且 `etag/content_version` 变化 → Android 刷新后 UI 不再显示该卡片（含旧缓存场景）。
6. **迁移测试**：既有 Skill 行迁移后新字段可空、旧数据不阻塞现有 signed 下发（或按策略标记需重签）。

---

## 7. 契约修正点 6：DSR 数据删除矩阵（P0）

### 【现状问题】

1. `complete_dsr`（routes.py L1023-1053）在 `request_type=delete` 时**仅删除** `DerivedFeature + RiskSignal`，其余数据类型（Consent/EmergencyContact/Checkin legacy/Journal legacy/Questionnaire legacy/Practice legacy/DailyNarrative/UserProfile/Escalation/Skill 个性化数据/SandboxRun/DSR 记录/AuditEvent）无定义。
2. 返回体仅 `{id,status,completed_at}`，无每类删除结果摘要。
3. UI 文案"已创建删除请求；依法需保留的数据可能不立即删除"承诺模糊，未向用户说明哪些删除、哪些依法保留及原因。
4. 现状对 `RiskSignal` 执行 delete，与"风险事件是追加式记录、不可被普通业务更新覆盖"（docs/02）的安全原则冲突。

### 【新契约定义】

- **数据删除矩阵**（每类必须明确 action + retention reason + period + legal hold）：

| 数据类型 | Action | Retention reason | Period | Legal hold |
|---|---|---|---|---|
| Consent | retain | 同意/撤回证据链（合规审计） | 法定期限（如 3 年，以法务定稿为准） | 是 |
| EmergencyContact | delete | — | 即时 | 否 |
| Checkin (legacy) | delete | — | 即时 | 否 |
| Journal (legacy，含 revision 历史) | delete | — | 即时 | 否 |
| Questionnaire (legacy) | delete | — | 即时 | 否 |
| Practice (legacy) | delete | — | 即时 | 否 |
| DerivedFeature | delete | — | 即时 | 否 |
| DailyNarrative | delete | — | 即时 | 否 |
| UserProfile | delete | — | 即时 | 否 |
| RiskSignal | **retain/anonymize**（从 delete 改为保留） | 危机处置与安全审计（append-only） | 法定期限 | 是 |
| Escalation | retain | 危机处置记录（append-only） | 法定期限 | 是 |
| Skill 个性化数据（用户 Skill/Tool） | delete/anonymize | — | 即时 | 否 |
| SandboxRun | delete/anonymize | 运行记录去标识 | 即时/去标识 | 否 |
| DSR 记录 | retain | 数据权利请求合规审计 | 法定期限 | 是 |
| AuditEvent | retain | 审计哈希链不可删除 | 永久（法律要求） | 是 |

- **API 返回删除结果摘要**：`POST /v1/data-subject-requests/{id}/complete` 对 delete 请求返回
  `{"per_category": { "<category>": {"action": "delete|anonymize|retain", "count": N, "retained_reason": "..." } }, "summary": "..."}`。
- **UI 不得承诺超过实际能力的"全部删除"**：数据权利页必须展示矩阵摘要（哪些已删除、哪些依法保留及原因），不得使用"全部删除/彻底删除"等绝对化表述。
- delete 执行幂等；`revoke_service` 与 `delete` 语义分开（撤回服务 ≠ 数据删除）。

### 【验收标准】

1. **API 测试**：delete DSR 完成后，响应含矩阵中全部 15 类的 `per_category` 摘要；DerivedFeature/DailyNarrative/UserProfile/个性化 Skill/Tool 计数正确删除；Consent/RiskSignal/Escalation/AuditEvent/DSR 保留且 `retained_reason` 非空。
2. **数据测试**：删除后该用户 `GET /v1/narratives`、`GET /v1/profile/{id}`、legacy journal/checkin 查询为空或 404；RiskSignal/Escalation/AuditEvent 仍可被工作台追溯。
3. **幂等测试**：同一 DSR 重复 complete 不报错、结果一致。
4. **权限测试**：普通用户仅能请求删除自己数据；admin 完成操作写入审计。
5. **UI 测试**：数据权利页展示矩阵摘要与依法保留说明；文案扫描不含"全部删除/彻底删除"字样。

---

## 8. 契约修正点 7：Android 信息架构（P1）

### 【现状问题】

1. 底部导航 5 Tab：今天/能力/练习/趋势/支持（`EchoMindApp.kt` `Tab` 枚举）。
2. `PracticeScreen` 仅为骨架提示："练习打卡已改为能力卡片驱动，请在「能力」标签查看下发的 Skill。"——"练习"Tab 无真实内容。

### 【新契约定义】

- **底部导航聚焦 4 Tab：今天 / 能力 / 趋势 / 支持**（移除"练习"独立 Tab）。
- 若确需保留"练习"概念，必须改造成**"我的练习 / 能力记录"**，展示：进行中、已完成、已停止、收藏、最近使用（数据来自 Skill 执行记录本地表），不得只显示"请去能力页"提示。
- 推荐方案：删除独立"练习"Tab；"我的记录"作为「能力」页内的分段子区。

### 【验收标准】

1. **UI 测试**：`NavigationBar` 仅含 今天/能力/趋势/支持 四项（枚举断言）；不存在仅含提示文案的空 Tab。
2. **导航测试**：四个 Tab 各自可达且渲染正确页面；紧急 FAB 逻辑（`shouldShowEmergencyFab`）保留。
3. **改造测试**（若保留练习概念）："我的练习"展示进行中/已完成/已停止/收藏/最近使用五类视图，数据与本地 SkillRun 记录一致。

---

## 9. 契约修正点 8：趋势页 UX 状态契约（P1）

### 【现状问题】

1. `TrendScreen` 仅区分"画像加载中…"与"需要至少两天…"两态；`fetchNarratives` 逐日拉取、失败即跳过该日，API 失败会被伪装成"无数据/observation_days=0"。
2. 无 loading / offline cached / fresh / partial / no data / error / permission disabled 七态。
3. 图无日期轴、无数据覆盖度、无 missing window、无基线稳定性、无最近同步时间。
4. 存在伪精确"情绪评分折线"（与契约点 2 联动删除）。

### 【新契约定义】

- **趋势页七态**：`loading / offline_cached / fresh / partial / no_data / error / permission_disabled`。
- **API 失败不得伪装成 `observation_days=0`**：网络/服务端错误 → `error` 态（区别于真无数据的 `no_data` 态）。
- **图要素必须包含**：日期轴、数据覆盖度、missing window 标注、基线稳定性、最近同步时间。
- **删除伪精确"情绪评分折线"**，改展示行为派生指标（活动节律/屏幕互动/覆盖度）与契约点 2 免责文案。
- `permission_disabled` 态在被动感知关闭/权限被撤时显示（联动契约点 3）。

### 【验收标准】

1. **单元测试**：`TrendUiState` 状态机纯函数覆盖七态及转换；API 异常 → `error`；数据为空 → `no_data`；`observation_days=0` 仅在真无数据时出现。
2. **UI 测试**：七态各有对应 UI 文案/组件（暴露 internal 常量断言）；`permission_disabled` 在感知关闭时显示。
3. **图测试**：数据点含日期轴/覆盖度/missing window/基线稳定性/最近同步时间；不存在"情绪评分"折线。
4. **免责文案测试**：趋势页含契约点 2 固定免责文案。

---

## 10. 契约修正点 9：同步 UX 状态契约（P1）

### 【现状问题】

1. UI 仅展示"待同步 N 项"chip + "立即同步"按钮（`TodayScreen`/`SupportScreen`），无状态语义。
2. `SyncWorker` 内部区分 401/403/412/422 → failure（终态）、500 → retry、网络异常 → retry，但未映射为可展示的用户状态与文案。
3. 无"数据仍安全保存在本机""等待网络恢复""登录已失效""被动感知已关闭""旧版数据无需再上传"等文案契约。

### 【新契约定义】

- **统一 `SyncState` 枚举**：`synced / pending(count) / syncing / offline / retrying / blocked_by_auth / blocked_by_consent / failed_terminal`。
- **文案映射契约**：
  | SyncState | 用户文案 |
  |---|---|
  | synced | 已同步 |
  | pending(n) | n 项待上传 · 数据仍安全保存在本机 |
  | syncing | 正在同步 |
  | offline | 等待网络恢复 · 数据仍安全保存在本机 |
  | retrying | 同步遇到临时问题，将自动重试 |
  | blocked_by_auth | 登录已失效，请重新登录 |
  | blocked_by_consent | 被动感知已关闭，相关数据不再上传 |
  | failed_terminal | 部分数据无法上传（旧版数据无需再上传；如需帮助请联系机构支持） |
- **严禁把内部 HTTP status（401/403/412/422/5xx 等）暴露给普通用户**；内部码仅映射为上述用户态。
- 联动：consent 撤回后（契约点 3）本地应停止产生新特征；已存在的 pending `derived_feature` 不再上传（或标记 blocked_by_consent 并丢弃），但撤回 consent 事件本身必须上传。

### 【验收标准】

1. **单元测试**：`SyncResult → SyncState` 映射纯函数覆盖全分支：2xx/409→synced 或 pending；网络异常→offline/retrying；401/403→blocked_by_auth；412→blocked_by_consent；5xx→retrying；其余→failed_terminal。
2. **UI 测试**：Today/Support 页按当前 SyncState 渲染对应文案；文案扫描不含"HTTP""401""412""500"等字样。
3. **集成测试**：断网 → `offline` 文案；恢复网络 → `syncing → synced/pending`；token 失效 → `blocked_by_auth`；consent 撤回 → `blocked_by_consent` 且撤回事件仍上传。
4. **一致性测试**：UI 展示的 pending 数量与 outbox 表真实行数一致。

---

## 11. 优先级与依赖汇总

| 优先级 | 契约点 | 依赖 |
|---|---|---|
| P0 | 1 被动 Safety | 独立 |
| P0 | 2 情绪映射 | 与 1、8 联动 |
| P0 | 3 consent 中心 | 与 9 联动 |
| P0 | 4 Skill declarative | 与 5 联动（执行契约字段纳入 Skill 内容与重签） |
| P0 | 5 Skill 治理状态机 | 与 4 联动（signed 门禁、重签校验） |
| P0 | 6 DSR 矩阵 | 独立（模型/路由扩展） |
| P1 | 7 信息架构 | 与 3、8、9 联动（Tab 精简后各页状态契约） |
| P1 | 8 趋势七态 | 依赖 2 |
| P1 | 9 同步状态 | 依赖 3 |

建议实施顺序：1 → 2 → 4/5 → 3 → 6 → 8/9 → 7（契约点 1、2 为安全语义修正，优先落地并随附回归测试）。

## 12. 开放问题

1. `RiskSignal` 从 delete 改为 retain 的合规理由需法务/临床确认（契约点 6 按保留实现，但 period 需定稿）。
2. `action_type` 白名单枚举需临床/内容负责人签署（契约点 4）。
3. Skill 卡片 WebView → Compose 改造的最终结论需架构师基于性能/可访问性评估输出（契约点 4），PRD 默认倾向改造。
4. 契约点 5 的"内容变化自动回 draft"对沙箱自动归纳链路（skill_induct.py 幂等复用）的影响需架构评审。
5. 契约点 3 的 Notification access / App usage access 状态读取需系统 API 能力确认（Android 12+ 隐私限制）。

## 13. 影响面清单（供架构师排期）

| 层 | 涉及文件（现状） |
|---|---|
| Backend 模型 | models.py（Skill 新字段、DailyNarrative.mood_hint 废弃、DSR 结果字段） |
| Backend 路由 | routes.py（ingest 移除 passive_red、skills 门禁/etag、transition 重签、DSR complete 摘要、skills/revoked） |
| Backend 服务 | safety.py（evaluate_passive 移除）、profile.py（mood_hint 移除）、trends.py（行为指标化） |
| Backend 迁移 | alembic versions（Skill 新列、narrative 字段变更） |
| Android UI | EchoMindApp.kt（4 Tab）、OtherScreens.kt（Trend/Support 重构）、SkillCardHost.kt（Compose 卡片+真实行为）、OnboardingScreen.kt |
| Android 数据 | LocalRepository.kt（fetchSkills 版本缓存、SyncState、TrendUiState）、SyncWorker.kt（状态映射）、PassiveSensingPrefs.kt、EchoDatabase.kt（buffer 清空、SkillRun 表） |
| Android 感知 | PassiveSensingService.kt（原子停止）、FeatureExtractor.kt（零新特征）、SafetyEngine.kt（evaluatePassive 移除） |
| 测试 | backend/tests/*、android/app/src/test/*（各契约点验收标准落地） |
