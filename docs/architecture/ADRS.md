# ADR —— 架构决策记录

> 状态：v1.0 · 持续追加 · 位置：`docs/architecture/ADRS.md`
> 规则：所有长期架构关键决策必须记录于此；每条包含「决策 / 理由 / 后果」。用户已授予全权自主裁决（2026-08 会话），历史产品问题 Q1–Q10 在此一次性裁决。

## ADR-001：ECHO 不是 LLM（为何）

- **决策**：ECHO 身份 = Observation Core + Baseline + Context Engine + EchoSelfModel + Memory + 用户纠错 + 隐私策略 + Reasoning Contracts + 视觉身份；LLM 只是 Compute Provider（`docs/providers/AI_PROVIDER_SPEC.md`）。
- **理由**：换模型不失忆、不换人格是产品北星；把身份绑定到任何供应商 SDK 都会破坏个人连续性。
- **后果**：业务代码禁止直连供应商 SDK；全部经 `EchoReasoningProvider` 抽象。

## ADR-002：BYOM 是一等公民，secret 永远设备端（为何）

- **决策**：BYOM 默认链路 `Android Device → User-selected Provider`；API Key 用 Android Keystore 派生的 `FieldCipher` 加密存独立 SharedPreferences；ECHO server 默认不知道用户 Key。
- **理由**：社区版不能要求官方承担全部模型费用；设备直连消除 server 中转的 secret 暴露面。
- **后果**：`ProviderCredentialStore` 与个人数据逻辑隔离；错误文案永不含 secret；`allowBackup=false` 保证 Key 不进备份。

## ADR-003：Observed / Interpreted / Felt 三层分离（为何）

- **决策**：任何个人信息必须声明来源层级；Felt（用户自述）永远覆盖 Interpreted（AI 推断）；Interpreted 必须携带 confidence/evidence/source/timestamp/model-version。
- **理由**：`PORTRAIT_CONTRACT.md` 的 Observation 保护永久保留（Ground Truth Contract），新 AI 能力不绕过它。
- **后果**：`docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md` 冻结；CI 词表门禁（`containsBlockedVocabulary` + PRESENCE 禁令）。

## ADR-004：全系统只有一个 EchoPresenceState（为何）

- **决策**：`EchoStateStore` 是唯一状态流；Today/Wallpaper/Dream 都是只读消费者；`PresenceRepository` 是唯一组装方（分钟级）。
- **理由**：消灭「首页说运行、趋势说关闭、壁纸另一套状态」的信任错位。
- **后果**：状态更新率（分钟级）与渲染帧率（帧级）彻底解耦；Wallpaper 不运行 Intelligence pipeline。

## ADR-005：共享渲染器（纯帧模型 + 三种绘制适配）

- **决策**：`computeEchoSceneFrame()` 纯 Kotlin 输出 `EchoSceneFrame`（颜色/半径/粒子均为纯数据）；Compose DrawScope、android.graphics.Canvas（Wallpaper/Dream）共享同一帧模型。
- **理由**：确定性（同一 identity/day/state/time 可复现）是 Journey 视觉记忆的前提；帧模型 JVM 可测；未来可换 Shader/GPU 渲染后端。
- **后果**：`EchoVisualParameters` 12 维连续参数（禁止「焦虑=红」式映射）；低置信度 = 更弥散、更少结构。

## ADR-006：Wallpaper / Dream 只读持久化快照（不初始化业务容器）

- **决策**：`EchoPresenceCodec` 把最近一版状态快照落盘（SharedPreferences）；两个系统服务只读该快照渲染；不触碰 `AppContainer`（lazy + Keystore fail-closed）。
- **理由**：壁纸进程启动不该级联 Room/Keystore 初始化（重量级、fail-closed 可能抛错）；快照不含叙事文字 → 锁屏 Public Safe 由构造保证。
- **后果**：快照新鲜度 = 最近一次 App 内刷新；后台定期刷新 Presence 进入 backlog（WORK-ERA3-1）。

## ADR-007：苏醒瞬间由 consent CTA 触发（活动识别延后）

- **决策**：核心感知 = 传感器 + 屏幕（均无需系统权限）；「让 ECHO 开始了解我」CTA 即触发 Awakening（写入 `awakenedAtEpochMs`）。ACTIVITY_RECOGNITION 不加入核心（当前特征链没有活动识别消费端）。
- **理由**：方案 v1.0 的示例核心权限是活动识别，但代码未实现该权限与特征；为它加权限却无下游消费是空转。待 Feature Engine 有对应槽位后再评估。
- **后果**：Awakening 是产品瞬间而非系统权限瞬间；USAGE/NOTIFICATION/MIC 全部移出 onboarding（Enhancement/Sensitive Optional）。

## ADR-008：Ambient 词对用户不可见；锁屏默认视觉-only

- **决策**：QUIET/ACTIVE/DENSE/SLOW/LATE/TRANSITION/UNKNOWN 是机器内部态，只驱动视觉参数；锁屏/壁纸不渲染任何文字（Dream 只显示 ECHO 字标 + 时间 + 日期）；「安静/活跃」等词不回锁屏（保留 Phase 6.3 REWRITE 词表裁决）。
- **理由**：机器词表与用户语言分离是信任与合规的双保险；「今天比较安静」与 Phase 6.3 禁令冲突，不回滚该禁令。
- **后果**：`publicNarrative` 生成器（ERA 5+）必须过白名单门禁才能上锁屏。

## ADR-009：成熟度映射数据驱动（baselineDays），不动基线算法

- **决策**：`echoMaturity(baselineDays)`：0→SEED、1-2→DISCOVERING、3-6→EMERGING、7-27→KNOWN、≥28→MATURE；后端冷启动三态与端侧基线算法零改动。
- **理由**：Day0 独立成 SEED 只需客户端映射，避免契约与算法返工；MATURE 用完整 28 天窗口作为「这是我的 ECHO」门槛。
- **后果**：老用户无 awakenedAt 时 SEED 页不显示「已观察 N 分钟」（不伪造观察时长）。

## ADR-010：订阅重构延后到产品评审（不在本轮工程裁决）

- **决策**：能力练习 402 门禁、单档订阅矩阵保持现状；本轮不触碰订阅边界（属商业决策，非工程决策）。
- **理由**：决策顺序「User control → Trust → Data truth → …」中订阅是商业化问题；在无产品定档矩阵时改动门禁会制造收入预期漂移。
- **后果**：文档化于 `ECHO_ARCHITECTURE_MAP.md` C17 / IMPLEMENTATION_STATUS 债务清单；ERA 9 前需产品裁决。

## ADR-011：Identity 色相限制在 [0.45, 0.75]（青蓝→紫）

- **决策**：Identity Genome 的色相经 Knuth 乘法散列展开到 0.45-0.75 区间，数月恒定、不随状态变化。
- **理由**：色相属于身份（稳定）而非状态（变化），因此不构成情绪色彩联想；区间约束避免与常见情绪色码（红=愤怒等）撞车。
- **后果**：不同用户 ECHO 有色相差异；同一用户不同日子保持视觉血缘。

## ADR-012：Context Compiler 是 LLM 请求的唯一通道（最小上下文）

- **决策**：所有推理经 `EchoContextCompiler`：Task → ContextPolicy（allowed/prohibited/maxEvidenceItems）→ 剔除禁止数据 → 截断 → 编译最小上下文；模型默认无数据库访问权。
- **理由**：Personal AI 价值来自 Relevant context，不是 Maximum context；隐私预算（PRIVACY_BUDGET）按任务计价，原始通知/音频/麦克风特征永不进入任何任务（CI 断言）。
- **后果**：`ReasoningTasks.kt` 十任务策略表冻结；新增任务必须带 policy；「依据」UI 直接用 compiled.usedSources。

## ADR-013：Memory 生命周期规则（不是什么都值得记，也不是什么都永远在）

- **决策**：七类记忆 × 四档保留期（EPHEMERAL 7d / SHORT 30d / LONG 365d / USER_PINNED 永续）；自动过期=软删（审计保留）；确认=强化（顺延 + 重要度 +10 封顶 100）；检索按衰减分排序。
- **理由**：契约要求 decay/reinforcement/expiry/delete/user edit；软删保证「可审计 + 可物理删除（数据权利）」。
- **后果**：Room v9 新增 echo_memories（迁移 8→9 纯建表）；Correction Memory 从画像反馈「不太像 + 原因」写入（confidence=1，用户自述最高优先）。

## ADR-014：Ask ECHO 是对话层，不是聊天机器人（不存跨会话历史）

- **决策**：对话从 ECHO Scene 展开（ECHO 保持可见）；回答必须经 Context Compiler（个人时间上下文）+ 依据展示；会话仅存内存，**不持久化**（Memory ≠ 聊天记录，契约 §5）。
- **理由**：产品核心是「它知道我的时间上下文」；存聊天历史会把 EchoMemory 变成 ChatGPT 式档案，破坏记忆契约与隐私边界。
- **后果**：跨会话对话历史进入 backlog（若产品要求，需先过隐私评审 + 独立删除路径）。

## ADR-015：AI 叙事门禁 = 画像词表 + 监控语言 + 长度上限（不合格即降级，不重试）

- **决策**：AI 输出必须过 `StructuredOutputValidator`：JSON 校验/修复 + BLOCK 词表（对 AI 同样生效）+ 监控语言（检测/监测）+ ≤200 字；任一失败 → 确定性叙事 → 观察事实 fallback 链。
- **理由**：AI 不是契约豁免区；「AI 说的」不能成为越界文案的通道。无限重试是浪费且放大风险，fail-fast 降级更可信。
- **后果**：`AiNarrativeService` 三层 fallback；用户永远不面对空白，也永远看不到未过门禁的 AI 输出。

## ADR-016：Journey 视觉 = 确定性 CANONICAL_SNAPSHOT（不 AI 生图）

- **决策**：每天的视觉记忆单元 = `journeyDayParams(portrait)` 确定性映射 + 固定时间点 `computeEchoSceneFrame`；周/月 = 参数平均聚合；代表日 = SIMILAR 维度数最多。图表降级为「查看依据」Evidence Layer。
- **理由**：Determinism 是「看到自己的时间」的前提（同一天永远同一帧）；确定性渲染零成本、可离线、可测试；Master Prompt PART 93 明确 Visual Snapshot 不需要 AI 重新生图。
- **后果**：`journey/JourneyVisuals.kt` 纯函数 + golden 断言；Journey tab 标签从「趋势」改为「旅程」（三世界：ECHO/Journey/Me）。

## ADR-017：基础行动免费且由 Scene 自己执行（订阅能力分区展示）

- **决策**：呼吸/暂停/什么也不做是本地免费行动，覆盖层内 ECHO 生命场自己执行（8s 呼吸周期、60s 时长）；订阅 Skill 卡片在「更多能力（订阅）」分区之下，不与免费行动混排。Intervention 分级 L0-L3：低置信/未知状态 → L0；L3 主动通知需 opt-in + 置信 ≥0.85 + ≥7 天间隔。
- **理由**：产品宪法「基本行动能力免费」「默认安静存在」；行动不跳转不同视觉风格的页面（Interaction Grammar）。
- **后果**：`actions/InterventionPolicy.kt` 纯函数冻结；L2 应用内建议已接线（opt-in 开关默认开，低置信不出现）。

## ADR-018：感知自愈 = 看门狗 Worker + 服务自身 fail-closed 门控（最终闭环）

- **决策**：`SensingWatchdogWorker` 每 15 分钟做「是否值得尝试重启」的纯函数决策（consent 关 → 绝不重启；服务死或心跳过期 → 尝试重启）；真正门控仍在 `PassiveSensingService`（flag+consent+传感器，fail-closed）。
- **理由**：SYSTEM_PAUSED 必须能被自动恢复，而不是永远靠用户手动重开；Doze 停传感器无法被 App 强行解除（不给电池豁免，见 ADR Q6 裁决），因此看门狗是「尽力自愈 + 诚实呈现」而非「永不死亡」。
- **后果**：六态闭环完成——检测（心跳）+ 决策（纯函数）+ 自愈（幂等重启）+ 呈现（SYSTEM_PAUSED 文案）。

## ADR-019：Presence 后台刷新 = 15 分钟 Worker（快照新鲜度与记忆维护合一）

- **决策**：`PresenceRefreshWorker` 每 15 分钟组装 EchoPresenceState（纯端侧、无上行）刷新 Wallpaper/Dream 快照，并顺带执行记忆自动过期清理。
- **理由**：Presence 是长期系统功能；用户一周不打开 App，壁纸也应反映最近状态。15 分钟粒度 = 感知 5 分钟窗口与「低频更新」原则的折中。
- **后果**：WORK-ERA3-1 关闭；快照新鲜度上限 = 15 分钟 + Doze 调度延迟。

## ADR-020：最终产品边界裁决（v0.8.0 收口）

- **决策**：
  1. **订阅**：v0.8.0 保持单档 standard（云端同步/长周期分析/专业支持/能力练习）；基础行动免费已由 Scene 内行动集承担。多档矩阵属商业化发布决策，不进入本产品版本。
  2. **对话历史**：Ask ECHO 会话不持久化（Memory ≠ 聊天记录），维持 ADR-014；如需产品化需独立隐私评审与删除路径。
  3. **ERA 11 多设备**：Android 手机是 v1 产品形态；EchoSelfModel/EchoPresenceState 已单源化（跨设备共享的架构基础），Watch/Tablet/Desktop 属下一产品周期，不在 v0.8.0 范围。
- **理由**：用户要求交付最终成品、不留预留事项——把「未决」转为「已决」：以上三项均为有边界的最终决策，而非悬而未决的工程债。
- **后果**：IMPLEMENTATION_STATUS 的「预留事项」清单清零。

## ADR-021：一级导航收敛为三世界（ECHO / Journey / Me）+ EchoRuntimeCoordinator 统一运行时（v2 应用壳接管）

- **决策**：
  1. 一级导航从「今天/能力/旅程/我的」收敛为 **ECHO / Journey / Me**；「能力」Tab 删除——基础行动在 ECHO Scene 内执行，订阅能力在 Scene「更多能力（订阅）」分区展开；危机入口（紧急 FAB）常驻不因 IA 精简而隐藏。
  2. `EchoRuntimeCoordinator` 成为运行时唯一协调者：以系统真实权限计算六态、触发 Presence 组装、广播 Provider 轻量状态；UI 不再各自拼状态。
  3. `EchoSceneUiState` + `assembleEchoSceneUiState` 纯函数：一句话 fallback 链、Why 三层（一句话 → Scene 内 facts → Journey 证据）、L2 建议门槛全部集中在装配层，UI 只渲染。
  4. Legacy 处置：`SkillListScreen`（旧「能力」Tab 全页）无调用方无测试 → **删除**；`rememberSkillList/coldStartHint/SkillCardHost` 保留（Scene 订阅分区复用）。
- **理由**：v2 的核心判断——新内核已长出但旧壳仍在；接管必须发生在一级入口，而不是继续堆底层。职责优先于文件名；不为框架而框架（不引入 ViewModel/DI 依赖，用纯函数装配器 + 协调器）。
- **后果**：`EchoSceneScreen.kt`（原 TodayScreen 重构）成为 ECHO 世界主路径；`TrendScreen`（旅程）与 `SupportScreen`（Me）保持文件结构；物理模块化延后到 domain boundary 完全清晰之后（v2 §80）。

## ADR-022：Context Retriever 是 Context Compiler 的真实数据检索层（v2 §42）

- **决策**：`EchoContextRetriever` 按任务策略（timeWindowDays + allowedMemoryTypes）从端侧画像引擎/时间线/记忆**实际检索**证据；检索失败 → 空证据 + fallback 链（不抛异常）；EvidenceAssembler 只做脱敏映射。
- **理由**：策略表（ReasoningTasks）若无真实检索实现就是摆设；端侧检索保证离线可用、数据最小化（原始通知/音频/麦克风永不出现在检索层）。
- **后果**：EchoSceneScreen / TrendScreen 不再手工拼 EvidenceItem；新任务接入 = 策略 + 检索器自动生效。

## ADR-023：Provider 测试连接四步 + 对话依据/反馈闭环（v2 §39/§51/§52 最终收口）

- **决策**：
  1. 「测试连接」= 认证（GET /models）→ 模型可用性（/models 含配置模型；不暴露列表则「无法确认」而非失败）→ 结构化输出（json_object 最小探测，**不含任何个人数据**）→ 基础请求；结论 overall 纯函数映射人话。
  2. Ask ECHO 每条回答附「依据」双清单（参考了哪些数据源 + 没有使用：麦克风/通知正文/精确位置）；每条回答可反馈「像我/不太像 + 原因」→ Correction Memory。
  3. Journey 全尺度天/周/月/季/年（backend 画像窗口 90 → 365，openapi 重新导出）。
- **理由**：可信 AI 的最后一公里 = 可测试（Provider）+ 可追溯（依据）+ 可纠正（反馈）；「无法确认」与「失败」必须诚实区分。
- **后果**：v2 第一/二轮全部闭环；IMPLEMENTATION_STATUS 预留事项清零，产品进入纯优化周期。

## ADR-024：ERA 12 Consolidation 收口（ECHO 成为唯一产品架构）

- **决策**：
  1. **ECHO Scene 拆解**：EchoSceneViewModel（Composable→ViewModel→Coordinator→Repository 分层，viewmodel-compose 既有依赖）+ 组件化（VisualSurface/StatusOverlay/WhyLayer/ConversationLayer/ActionLayer）+ 三独立运行时（EchoConversationController 状态机 / EchoCorrectionService / EchoActionRuntime）；Screen 只组合。
  2. **Journey/Me 正式迁移**：`ui/journey/JourneyScreen.kt` + `ui/me/MeScreen.kt`（六子领域：Subscription/Support/DataAndSensing/Presence/Intelligence/WhatEchoKnows）；旧 TrendScreen 变 @Deprecated 委托、SupportScreen 删除（测试锚点函数迁 `ui/MeSupportHelpers.kt`）。
  3. **AppContainer 拆权**：Core/Sensing/Observation/Presence/Intelligence/Memory 子容器（组合式，不引入 DI 框架——v3 §42 不为框架而框架）。
  4. **依赖方向修正**：渲染器归位 presence 包（presence 不再依赖 ui）；`ArchitectureBoundaryTest` 5 项断言永久边界（presence 不依赖 Room/ui、observation 不依赖 intelligence、memory 不依赖 Provider、intelligence 不依赖 ui）。
  5. **文档治理**：`README_AUTHORITY.md` 读取优先级 + CURRENT/SUPERSEDED/ARCHIVED 生命周期；旧规格标注 SUPERSEDED。
  6. **EchoRuntimeHealth**：组件级统一状态（OK/DEGRADED/UNAVAILABLE），UI 不拼 Boolean。
- **理由**：v3 阶段判断——新内核已长出，任务是让新 ECHO 正式成为唯一产品架构并系统性清理结构债务（Risk A-F）。
- **后果**：ERA 12 完成；ERA 13（Gradle 物理模块化）前置条件（依赖图清晰 + 边界测试）就绪；SupportScreen/TrendScreen 旧命名退出主路径。







## ADR-025：ERA 12.8 Final Distribution Closure（Git = Manifest = Archive = Provenance = Package 同一 Gate）

- **决策**：
  1. **EchoRuntimeCoordinator 入库**：修复 `.gitignore` 裸 `runtime/` 规则（改为根锚定 `/runtime/`）——runtime 包（145 行正式实现：sensing 六态编排 / presence 刷新 / EchoRuntimeHealth 五态 / provider 状态广播）此前只存在于工作树、从未进入 git，clean checkout 必漏包。禁止以空 stub 凑数。
  2. **SOURCE_MANIFEST = git 受控文件集**（`git ls-files` 枚举 + 既有排除规则），不再扫描文件系统——未受控文件物理上不可能进入清单。
  3. **确定性 Source Archive**：`build_source_archive.py`（Python zipfile/tarfile；路径一律 NFC；ZIP 非 ASCII 条目显式置 UTF-8 标志 0x800——修复中文路径 #Uxxxx/乱码根因；时间戳 = HEAD commit time；同 commit 字节级可复现；内嵌 SOURCE_MANIFEST 自证）。禁在旧 ZIP 上覆盖、禁手工拼 dirty worktree。
  4. **Final Archive Verification Gate**：`verify_source_archive.py`（安全解包 → 内嵌清单双向复核 → NFC/UTF-8 标志校验 → required sources → 禁止目录/后缀检查）+ `test_source_archive.py` 10 用例负例矩阵（中文/emoji/空格/长路径 fixture、确定性、hash 篡改、意外文件、缺失 runtime、NFD、缺 UTF-8 标志）。
  5. **Provenance schema v2**：root APK 直接绑定（`release_apk_path/release_apk_sha256`）+ `unsigned_apk_sha256` + `signing_stage`/`signature_scheme`（apksigner --verbose 解析，不记录 key 材料）+ jdk/gradle 真实探测 + `source_archive_sha256`。
  6. **Artifact Manifest 只描述最终交付物**：去除 build 目录 debug/androidTest 临时 APK；根 APK 缺失时以 unsigned release APK 为 dev 交付物。
  7. **Final Release Package**：`build_final_package.py` + `verify_final_package.py`（包内 manifest 双向复核 + provenance 交叉绑定 + 包内 source archive 递归验证）。
  8. **CI**：source-integrity 扩展为完整 archive gate；新增 `release-closure.yml`（§17 原子流程：clean checkout → verify → backend → Android test/lint/detekt → release build → sign(secrets) → SBOM → metadata → archive → verify → package → final verify；`--require-clean` 禁 dirty release）。
  9. **根目录 APK/idsig 移出 git**（交付物而非源码；`.gitignore` 泛化 `ECHO_Mind_v*.apk*`）。
- **理由**：上一轮 source-integrity 只验证 git checkout，最终分发 ZIP 与 checkout 不是同一道 Gate；manifest 描述打包前 worktree 而非最终 ZIP。
- **后果**：Git source = SOURCE_MANIFEST = source ZIP/tar.gz = extracted verified source = tested/built source = signed APK = BUILD_PROVENANCE = RELEASE_ARTIFACT_MANIFEST = final package（clean checkout 实测全 PASS）；ERA 12.8 完成后进入 ERA 12.9（文档/状态真值）→ ERA 13（Journey Application Layer）。

## ADR-026：ERA 13 Journey Application Layer（Screen → ViewModel → Application Service → 数据实现）

- **决策**：
  1. **JourneyScreen 去编排**（555 → 293 行）：Screen 只保留 Scale selector / Visual Memory River / Narrative / Evidence；删除 7 个直接依赖（Portrait/SyncState/FeatureFlag/Memory Repository、AiNarrativeService、EchoContextRetriever、AppPreferences）与全部业务 LaunchedEffect；Evidence Layer（图表/综述）拆至 `ui/journey/JourneyEvidenceView.kt`（142 行）。
  2. **JourneyViewModel**（168 行，AndroidViewModel + viewModelFactory）：唯一业务持有者；`uiState = StateFlow<JourneyUiState>`（combine 装配）；事件面 `JourneyEvent`（SelectScale/Refresh/ToggleEvidence/AskAboutPeriod/RetryNarrative；SelectDay/SelectPeriod 属 ERA 16 周期选择交互，不预置死事件）。
  3. **JourneyUiState**（§24 全字段）：selectedScale/timeline/selectedPeriod/visualDays+visualPeriods（预装配视觉记忆）/narrative/evidence/exceptions（上下文例外）/intelligenceAvailability/syncStatus/loading/error/journeySeed；纯函数装配器 `assembleJourneyUiState`（与 EchoSceneUiState 同模式）。
  4. **JourneyRepository（应用服务）+ JourneyPort（数据端口）**：timeline/refresh/runtimeSnapshot/narrativeFor（FIND_LONGITUDINAL_PATTERN 证据检索 + AI 叙事 → 确定性综述 fallback + CONTEXT_EXCEPTIONS 抽取）/feedback/journeySeed；Feature Flag 经 ViewModel 注入 UI state（§28），Screen 不再直读。
  5. **七态纯逻辑迁入 journey 包**：TrendUiState/TrendNoDataReason/resolveTrendState/resolveTrendNoDataReason/coveragePercent 等自 ui/JourneyState.kt 迁至 journey/JourneyTrendState.kt（ui/JourneyState.kt 仅留 TREND_DISCLAIMER + 系统设置 intents + formatTimestamp，formatTimestamp 因 Me/DataAndSensing 共用而保留在 ui）。
  6. **边界断言**：ArchitectureBoundaryTest 新增 `journey 应用层不依赖 ui`（方向恒为 ui → journey）。
  7. **测试（§102 矩阵）**：JourneyUiStateAssemblyTest 8 用例（empty/partial/7d/28d/90d/365d/missing days/context exceptions/AI unavailable/seed 稳定）+ JourneyViewModelTest 4 用例（fake JourneyPort；Robolectric sdk=35）。
- **理由**：Journey 是当前最大 UI/Application debt（555 行 Screen 直接编排 7 依赖）；§23-§30 要求 Screen 1–2 分钟可读。
- **后果**：Journey Application Layer 完成（§110：Screen 不再直接 orchestrate repositories）；ERA 13.1（Me Application Layer）开始；JourneyPort 成为 ERA 13.2 全局 ports 的先行样本。

## ADR-027：ERA 13.1 Me Application Layer（五 ViewModel 收口）

- **决策**：
  1. **MeScreen 去编排**（326 → 124 行）：删除全部 Repository/Worker/权限/麦克风/感知生命周期编排与 LaunchedEffect；根页面只组合七卡（Crisis/Subscription/Support/Data&Sensing/Presence/Intelligence/WhatEchoKnows/About）+ 支持请求确认对话框。
  2. **MeViewModel**（§31/§32）：根页面摘要（Presence/Intelligence/Memory/Sensing/Support）+ 人工支持请求流（本地模式/订阅到期前置检查 → requestHumanSupport → SyncWorker）。
  3. **DataAndSensingViewModel**（§33）：permission truth（系统真实能力状态）、sensing pause/resume、mic opt-in（二次确认对话框状态 + 权限结果闭环）、usage access/notification listener 恢复、每晚提醒、数据权利（本地导出经 SharedFlow 一次性事件 / 删除 / 撤回同意）、本机计数、last active。
  4. **IntelligenceSettingsViewModel**（§34）：provider config 草稿 / 测试连接 / 保存并连接（READY 才落盘）/ 断开。
  5. **PresenceSettingsViewModel**（§35）：动态程度 / 夜间模式 / 减少动画 / 应用内建议（壁纸/屏保系统 intent 属 UI 平台职责）。
  6. **MemoryManagementViewModel**（§36）：confirm/edit/forget + 类别过滤（全部/纠正/已确认/我告诉你的）；pin 属 ERA 15.5（EchoMemory 无 pinned 字段，不预置死事件）。
  7. **纯函数装配器**：assembleMeUiState / assembleDataAndSensingUiState（分组输入类，detekt LongParameterList 合规）+ combine7/combine8 助手（kotlinx combine 上限 5 参）。
  8. **测试（§103 矩阵）**：MeStateAssemblyTest 7 用例（provider/permission/sensing/mic/memory/data rights/support/presence 摘要 + 记忆过滤）。
  9. **工具链修复**：SourceIntegrityTest / generate_source_reality 声明扫描支持泛型顶层函数（fun <A,...> combine7）。
- **理由**：MeScreen 是最后一个直接编排共享状态与多 Repository 的根页面（§31-§36）。
- **后果**：Me Application Layer 完成（§110：Screen 不再直接 orchestrate repositories）；ERA 13.2 Domain Ports 开始（Data package → Adapter）。

## ADR-028：ERA 13.2 Domain Ports（Data package 成为 Adapter）

- **决策**：
  1. **新 ports 包**（`com.yunjue.echo.mind.ports`）：
     - Observation Ports（§38）：ObservationEvidenceSource / CurrentPortraitSource / PortraitHistorySource / BaselineSource；
     - Memory Ports（§39）：EchoMemoryReader / EchoMemoryWriter / CorrectionMemoryWriter（默认参数只在端口声明，实现不得重设）；
     - Presence Ports（§40）：PresenceStateSource / PresenceStateWriter / PresenceSnapshotStore；
     - Actions Ports（§41）：**暂不建 ActionContentSource**——Actions 当前不消费 Skills（EchoActionRuntime 为呼吸/暂停），不预置死端口；订阅能力需要技能内容时再建。
  2. **Data Adapter 化（§42）**：LocalPortraitDataSource → ObservationEvidenceSource；PortraitRepository → CurrentPortraitSource+PortraitHistorySource+BaselineSource；MemoryRepository → 三记忆端口；PresenceRepository → PresenceStateSource+Writer；EchoStateStore → PresenceSnapshotStore。
  3. **Intelligence 依赖清理（§37）**：EchoContextRetriever 改为（ObservationEvidenceSource, EchoMemoryReader, userId 函数），不再 import LocalPortraitDataSource/MemoryRepository；EchoCorrectionService 改为（EchoMemoryWriter, CorrectionMemoryWriter）。
  4. **Ground Truth 断环**：sensing/PassiveSensingService 的 SyncWorker 调用经根级缝隙 `SyncEnqueue.kt`（root = composition root 允许接 data）；消除 observation→data 边。
  5. **边界断言**：ArchitectureBoundaryTest 新增 intelligence 不依赖 data 实现、observation（sensing/localportrait/model）不依赖 data 实现。
  6. **真实依赖图（§48）**：新增 `scripts/generate_dependency_graph.py`（package/import 分析 + 循环检测；ports 契约层豁免；model 并入 Ground Truth 聚合）→ 生成 ANDROID_DEPENDENCY_GRAPH.md（11 域 53 边，无循环）；CI source-integrity drift gate 强制同步。
- **理由**：Application Layer（Journey/Me）已收口；下一步是依赖方向：Domain interfaces ↑ data implementations。
- **后果**：intelligence/observation 与 data 实现解耦；依赖图成为物理模块化（ERA 13.5）输入；下一轮 ERA 13.3 Real DI Ownership（AppContainer 拆构造职责）。

## ADR-029：ERA 13.3 Real DI Ownership（子容器自持构造职责）

- **决策**：
  1. **AppContainer 缩减为 composition root（§43）**：只组合 CoreContainer/ObservationContainer/PresenceContainer/MemoryContainer/IntelligenceContainer/ActionContainer + 跨域编排（EchoRuntimeCoordinator/JourneyRepository/SkillSessionCoordinator）+ Transient 工厂；不再构造任何领域对象。
  2. **容器自持构造职责（§44）**：CoreContainer(context) 构建 cipher/preferences/database(fail-closed)/apiClient/outbox/四基础仓库；ObservationContainer(core) 构建感知/同意/画像 Ground Truth；PresenceContainer(core, obs)；MemoryContainer(core)；IntelligenceContainer(core, obs, mem) 内部构建 credential store/provider manager/narrative service/context retriever；ActionContainer(core) 持有 SkillRepository。
  3. **兼容访问器**：`container.preferences` 等为委托属性（所有权在子容器）；新代码走领域入口 `container.core.*`/`container.intelligence.*` 等；ArchitectureBoundaryTest 新增 `appContainerIsCompositionRootOnly`（禁止 Root 直接构造 17 类领域对象，防回归）。
  4. **生命周期所有权（§45）**：Application / Worker / Service / ViewModel / Transient 五类 scope 明文化（docs/architecture/DI_OWNERSHIP.md）。
  5. **DI Framework 裁决（§47）**：继续 structured manual DI（viewModelFactory + 领域容器）；不引入 Hilt——单模块阶段无等价收益，物理模块化后如出现跨模块注入需求再复评。
  6. **不制造 God DI 文件（§46）**：构造职责按领域分布在各容器文件；Root 无领域对象构造。
- **理由**：旧子容器只是「同一实例的分组暴露」（构造全部在 AppContainer），§43-§46 要求真正所有权。
- **后果**：AppContainer 从 185 行构造体降为纯组合 + 委托；依赖方向与模块化输入齐备 → ERA 13.5 物理 Gradle 模块化开始（每次一个模块）。

## ADR-030：ERA 13.5 Physical Gradle Modularization 第一批（:feature:actions + :core:security）

- **决策**（§50 每次只拆一个；本批两个，均为零依赖叶子模块）：
  1. **:feature:actions**：`com.yunjue.echo.mind.actions`（EchoActionRuntime/InterventionPolicy，2 文件，仅依赖 kotlinx-coroutines）——边界最清晰、零项目依赖；
  2. **:core:security**：`com.yunjue.echo.mind.security`（AndroidKeystoreFieldCipher/FieldCipher，2 文件，仅 Android 框架）——零项目依赖；
  3. 模块参数：com.android.library + kotlin-android + detekt（共享 android/detekt.yml）；namespace 保留原包名（模块边界 ≠ 包重命名）；:app 以 project() 依赖两个模块；
  4. 扫描器多根化：SourceIntegrityTest / ArchitectureBoundaryTest / generate_source_reality / generate_dependency_graph 登记模块源码根（新增模块在此登记，防止空扫假通过）；
  5. 下一批候选：:core:model（需先把 CapabilityState/SensingCapability 两个纯枚举从 sensing 迁入 model 消除 Ground Truth 内依赖）→ 之后 :feature:memory / :feature:observation。
- **理由**：ports + DI 已稳定（ERA 13.2/13.3）；依赖图无循环（11 域）；模块化从此开始收紧编译边界。
- **后果**：:app 无法再反向依赖动作/安全模块内部实现（编译器强制）；CI gradle 任务天然聚合多模块；后续模块按 move → compile → test → lint → fix → continue 逐次推进。

## ADR-031：ERA 13.5 第二批模块 :core:model（Ground Truth 词表 + 共享模型）

- **决策**（§50 每次一个；本批一个）：
  1. **枚举归位**：`SensingCapability` / `CapabilityState`（纯 Kotlin 枚举）自 `sensing/SensingCapabilities.kt` 迁入 `model/SensingCapability.kt`——消除 Ground Truth 内部 sensing↔model 依赖；`capabilityState()` 判定函数留在 sensing（Android 上下文判定）；
  2. **:core:model**：model/*（Models/PortraitCore/PortraitAvailability/SensingCapability，4 文件，零项目依赖，仅 java.time/UUID）；
  3. **编译器强制边界生效**：:core:model 的 `internal` 声明对 :app 不可见——按共享核心模块语义将 22 处 internal 声明改为 public（模块 API 面明文化）；
  4. 扫描器四件套登记 :core:model 模块根。
- **理由**：模型是横切词表（model 层），先拆它为后续 :feature:memory / :feature:observation 提供共享底。
- **后果**：依赖图 11 域保持无循环；下一批 :feature:memory（依赖 :core:model + ports 迁移）。

## ADR-032：ERA 13.5 第三批模块 :feature:memory（EchoMemory 领域模型）

- **决策**（§50 每次一个）：
  1. **:feature:memory**：`memory/EchoMemory.kt`（MemoryType/EchoMemory/RetentionClass/decay 纯函数，零项目依赖）迁入模块；
  2. **EchoCorrectionService 留在 :app**（同包 split-package）：它是应用层服务（消费 EchoMemoryWriter/CorrectionMemoryWriter 端口），依赖方向 app → feature:memory 而非反向；
  3. 依赖方向：:app → :feature:memory（+ :core:model/:core:security/:feature:actions）；memory 模块不依赖 Provider/intelligence（§51 边界由模块化进一步收紧）；
  4. 扫描器四件套登记 :feature:memory 模块根。
- **理由**：memory 是依赖最少的领域模型；split-package 是模块边界 ≠ 包重命名的自然结果（服务在应用层，模型在领域模块）。
- **后果**：下一批 :feature:observation（sensing + localportrait 迁入；需先处理 data 实现类对其的引用方向）。

## ADR-033：ERA 13.5 第四批模块 :feature:observation（Ground Truth 物理化）

- **决策**（§50 每次一个；本批一个，16 文件）：
  1. **:feature:observation**：sensing 纯逻辑（11 文件：Collectors×5 / FeatureExtractor / SensingCapabilities / SensingEventHub / SensingRuntimeStatus / SensingWatchdog / SensingWindowScheduler）+ localportrait 全量（5 文件：Ground Truth 画像引擎）；依赖仅 :core:model + kotlinx-coroutines + androidx.core；
  2. **平台组件留 :app**（split-package）：PassiveSensingService（Android Service，依赖 root AppPreferences/EchoMindApplication/PassiveSensingPrefs）+ MicCollector（依赖 root PassiveSensingPrefs）；
  3. **契约下沉**：`MicDerivedFeatureSource` 接口自 MicCollector 抽出置于 :feature:observation（SensingWindowScheduler 消费契约；MicCollector 为实现）——契约在领域模块、实现在应用层；
  4. **Ground Truth API 公开化**：11 文件 internal 声明改 public（localportrait 引擎/LocalPortraitDataSource 适配面、sensing 六态/能力判定为模块 API）；
  5. 扫描器四件套登记 :feature:observation 模块根。
- **理由**：observation 是 §51 边界核心（Ground Truth 独立）；物理化后 :app 无法再让 Ground Truth 反向依赖 data/intelligence。
- **后果**：:feature:observation 不含任何 data/intelligence/ui 依赖（编译器强制）；下一批 :feature:intelligence（依赖 :core:model + ports 迁移）。

## ADR-034：ERA 13.5 第五批模块（:feature:presence + :core:ports + :feature:intelligence 依赖链一次拆解）

- **决策**（§50 每次一个；本批因 ports 环依赖按依赖序三个连续拆解，各自 move→compile→fix）：
  1. **:feature:presence**：presence 纯域（EchoPresenceState/AmbientEngine/EchoPresenceCodec/EchoSceneRenderers/VisualProfile）；依赖 :feature:observation + compose（runtime/foundation/ui + BOM）；EchoStateStore 类自 EchoPresenceState.kt 抽出留 :app（进程级快照存储 = Application 基础设施，实现 ports.PresenceSnapshotStore）；EchoWallpaperService/EchoDreamService 留 :app（依赖 root AppPreferences 的平台服务）；
  2. **:core:ports**：三个端口文件迁入；依赖 :core:model + :feature:memory + :feature:presence（契约层引用领域词表，无实现循环）；
  3. **:feature:intelligence**：13 文件全迁；依赖 :core:model + :core:security + :core:ports + :feature:memory + coroutines（org.json/HttpURLConnection 为 Android SDK 提供）；
  4. 扫描器四件套登记三模块根；presence API internal → public（AmbientEngine/maturityOpenness/PRESENCE_COPY_*）。
- **理由**：ports 引用 presence 类型、intelligence 引用 ports——依赖序 presence → ports → intelligence 是唯一无环拆解顺序。
- **后果**：§51 边界物理化：intelligence 模块内无 ui/data 依赖（编译器强制）；模块版图 8/9（剩 :feature:journey）；ERA 13.5 完成后进入 ERA 14（ECHO Long-term Identity）。

## ADR-035：ERA 13.5 收官模块 :feature:journey（模块化全部完成）

- **决策**：
  1. **:feature:journey**：journey 域 6 文件（JourneyDomain/JourneyVisuals/JourneyTrendState/JourneyUiState/JourneyPort/JourneyEvent）；依赖 :core:model + :feature:presence + :feature:intelligence + coroutines；
  2. **JourneyRepository 留 :app**（split-package）：应用层服务（依赖 data 实现 + root AppPreferences + intelligence 服务），方向 app → feature:journey；
  3. **ERA 13.5 完成**：9 模块（:app + :core:model/:core:security/:core:ports + :feature:actions/:feature:observation/:feature:presence/:feature:intelligence/:feature:memory/:feature:journey）；§51 五条边界全部由编译器物理强制（observation 模块无 intelligence；presence 渲染无 Room；memory 无 Provider；intelligence 无 ui/data；feature 模块无 app 反向依赖）。
- **理由**：§49 推荐目标（:app + 3 core + 6 feature）全部落地；不机械执行——EchoStateStore/Wallpaper/Dream/Collector 平台组件按语义留 :app。
- **后果**：模块化 Era 完成 → ERA 14（ECHO Long-term Identity：IdentityGenome/LifeSeason/DailyComposition/MomentState 真实数据流）开始。

## ADR-036：ERA 14 ECHO Long-term Identity（§52-§62 第一轮：四层真实数据流）

- **决策**：
  1. **§52 审计结论**：EchoIdentityGenome 此前只有 seed+accentHue（其余维度缺失）；LifeSeason/DailyComposition/MomentState 从未填充；rhythmDelta=0f 为 placeholder——按 §52 判定为未实现，本轮实现。
  2. **IdentityGenome 七维（§53）**：seed/accentHue/colorFamily/textureFamily/coreTopology/symmetryTendency/orbitGeometry/motionPersonality；`deriveIdentityGenome`（installation random seed + 长期基线稳定性 + 视觉偏好混合 50/35/15）；**AppPreferences.identitySeed**（SecureRandom 一次性生成持久化，§54 禁 IMEI/Android ID/手机号/用户名 hash）。
  3. **LifeSeason（§56/§57）**：`computeLifeSeason` 由近 60 天画像时间线计算（phaseIndex/真实 drift + 五类中性描述：later-earlier/more_fragmented-concentrated/more_variable-regular/more_mobile-less_mobile/more_regular-less_regular）；词表硬禁医学/心理结论（测试断言）。
  4. **DailyComposition（§58）**：Identity + Ambient 向量确定性合成 12 维（一天内稳定）；**MomentState（§59）**：Ambient 向量 + 昼夜曲线（呼吸周期/噪声调制）。
  5. **平滑（§60）**：`smoothPresenceState`（四视觉层 + confidence 按 alpha=0.35 插值；运行时字段直取新值）；**rhythmDelta = lifeSeason.drift**（§61 placeholder 移除）。
  6. **EchoVisualMapper（§62）**：object 冻结映射链（EchoPresenceState → map → EchoVisualParameters → EchoSceneRenderer）；computeVisualParameters 消费四层（Identity 长效调制/Season 慢湍流/Daily 基座/Moment 呼吸周期），未填充层回退旧推导（Journey/旧快照兼容）。
  7. **测试（§106）**：EchoIdentityTest 7 用例（determinism/偏好塑形不重置/季节中性词表禁词/phase 桶/日构图范围/时刻调制/平滑无瞬切/多 Surface 一致性 + lock-safe 无 narrative）。
- **理由**：ECHO 已拥有 Presence，但尚无长期人格；§110 禁止「字段存在=实现完成」。
- **后果**：Day 1/30/180 同一 ECHO（identity 确定性）；渲染器不再自行推导身份；下一轮 ERA 14 续：Wallpaper 不可见连续渲染=0 基准（§65）+ Motion Language 冻结（§66）。

## ADR-037：ERA 14 续 —— §65 Wallpaper 零渲染硬指标 + §66 Motion Language 冻结

- **决策**：
  1. **§65 硬指标结构化**：渲染生命周期抽为纯状态机 `WallpaperRenderController`（feature:presence，可单测）；EchoWallpaperService 以其为唯一事实源——不可见 → renderActive=false → Choreographer 回调移除（0 帧率 0 CPU）；destroy 永久停止；不可见触摸不绘制。WallpaperRenderControllerTest 4 用例锚定。
  2. **设备实测基准契约**：docs/performance/PRESENCE_BENCHMARKS.md——CPU/GPU/frame time/memory/wakeups/battery 测量方式 + 基线目标；本仓库无真机/模拟器时**不伪造数字**，执行点为 CI connected-test（API 34/36 emulator）与真机矩阵。
  3. **§66 Motion Language 冻结**：docs/product/ECHO_MOTION_LANGUAGE.md v1——11 类语义（ambient/state transitions/unlock/touch/scroll/Why/Conversation/Action/Dream/Reduced Motion/Low Power）逐一映射到当前实现锚点；Unlock/Scroll 如实标注「预留」（§22：不把未实现写成 implemented）。
- **理由**：不可见零渲染是功耗验收硬指标；运动语义此前散落在实现中无单一契约。
- **后果**：ERA 14 全部完成 → ERA 15 Personal Intelligence Depth（§67-§74：Question Classification / Context Ranking / Context Budget / Evidence 归一化 / Grounding Validator）。

## ADR-038：ERA 15 Personal Intelligence Depth 第一轮（分类/排序/预算/证据/落地校验）

- **决策**：
  1. **§67 Question Classification**：`QuestionClassifier` 六分类（EXPLAIN_CURRENT_STATE/FIND_LONGITUDINAL_PATTERN/SUMMARIZE_WEEK/SUMMARIZE_MONTH/ANSWER_PERSONAL_QUESTION/PROPOSE_ACTION）关键词信号 + 置信度；EchoConversationController.ask 以分类结果驱动检索（此前恒 ANSWER_PERSONAL_QUESTION）。
  2. **§68 Context Ranking**：`ContextRanker` 优先级（USER_CORRECTIONS > CONTEXT_EXCEPTIONS > PREFERENCES/BASELINE > PORTRAIT_HISTORY/TODAY_AGGREGATE）+ confidence/timeRange/信息密度次级因子；与 §75 记忆排序同源。
  3. **§69 Context Budget**：ContextPolicy 增 maxMemories/maxTokens；EchoContextCompiler.compile 在剔除禁止数据后执行「排序 → 证据上限 → 记忆上限 → token 预算（3 字符/token 保守估算，超限截断）」——禁止全历史塞模型。
  4. **§70 EchoEvidence**：EvidenceItem 扩展为统一 schema（id/type/timeRange/source/value/baseline/comparison/confidence/provenance/sensitivity）；EvidenceAssembler 填充 observation/memory/correction/context_exception 语义字段。
  5. **§71/§72 EchoAnswer + Grounding Validator**：`GroundingValidator`（Evidence exists / confidence 越界 / Observed-Interpreted-Felt 边界禁词 / containsBlockedVocabulary）+ `buildAnswer` 降级兜底；AiNarrativeService.answerQuestion 在 AI 文本出口强制校验，未通过 → OBSERVATION_FACTS 事实兜底（§73 链：provider fail → repair/retry 已有 → deterministic → observation facts）。
  6. **§74 立场**：OpenAI-compatible 保持社区主实现；Provider 数量不是成熟度指标（不新增 Provider）。
- **理由**：模型不是 ECHO——价值来自正确的个人 Context（Relevant，不是 Maximum）。
- **后果**：IntelligenceDepthTest 9 用例；对话答案所有出口均过 Grounding；下一轮 ERA 15.5 Memory Maturity（§75-§80：检索排序正式化/生命周期 Worker/派生模式/Context Exceptions 表单/What ECHO Knows 七分类）。

## ADR-039：ERA 15.5 Memory Maturity 第一轮（排序/生命周期/派生模式/用户解释/七分类）

- **决策**：
  1. **§75 Retrieval Ranking 正式化**：`rankMemories` 纯函数（类型优先级 USER_CONFIRMED/CORRECTION > CONTEXT > PREFERENCE > DERIVED_PATTERN > OBSERVATION > TEMPORARY_INTERPRETATION × 衰减分 × 重要度 × 最近确认）；MemoryRepository.topMemories 改为 JVM 侧重排（取 3 倍候选再截断）。
  2. **§76 生命周期确认**：purgeExpired 已由 PresenceRefreshWorker（15 分钟周期）执行；confirm 走 DAO 强化（importance +10 上限 100 + lastConfirmedAt 刷新）；PresenceRefreshWorker 增补 derivePatterns 维护步骤。
  3. **§77 Derived Pattern Memory**：`derivePatterns` 纯函数（重复 ≥3 次同内容 OBSERVATION → DERIVED_PATTERN；confidence 随证据数增长 0.5-0.95）；MemoryRepository.derivePatterns 幂等 upsert（id = 内容哈希）；中文事实 4 字起算。
  4. **§78/§79 用户解释优先**：WhatEchoKnows 增「告诉 ECHO 一个特殊时期」入口（七类：出差/旅行、假期、工作特别忙、考试周、生病/恢复期、重要事件、其他 + 补充说明）→ MemoryRepository.recordContextException（CONTEXT 类型，confidence=1 用户自述最高置信，importance=70）。
  5. **§80 七分类展示**：Observed / User-confirmed / Context / Correction / Preference / Derived Pattern / Temporary Interpretation 全部有独立标签分组（低置信临时解释标注「还不确定」）。
- **理由**：Memory 是长期 Personal Intelligence 的核心资产；§110 禁止字段存在=实现完成。
- **后果**：MemoryMaturityTest 8 用例；下一轮 ERA 16 Journey Long-term Memory（§81-§87：Canonical Daily State/历史重建/Year View/Life Season × Journey）。

## ADR-040：ERA 16 Journey Long-term Memory 第一轮（Canonical Daily State/历史重建/视觉记忆河流/年视图/Life Season × Journey）

- **决策**：
  1. **§83 Canonical Daily State**：`JourneyCanonicalDay`（date/visualSeed/visualParams/identityReference/maturity/keyEvidenceIds/createdAt——只存参数，**绝不存 bitmap**）+ `JourneyCanonicalCodec`（v1 '|' 分隔，fail-closed 解析失败 → null 弥散占位）；`buildCanonicalDay` 经冻结的 EchoVisualMapper（§62）以正午 12:00 基准时刻映射。持久化：Room v9→v10 新增 `journey_canonical_days`（id=userId_date 幂等 upsert；SQL 层 userId 隔离；LocalDataRights 数据权利删除覆盖）。写入口：PresenceRefreshWorker 15 分钟周期 + Journey 打开时（幂等覆盖）。**快照 = 预聚合（§108）**：年视图只读 ≤365 行参数行，不做全量实时计算。
  2. **§84 历史重建**：`reconstructJourneyFrame(canonical, fallbackPortrait, seed, w, h)`——Canonical 优先（确定性，一年后同一帧）→ 画像派生参数 fallback → 双无则 null（不编造）；JourneyScreen DAY 尺度点按任意一天 = 「那一天的回声」（JourneyEvent.SelectDay）。
  3. **§85 Visual Memory River**：`buildVisualMemoryRiver`（7/30 天分段 → 逐段聚合 → 分类 SPECIAL > TRANSITION > DRIFT > DENSE > STABLE → 相邻同类合并，TRANSITION 不合并）；`visualDistance` 12 维归一化欧氏距离；阈值 TRANSITION=0.30 / DRIFT 步=0.05 / DENSE 活动=0.55；中性标签（平稳时期/密集时期/节律漂移/特殊阶段/长期转变）。
  4. **§86 Year View**：`buildYearView` = 四季聚合（Spring/Summer/Autumn/Winter 视觉+转变+上下文时期+身份快照）+ majorShifts（月聚合距离 ≥ 阈值）+ contextPeriods（同 kind 相邻日期合并）+ identityEvolution（每月最近 Canonical 身份快照，无快照不编造）——不是 365 个点。
  5. **§87 Life Season × Journey**：`explainLifeSeasonVisual`（later rhythm/more fragmented/less mobile… → 「为什么 ECHO 的视觉在这个阶段慢慢变化」中性解释）+ `changedVisualAspects`/`explainPeriodChange`（12 维前后对比 ≥0.12 阈值）；测试强制 §57 禁词表（depressed/anxious/burned out/抑郁/焦虑/燃尽…绝不出现）。
  6. **§81/§82 五尺度六层**：`assembleJourneyLayer`（Day/Week/Month/Season/Year × Visual/Facts/Patterns/Exceptions/Narrative/Evidence）；patterns = 窗口维度众数（≥2 天）；evidenceIds = portrait:yyyy-MM-dd（只有真实视觉参数的日期才产生证据）。
  7. **§78 上下文例外时间定位**：CONTEXT 记忆内容携带可选日期（`特殊时期：kind（note）@yyyy-MM-dd`，`contextExceptionContent/Info` 纯函数 + fail-closed 解析）；Me 页新增特殊时期默认带今天日期；Journey 河流 SPECIAL 段/年视图 contextPeriods 由带日期例外驱动；无日期的旧记忆仍可检索但不进入时间线。
- **理由**：Journey 最终不是 Trend，而是 Personal Visual Memory System（§81-§87）；§110 禁止字段存在=实现完成——每一条 § 都有真实数据流（写/存/读/渲染/解释）。
- **后果**：JourneyCanonicalTest/RiverTest/YearViewTest/SeasonNarrativeTest/LayerTest/ContextExceptionsTest + DatabaseMigrationTest v10 + JourneyUiStateAssemblyTest/ViewModelTest 增补；下一轮 ERA 17 Security Hardening（§88-§92 KDF 迁移/密钥分离/迁移测试）。

## ADR-041：ERA 17 Security Hardening 第一轮（KDF 审计/标准 KDF 迁移/密钥分离/旧库迁移链）

- **决策**：
  1. **§88 审计结论**：确认旧实现为 fixed IV(全零 12B) + AES-GCM + SHA-256 的非标准派生，且字段加密与 DB 口令共用同一 alias（`echo_mind_sensitive_fields_v2`）——正式迁移。
  2. **§89 标准 KDF**：DB 口令 = HKDF-SHA256(ikm=每安装 256-bit SecureRandom 秘密, 公开域分离盐, info="echo-mind:sqlcipher-passphrase:v1")，32 字节；`HkdfSha256` 为 RFC 5869 标准实现，**测试用 RFC 官方 Test Case 1/2/3 向量验证**（不发明 Crypto）。秘密经 Keystore AES-GCM **随机 IV** 包装持久化（`DatabaseSecretFormat` v1，fail-closed 解析）。
  3. **§90 密钥分离**：字段加密沿用 field alias（v2）；DB 秘密包装使用**全新独立 alias** `echo_mind_db_secret_v1`（randomizedEncryptionRequired=true 标准随机 IV）+ 独立 HKDF context/info——轮换 DB 秘密不影响字段密文（测试断言）。
  4. **§91 旧库迁移链**：`DatabaseOpenOrchestrator`（:core:security 纯决策，JVM 可测）——derive new → 打开失败且为错钥 → 已迁移标记则 fail-closed（legacy 退役）→ derive legacy（v0.8/0.9 固定 IV 派生，field key）→ open → rotateSecret（生成/存储新受保护秘密）→ PRAGMA rekey → verify → markMigrated → retireAncient（删除 v0.7 v1 alias）。失败任一步抛原始异常，旧库仍以旧口令可用、下次启动重试（自愈）。AppContainer.openDatabase 退化为 Room/SQLCipher 适配器。
  5. **§92 Crypto 测试**：fresh install（秘密自动 provision + 跨实例稳定）/ 旧库 legacy 派生稳定 / 轮换持久 / 字段加密不受 DB 轮换影响 / 损坏存储 fail-closed 重建 / ancient 退役 / 迁移标记 round-trip（JCEKS 替身纯 JVM，10 用例）；orchestrator 迁移链决策矩阵 9 用例；RFC 向量 4 用例；真机路径（HKDF 稳定/legacy 可用/轮换跨实例）由 CI 模拟器 instrumentation 执行。
- **理由**：Crypto 不发明、不共享、不静默降级；旧用户无损迁移优先于算法洁癖（禁止改 Crypto 却不迁移已有 DB）。
- **后果**：旧库首次启动自动 rekey 到新 KDF；field/DB 密钥独立；下一轮 ERA 18 Reproducible Release（§93-§96：Actions SHA pinning / release set / in-app build info / clean-room 复现）。

## ADR-042：ERA 18 Reproducible Release 第一轮（Actions SHA pinning/release set/应用内构建信息/clean-room 复现）

- **决策**：
  1. **§93 Actions pinning**：全部 5 个 workflow、64 个 `uses:` pin 到 40 位 immutable commit SHA（GitHub API 实时解析 tag → SHA，注释保留原 tag 追溯）；新增 `scripts/verify_workflow_pins.py` 门禁（拒绝 major tag/短 SHA/分支），接入 source-integrity.yml 与 release_preflight。
  2. **§94 Release Set**：新增 `scripts/test_release_set.py`（5 用例：§94 九要素齐全 + artifact manifest 逐条 hash 一致 + signed APK ↔ provenance 绑定 + source archive ↔ provenance hash + Release Notes 版本声明），接入 release-closure（verify_final_package 之后同 run 执行）。
  3. **§95 In-app Build Info**：`BUILD_VERSION` 改为派生自 versionName（消除硬编码漂移）；`BUILD_TIMESTAMP` 默认 = HEAD 提交时间（同 commit 构建字节可复现；发布可 -PECHO_BUILD_TIMESTAMP 显式注入）；新增 `BuildInfoTest`（40 位 hex/版本一致/无敏感 CI 信息泄漏）。
  4. **§96 Clean-room Reproduction**：gradle wrapper 增 `distributionSha256Sum`（官方 8.13-bin.zip 校验和）；启用 Gradle dependency locking（`lockAllConfigurations()` + 10 个 `gradle.lockfile`，升级须显式 `--update-locks`）；`docs/architecture/CLEAN_ROOM_REPRODUCTION.md` 记录 JDK/Gradle/Python/依赖锁定 + 复现步骤 + 可复现性边界（source archive 字节级 / unsigned APK 等价可复现 / signed APK 可验证但非字节恒等——诚实声明）。
- **理由**：最终社区用户必须能验证「这个 APK 来自这份源码」；可复现性的每一层都给出可执行门禁而不是文档承诺。
- **后果**：下一轮（ERA 18 收尾/可选 Affective Intelligence）：release 流水线在真实 GitHub Actions 上首跑验证 pins；Affective Intelligence 仅在 opt-in 契约完整后实施。

## ADR-043：ERA 18 收尾（APK↔provenance 绑定闭环 / §109 记忆索引 / JVM 性能防退化门禁）

- **决策**：
  1. **APK↔provenance 绑定闭环**：`test_release_set.py` 新增 `test_apk_embeds_provenance_commit`——40 位 commit SHA 是 BuildConfig 常量、直接存在于 classes.dex 字节流，任何社区开发者无需反编译工具即可验证「这个 APK 构建自 provenance 记录的这份源码」（release 构建必须发生在 feat 提交之后，漂移 = 发布阻断）。
  2. **§109 Memory Long History**：Room v10→v11 为 echo_memories 增复合索引 `(userId, deleted, importance)` + `(userId, type, deleted)`（覆盖 top/observe/byType 热路径；MIGRATION_10_11 幂等建索引 + 迁移测试）；配合既有 LIMIT 3 倍候选截断，避免「SELECT everything → JVM sort」退化。
  3. **JVM 性能防退化门禁**：`PerformanceBaselineTest`（365 天 Year View <2s / LifeSeason 365 窗口 <1s / 1000 条记忆排序 <1s / 空输入 <200ms，最优 3 次）；`docs/performance/PERFORMANCE_BASELINES.md` 记录预算语义与扩展规则；真机数字仍由 CI connected-test 矩阵执行。
- **理由**：FINAL ENGINEERING ACCEPTANCE 的「任何社区开发者都能够验证：这个 APK 确实来自这一份源码」需要可执行的绑定测试而不是文档承诺；性能防退化与正确性同为发布门。
- **后果**：可选 Affective Intelligence 仍被 AFFECTIVE_CONTRACT §8/§9/§10（临床/安全评审 + PIPIA + 错误恢复前置）冻结，`affectiveState` 保持恒 null，待人工评审门槛完成后实施。

## ADR-044：Affective Intelligence 预备（§8 离线评估框架，不激活）

- **决策**：
  1. 在不越过 AFFECTIVE_CONTRACT §8/§9/§10 评审门槛的前提下，先行建设 §8 前置设施：
     `scripts/affective_eval.py`（grounding/overreach/calibration 三指标 + 阈值 gate，
     本地回放模式零第三方依赖；endpoint 模式支持同一 fixture 在不同 OpenAI-compatible
     Provider 上复跑）+ 8 场景合成验证集（七维连续表示、中性描述、禁标签词）+
     9 用例测试套件（幻觉引用/越界词/校准/解析/确定性）。
  2. **冻结执行**：新增 `AffectiveContractFreezeTest`（扫描全部 main 源码：任何非空
     `AffectiveState(` 构造或非 null `affectiveState =` 赋值 = 发布阻断）——评审门槛
     完成前 `affectiveState` 恒 null 由测试强制，不是注释承诺。
  3. 阈值诚实标注为**示例值**（评审定稿前不视为满足 §8）；试点脱敏数据验证集待 §9 评审补充。
- **理由**：可选时代的激活被契约的人工评审门槛冻结；预备工作（评估框架 + 冻结测试）是
  不触碰门槛的合法工程推进，且让未来的激活评审有可复跑的证据基础设施。
- **后果**：`docs/intelligence/AI_EVAL.md` 复跑协议 + 激活前置清单；下一轮继续收尾
  （如后端 pip-audit/lockfile 等）或等待评审推进 Affective 激活。

## ADR-045：backend 依赖锁定（uv.lock）+ SBOM 确定性（§96 收尾）

- **决策**：
  1. backend 依赖锁定：venv 由 uv 管理 → 采用官方 `backend/uv.lock`（51 包精确版本 + 完整传递闭包，跨平台 universal 解析）；本地 `uv sync --check` 校验环境一致；CI backend-ci lint job 增 `uv lock --project backend --check` 漂移门禁（pip install uv 后执行）；release_preflight 在 uv 可用时同检查。
  2. SBOM 升级：`generate_sbom.py` 的 backend 段改读 uv.lock 精确版本（无锁回退 pyproject 范围）；时间戳锚定 version_source.json 的 `sbom_created_utc`（版本冻结，commit 无关——首版尝试锚定 HEAD 提交时间因「SBOM 在 manifest 内 + 依赖 commit SHA」循环依赖被 clean-room 门禁否决后修正；任意 checkout 重生成字节一致，含 provenance chore 提交之后）。
- **理由**：§96「dependency lock state」必须可验证（Android 已有 gradle.lockfile；backend 补齐 uv.lock 后双侧锁定闭环）；SBOM 非确定性时间戳破坏同 commit 字节复现。
- **后果**：SBOM 包数 41→76（backend 51 锁定 + Android 25）；uv.lock 变更（依赖升级）需要显式 `uv lock` 重生成并通过 CI 门禁；SBOM 字节级确定性由 clean-room 门禁验证（版本升级时更新 sbom_created_utc）。

## ADR-046：依赖审计本地化 + 日期边界测试修复（§96/测试策略收尾）

- **决策**：
  1. **依赖审计本地化**：新增 `scripts/audit_dependencies.py`——backend 走 uv.lock → `uv export` → `uvx pip-audit`（OSV）；全仓 osv-scanner 本地可选（未安装如实 NOT RUN，security-ci 强制执行）；接入 release_preflight 与 security-ci（pip-audit 步改走锁定依赖，与本地同构）。首跑即命中真实漏洞：cryptography 46.0.7（GHSA-537c）→ 48.0.1（PYSEC-2026-3554）→ 49.0.0（PYSEC-2026-3552）→ 最终 pin `>=50,<51`（uv.lock 重解析、venv 重同步、全量后端测试复核）。
  2. **日期边界测试修复**（backend 6 用例周末 flaky 根因）：画像基线按 weekday/weekend 分桶（MIN_BUCKET_DAYS=2）——测试以 9 天窗口播种，周六/周日运行时 weekend 桶仅 2 有效日 → WARMING_UP；test_messages 用固定日期，滚动 7 天窗口过期后同病。修复：e2e 增 `_seed_history`（bucket-aware：只播种双桶各 ≥7 日的必要日期，~14 天，时长 2.4× 优于朴素 28 天）；test_messages TODAY 改为用户时区实时「今天」。与 cryptography 升级无关（46 版同样失败，已交叉验证）。
- **理由**：审计门禁必须与锁定依赖同源才可复跑；测试必须对真实时钟（周末/午夜边界）鲁棒。
- **后果**：backend 全量测试时长 +~2 分钟（e2e 双桶播种）；安全审计发现并修复 3 级串联漏洞链；下一轮继续剩余收尾。

## ADR-047：后端测试时长优化 + osv-scanner 本地化（收尾）

- **决策**：
  1. e2e 测试时长优化：`_seed_history` 每日窗口 120→80（覆盖 0.28 ≥ MIN_COVERAGE 0.25，留安全边际）——READY 依赖用例请求量 -33%；全量 backend 测试 2:52 → **1:52**（17 个 e2e/messages 用例 2:18 → 1:36），断言语义不变。
  2. osv-scanner 本地化：官方 darwin_arm64 二进制下载（GitHub release 网络超时重试 + 断点续传）；失败则如实保持 audit_dependencies.py 的 NOT RUN 豁免（security-ci 强制执行不变）。
- **理由**：测试时长是 CI 反馈速度与开发迭代成本；审计工具本地化让「CI 通过」可在本地预演。
- **后果**：backend-ci 全量 ~2 分钟内回到基线水平；osv 全仓本地首跑结果待网络可用后记录。

## ADR-048：CI 锁定依赖执行 + workflow 结构门禁 + 文档真值（§96 收尾 / §22）

- **决策**：
  1. backend-ci 与 release-closure 全部 backend 步骤改为 **uv 锁定执行**：`pip install uv` → `uv sync --project backend --extra dev --frozen`（安装即漂移门禁：pyproject 与 uv.lock 不一致即失败，替代原 `uv lock --check`）+ `uv run --project backend --directory backend …` 执行 ruff/mypy/pytest/alembic/静态检查/OpenAPI——CI 与本地同一锁定环境（本地已按同路径预演通过）。
  2. `verify_workflow_pins.py` 增 YAML 结构校验（手改 workflow 的语法错误在本地/CI 即断；5 个 workflow 全部解析通过）。
  3. 文档真值（§22）：README/权威文档的 ADR 计数（020/023/024 → 047）与 Android 单测计数（613 → 617）与当前 main 对齐；历史条目（v1/v2 时代的 ADR-001~024）保留为史实不做伪更新。
- **理由**：§96 的「dependency lock state」只有在 CI 与本地都从锁执行时才成立；文档真值必须与当前可交付 main 一致（§110 禁文档完成主义）。
- **后果**：CI 不再从版本范围安装（锁定执行）；未来升级依赖 = 改 pyproject → `uv lock` → 提交 → CI --frozen 验证。

## ADR-049：§101 Runtime 六态矩阵测试 + 首帧基准 + dependabot uv（收尾）

- **决策**：
  1. **§101 Runtime Tests**：将 coordinator 内嵌 health 推导提取为纯函数 `computeEchoRuntimeHealth`（sensing 六态映射 / presence 已组装 READY 否则 DEGRADED / intelligence Provider 映射 / memory 常驻 READY）；新增 EchoRuntimeHealthTest 5 用例（六态全矩阵 + ProviderStatus 全枚举 + 四组件聚合 + presence 空/有 + 确定性）。
  2. **首帧基准**：PerformanceBaselineTest 增 ECHO Scene 帧计算 1000 次 <2s（EchoVisualMapper.map → computeEchoSceneFrame 确定性路径）——PART PERFORMANCE「first meaningful frame」的 JVM 代表项；预算表扩为七行。
  3. **dependabot backend 生态切 uv**（uv.lock 为事实源；原 pip 生态改 uv 后升级 PR 会同时更新 pyproject + uv.lock）。
  4. osv-scanner 第五次下载仍 exit 16（GitHub release CDN 不可达）——本地豁免维持，security-ci 强制执行。
- **理由**：§101 要求六态有测试；§110 禁止「字段存在=实现完成」——health 推导纯函数化即证据。
- **后果**：623 unit tests 全绿；dependabot uv 升级 PR 需过 uv --frozen CI 门禁。

## ADR-050：Kotlin SAST 落地 + release 门禁补缺（security-ci/release-closure 修复）

- **决策**：
  1. **Kotlin SAST（P2 落地）**：security-ci CodeQL 扩为 `languages: python, java-kotlin` + `build-mode: manual`（setup-java 17 + android-actions/setup-android + `./gradlew compileDebugKotlin --no-daemon` 构建提取）；job 超时 40→60 分钟（cold cache + build tracing 余量）。
  2. **release-closure 门禁补缺**：包内 §94/§8 门禁步骤此前在未安装 pytest 的环境直接 `python3 -m pytest`（潜伏失败）——补 `pip install pytest`，并追加 `scripts/test_affective_eval.py`（Affective 评估框架随发布链路持续绿）。
  3. README Android 单测计数 617→623（§22 真值随轮更新）。
- **理由**：security-ci 自评的 P2（Kotlin SAST 缺位）是真实安全覆盖缺口；发布门禁自身必须能在 clean runner 上可执行（§17 原子流程要求同 run 全链可跑）。
- **后果**：security-ci 首跑时长上升（CodeQL Kotlin 提取）；release 门禁现包含 affective 评估框架 9 用例。

## ADR-051：Affective 评估 mock provider 自检 + docs/current 真值（§8 复跑协议 / §22）

- **决策**：
  1. `scripts/affective_eval.py` 增 `--mock-provider`（确定性：期望区间中值 + 置信 0.8 + 首条合法证据 + 中性叙事）——完整评估链路（prompt 构建 → 解析 → 三指标 → gate）在无第三方账号/Key 下可闭环自检；`test_affective_eval.py` 12 用例（含 mock 通过全部阈值 = fixture 自洽哨兵 + 无证据场景 abstain 不计 coverage 分母——首跑暴露的 coverage 语义 bug 已修）。
  2. docs/current/README.md 真值对齐（ERA 12.8-18 完成 + Affective 预备冻结、ADR-001~050、新架构文档/CLEAN_ROOM/PERFORMANCE_BASELINES/AI_EVAL/双锁回填）；Phase9 安全供应链文档回填 Kotlin SAST 与锁定审计。
- **理由**：§8 复跑协议需要一个零外部依赖的自检入口；§22 文档只描述当前 main 已存在能力。
- **后果**：release-closure 的 affective 9 用例随每次发布链路运行；mock 自检可在任何 clean runner 复跑。

## ADR-052：backend mypy 收紧（disallow_untyped_defs=true，84 → 0）

- **决策**：
  1. `pyproject.toml` 启用 `disallow_untyped_defs = true`（原注释标注的下一步）：84 个未标注函数逐一补齐——路由返回类型按真实契约标注（dict / list[dict[...]] / PortraitOut / ActivationCodeIssueOut / SandboxRunOut / OnboardingVerifyOut…）；410 停用路由标注 `-> None`（恒 raise）；内部辅助参数（Session/Principal/flush_context/instances/**fields）补齐。
  2. 过程暴露 1 个真实行为差异：`list_escalations` 加 `-> list[dict[str, int | str | datetime | None]]` 后，Pydantic 响应校验把 dict 值中的 bool 沿 int 分支强转为 0/1（`chain_broken`/`delivery_confirmed` 契约破坏，workbench 契约测试抓获）→ 联合类型并入 `bool` 修复。**教训：给路由加响应注解会改变序列化语义，必须跑契约测试而非只跑 mypy。**
- **理由**：全量类型标注让未标注函数成为 CI 阻断项；响应注解 = 响应契约的运行时强制。
- **后果**：backend mypy 门禁更严；未来新函数未标注即 CI 红。

## ADR-053：detekt 规则集扩围（5 → 14 规则，探测轮全绿后固化）

- **决策**：
  1. detekt 基线扩集：coroutines（GlobalCoroutineUsage / RedundantSuspendModifier）、potential-bugs（ImplicitDefaultLocale / ExplicitGarbageCollectionCall / MapGetWithNotNullAssertionOperator / UnnecessarySafeCall / UselessPostfixExpression）、style（UnnecessaryAbstractClass / NewLineAtEndOfFile / ProtectedMemberInFinalClass / ExplicitItLambdaParameter）——全部经探测轮在全模块（10 个 Gradle 模块）零告警后固化，maxIssues=0 门禁不变。
  2. RedundantVisibilityModifier 不启用（detekt 1.23 已移除该规则）；naming/WildcardImport/MagicNumber 维持关闭（既有风格契约，与策略注释一致）。
- **理由**：Android 侧静态分析深度与 backend mypy 收紧对齐（本轮为第 30 轮的对偶举措）；只固化「全绿通过」的规则，不引入需要批量修复的噪音。
- **后果**：未来新增代码命中上述 9 条新规则即 CI 红；规则集后续继续按「探测→清零→固化」流程扩围。

## ADR-054：Android lint 硬门禁（warningsAsErrors + 97 条告警清零）

- **决策**：
  1. `app/build.gradle.kts` 启用 `lint { warningsAsErrors = true; lintConfig = lint.xml }`——97 条告警（94 W + 3 H）全部处置：修复 13 项真实信号（ApplySharedPref commit→apply；ObsoleteSdkInt 恒真检查删除 + mipmap-anydpi-v26 目录归一；DataExtractionRules 补 fullBackupContent/dataExtractionRules 双规则（与 allowBackup=false 隐私契约一致，全域排除）；UnusedResources 14 条真未用 string 删除（manifest label 改 @string/app_name）；AutoboxingStateCreation 3 处 mutableIntStateOf；UseTomlInstead 2 处入版本目录；CanvasSize 以 View.onDraw 语义抑制；debug cleartext 以 tools:ignore 定点豁免）。
  2. lint.xml 全局豁免仅 4 类并内联理由：UseKtx（既有风格，50 处零行为收益）、GradleDependency/NewerVersionAvailable/AndroidGradlePluginVersion（dependabot 负责升级）、Aligned16KB（SQLCipher 4.5.4 上游 native 未 16KB 对齐，待上游发布对齐产物）。
- **理由**：lint 告警从「报告存在」变为「构建阻断」——与 detekt/mypy 双侧收紧对齐；备份规则补全同时强化「敏感本地数据不上云」隐私契约。
- **后果**：未来任何新 lint 告警 = CI 红；16KB 对齐待 SQLCipher 上游跟进。

## ADR-055：UI 层状态提升 + 槽位组合 smoke test 模式（ERA 32-39）

- **决策**：
  1. 三大世界根页面（EchoSceneScreen / JourneyScreen / MeScreen）全部收敛为「薄包装 + 纯内容」：包装层只做 ViewModel/流收集与容器依赖装配；纯内容以 state-in / event-out + 组合槽位（visualSurface/actionLayer/actionOverlay/九槽位 MeScreenContent 等）渲染，分组 data class（State/Navigation/CoreActions/FeedbackActions）保持 detekt 阈值。
  2. 容器依赖（AppPreferences/SkillRepository/Coordinator）一律留在调用侧：偏好字段下沉为状态输入（aiPromptDismissed/awakenedAtEpochMs），组件去容器化（SeedPortraitBlock 只收 awakenedAtEpochMs、EchoVisualSurface 只收纯映射 config）。
  3. 动画宿主组件（EchoLifeField 无限帧循环 / EchoActionOverlay 无限帧动画）不进入 Robolectric smoke test——由构造隔离（槽位注入 / 纯映射函数测试），真机行为由 CI connected-test 覆盖。
  4. Robolectric + compose-ui-test-junit4 渲染基线覆盖三世界（ECHO 30 + Journey 9 + Me 51 + Onboarding 7 + 组件层），技术要点入测试惯例：clickable/selectable 合并语义用 hasClickAction()+hasText()；视口外点击必须 performScrollTo()；LazyColumn 用超高窗口 qualifiers 全量组合。
- **理由**：UI 层此前零渲染测试；容器直连使渲染不可测。槽位模式同时消除 §31 类「Screen 直接编排 Repository」债务（SubscriptionViewModel 收口）。
- **后果**：新增 Screen 必须状态提升后才可测；动画组件用纯映射测试模式替代 Robolectric 渲染。

## ADR-056：backend mypy strict 冻结（ignore_missing_imports=false + 89 处裸泛型精确化）

- **决策**：
  1. `ignore_missing_imports = true → false` 探测——全部依赖自带类型（fastapi/sqlalchemy/pydantic/psycopg/PyJWT/cryptography），零豁免（预先假设的 passlib 豁免为死配置删除）。
  2. `mypy --strict` 89 处误差脚本化行级修复：dimensions 桶统计精确为 `dict[str, float]`（顺带消除 2 处 Any 回传）、baseline_metrics 精确为 `dict[str, dict[str, float]]`、安全鸭子特征 `list[Any]`、其余路由/服务 JSON 载荷 `dict[str, Any]`（28 文件补 Any import）。
  3. 配置冻结为 `strict = true`（在 disallow_untyped_defs 收紧之后的终态）。
- **理由**：类型盲区消除——新代码裸泛型 / 未标注函数 / 未使用 ignore / 缺失 stub 引用 = CI 红；pytest 1070 全绿证明注解变更零行为漂移。
- **后果**：backend 类型门禁达到 strict 终态；后续新增依赖若无类型 stub 将直接阻断。

## ADR-057：detekt 27 规则 + lint 安全规则固化（ERA 37/39）

- **决策**：
  1. detekt 14 → 27 规则：style +4（UnusedImports/MayBeConst/UnnecessaryParentheses）、potential-bugs +4（CastToNullableType/DontDowncastCollectionTypes/LateinitUsage 主源集强制·测试源集豁免/UnusedUnaryOperator）、coroutines +1（SleepInsteadOfDelay）、performance +2（ForEachOnRange/UnnecessaryTemporaryInstantiation）；探测淘汰 CollapsibleIf（1.23 不存在）与 MissingWhenCase/RedundantElseInWhen（编译器默认检查）。探测清除 60+ 处未用 import/多余括号。
  2. lint.xml 探测固化 4 条 error 级安全/RTL 规则（UnspecifiedImmutableFlag / UnspecifiedRegisterReceiverFlag / SetJavaScriptEnabled / RtlHardcoded——全仓库零命中）；UseKtx 豁免复核保留（50 处含刻意 commit() 同步写路径，KTX edit{} 默认 apply 语义不同）。
  3. 性能防退化预算 7 → 9 行（Journey 365 天完整 UI 状态装配 <2000ms、Derived Pattern 1000 条派生 <1000ms）。
- **理由**：Android 静态分析深度与 backend mypy strict 对齐的收官；豁免必须有复核后的活理由。
- **后果**：新增代码未用 import/多余括号/主源集 lateinit/PendingIntent 可变标志等 = CI 红；性能预算随产品路径扩展。

## ADR-058：下一长阶段选型——Identity/LifeSeason 真值审计深化（ERA 50）

- **决策**：质量/安全/数据权利复核阶段（ERA 30-49）收官后，下一长阶段选定 **ERA 14 §52/§61 真值审计与深化**——逐条验证 IdentityGenome / LifeSeason / DailyComposition / MomentState 是否真实流入 PresenceRepository → EchoVisualMapper → Renderer → Journey Canonical 快照（§52：未进入数据流则算未实现；§61：无意义 placeholder 字段必须真实现或删除），并以端到端测试锚定「Day 1 / Day 30 / Day 180 同一个 ECHO」的连续性。不选 Provider 生态扩展（宪法明确非优先级）、不选 Journey Year 视图重做（ERA 16 五尺度已交付）。
- **理由**：FINAL PRODUCT ACCEPTANCE 的核心验收（「半年以后形成只有这个用户才拥有的 Identity」）依赖长期身份链路的真实性；审计发现比新增功能价值更高，且符合「禁止文档完成主义」§110。
- **后果**：审计发现任何死字段/断链即以测试固定后修复；每轮维持全门禁 + 发布链纪律；Affective 冻结不受影响。

### ADR-058 结项记录（ERA 54，审计四轮结论）

- 第 1 轮（装配/映射/渲染/Journey 快照）：四层数据流逐段真实——无死字段；锚点 IdentityPipelineTruthTest（四层达参 / Canonical 往返同帧 / 跨成熟度同 ECHO）。
- 第 2 轮（Journey 回退重建 + §87 解释链 + §61 全模块巡检）：回退确定性与解释链逐字绑定锚定（LifeSeasonJourneyBindingTest）；五模块零占位。
- 第 3 轮（快照落盘恢复链）：发现并修复 **EchoPresenceCodec v1 跨进程断链**——进程死亡后 Wallpaper/Dream 仅恢复颜色连续、丢失人格/纹理/季节/日构图/分钟调制；v2 补齐四层 40 字段（v1 兼容、fail-closed 收紧），跨进程同帧测试锚定。
- 第 4 轮（Wallpaper 刷新链 + Runtime 协调器边界）：onVisibilityChanged 补快照重读（解锁即追上 15 分钟刷新）；EchoRuntimeCoordinator §3 职责边界复核 PASS（无 AI/Context/基线/记忆合成/UI 越界），§4 健康四组件齐备（memory=READY 为进程级不变量，文档化）。
- 结论：Identity/LifeSeason 真值审计阶段完成；「Day1/Day180 同一个 ECHO」具备端到端可测锚点；下一步进入下一长阶段选型（见 IMPLEMENTATION_STATUS Next-task）。

## ADR-059：下一长阶段选型——Personal Intelligence 解释链真值审计与深化（ERA 55）

- **决策**：Identity 真值审计（ADR-058）结项后，下一长阶段选定 **§67-73 Personal Intelligence 解释链真值审计与深化**——分类 → 检索/排序 → 预算编译 → Grounding → 降级兜底整链端到端验证（与 Identity 审计同模式：逐段找断链/死参数，测试锚定后修复）。不选 Journey Year 视图深化（ERA 16 五尺度已交付，属视觉打磨）与真机基准二期（CI 已接管，无本地收益）。
- **理由**：FINAL PRODUCT ACCEPTANCE 的「为什么今天不一样」与「换模型不失忆/模型崩溃 ECHO 不消失」直接依赖解释链与降级链的真值；§68 task relevance 已发现死参数（本轮修复）；Intelligence depth 在宪法优先级高于视觉打磨。
- **后果**：每轮维持全门禁 + 发布链；审计发现以测试固定后修复；Affective 冻结不受影响。

### ADR-059 结项记录（ERA 59，审计四轮结论）

- 第 1 轮：修复 §68 task relevance 死参数（同 tier 亲和，不跨纠正/例外层级）；端到端链锚点。
- 第 2 轮：检索策略矩阵 11 任务全检（隐私硬边界 / 用户解释全任务可达 / 预算有限）；Grounding 引用边界 5 契约。
- 第 3 轮：§73「retry」步骤由注释变实现（瞬态失败重试一次、语义失败立即降级）；StructuredOutputValidator 矩阵复核。
- 第 4 轮：EchoConversationController 零测试 → 6 契约（全链 / 诚实降级 / 4 轮窗口 / 检索异常 / 相位映射 / 重置）。
- 收官：§70 baseline/comparison 字段此前装配不落、编译不读（spec 字段流亡）——现装配进入 schema 指定字段并经编译显式进入模型上下文；§71 EchoAnswer.timeRange 此前恒 null——现由被引用证据时间范围导出。
- 结论：Personal Intelligence 解释链真值审计完成；换模型不失忆（记忆端口检索与模型无关）与模型崩溃 ECHO 不消失（fallback 链）具备端到端锚点。

## ADR-060：下一长阶段选型——What ECHO Knows 深化（Me 世界用户信任层，ERA 60）

- **决策**：解释链真值审计（ADR-059）结项后，下一长阶段选定 **§80 What ECHO Knows 深化**——七类认知分层（Observed / User-confirmed / Context / Correction / Preference / Derived Pattern / Temporary Interpretation）在 Me 世界的完整呈现与解释（FINAL PRODUCT ACCEPTANCE：用户能明确知道 ECHO 知道什么、不知道什么）。不选 Journey Year 视图深化（视觉打磨，优先级低于用户信任）与 Memory 长历史性能（§109 索引已在 v11 落地，千级压测归 CI 真机矩阵）。
- **理由**：宪法优先级 User trust / User control 直接受益；§80 分层在 UI 已有标签与过滤基础（WhatEchoKnowsContentSmokeTest 7 用例），深化成本低、真值收益高。
- **后果**：每轮维持全门禁 + 发布链；「ECHO 不知道什么」与分层摘要补齐后以测试锚定；Affective 冻结不受影响。

### ADR-060 结项记录（ERA 62，三轮结论）

- 第 1 轮：§80 七层分层摘要（MemoryLayerCounts 纯映射；过滤不影响计数——用户永远看到全貌）。
- 第 2 轮：「ECHO 不知道什么」能力边界（EchoKnowsFacts → EchoDoesNotKnow 纯映射：感知/麦克风/AI/基线四事实 → 诚实边界行；能力齐备零行不编造）。
- 第 3 轮：每条记忆显示来源（= 层标签）与保留策略（retentionLabelText 与 retentionDaysFor 同源：7/30/365 天到期自动软删 + 用户固定永不清理）。
- 结论：FINAL PRODUCT ACCEPTANCE「用户能明确知道 ECHO 知道什么 / 不知道什么 / Memory 有什么」在 Me 世界完整呈现且逐项可测；What ECHO Knows 深化阶段完成。

## ADR-061：下一长阶段选型——Memory 长历史性能与正确性（§109，ERA 63）

- **决策**：What ECHO Knows 深化（ADR-060）结项后，下一长阶段选定 **§109 Memory 长历史性能与正确性审计**——千级/五千级记忆的检索排序、派生、过期维护全路径实测与修复（§109 明确「根据规模加入 indexes/query ranking/summaries」；审计即刻发现 purgeExpired 500 条截断缺陷）。不选 Journey Year 视图深化（视觉打磨）与数据权利检查台（47/49 轮已覆盖导出/删除/撤回证据链）。
- **理由**：数据真值（宪法优先级 4）> 视觉打磨；过期维护截断是真实正确性缺陷（低重要度短时记忆恰是被截断的那部分）；性能基准本地 JVM 可测 + CI 真机矩阵承接。
- **后果**：每轮维持全门禁 + 发布链；发现缺陷以测试固定后修复；Affective 冻结不受影响。

### ADR-061 结项记录（ERA 65，三轮结论）

- 第 1 轮：发现并修复 purgeExpired 500 条截断缺陷（低重要度短时记忆漏过期）→ 维护专用全量查询；千级/五千级排序与扫掠锚点。
- 第 2 轮：检索 limit 语义复核 PASS（3×候选 + JVM 重排 + take 双层收紧）；derivePatterns 五千级锚点；维护 Worker 单轮上限 2000 条（软删幂等，剩余下轮）。
- 收官：万级三路径护栏（排序/扫掠/派生 <8000ms 宽预算区分真退化）；PERFORMANCE_BASELINES 9 → 12 行（Memory 长历史三行入表）。
- 结论：§109 Memory 长历史性能与正确性审计完成；「避免 SELECT everything → JVM sort」具备查询级（LIMIT+索引）与算法级（护栏）双重证据。

## ADR-062：下一长阶段选型——Me 数据权利检查台（ERA 66）

- **决策**：Memory 长历史审计（ADR-061）结项后，下一长阶段选定 **Me 数据权利检查台**——端侧一键体检（五域存储足迹 / 导出 / 删除 / 权限与基线状态聚合呈现；底层 47/49/61 轮已齐，缺聚合层）。不选 Journey Year 视图深化（视觉打磨）与 CI 模拟器矩阵扩展（基础设施，无用户直接收益）。
- **理由**：User trust / User control（宪法 1/7）直接受益；「数据使用什么 / Memory 有什么 / 权限有哪些」在 Me 世界以单一检查台落地；复用已验证的五域导出/删除与 EchoDoesNotKnow 事实。
- **后果**：每轮维持全门禁 + 发布链；足迹计数按存储真值（记忆含软删审计行）并明确标注；Affective 冻结不受影响。

### ADR-062 结项记录（ERA 68，三轮结论）

- 第 1 轮：五域存储足迹（DataFootprint + footprintSummary；记忆按存储真值计数含软删审计行）。
- 第 2 轮：DataAndSensing 五域接入 + UI 检查台卡片（五域行 / 上传真相 / 软删审计标注）。
- 第 3 轮：能力边界聚合行（EchoDoesNotKnow 同源事实接入检查台同卡；combine9 流装配扩展）；删除动作后足迹自动刷新契约确认（导出不改变计数）。
- 结论：Me 数据权利检查台完成——「数据使用什么 / Memory 有什么 / 权限有哪些 / ECHO 还不知道什么」单卡呈现、逐项可测。

## ADR-063：下一长阶段选型——Journey 年视图深化（ERA 16 §86/§87 真值审计，ERA 69）

- **决策**：Me 数据权利检查台（ADR-062）结项后，下一长阶段选定 **Journey 年视图深化**——ERA 16 §86/§87 的真值审计与兑现：季节聚合跨年正确性、长期转变点的中性解释绑定（§87「为什么某阶段 ECHO 的视觉慢慢变化」）、身份演化呈现与年视图 UI 锚定。不选 CI 模拟器矩阵扩展（基础设施、无用户直接收益，且本环境无模拟器无法本地验证 CI-only 循环）与任何横向新功能（宪法冻结）。
- **理由**：用户信任层三阶段（ADR-058~062）已全部结项；Journey 是宪法一级产品世界，§86/§87 承诺在 FINAL PRODUCT ACCEPTANCE（「能看到自己的长期时间」）中直接出现，且当前实现存在真实缺口：滚动 365 天窗口跨日历年时季节桶按月标签合并（如 2025-08 与 2026-06 混入同一 SUMMER 桶、转变点跨年错配）；转变点在年视图只显示技术维度名、无 §87 中性解释；年视图 UI 无 smoke 锚定。
- **后果**：每轮维持全门禁 + 发布链；修复以测试固定后实施；Affective 冻结不受影响。结项标准：跨年季节桶逐日可测 + 转变点解释行 + YearViewSection smoke 锚定 + ADR-063 结项记录。

### ADR-063 结项记录（ERA 71，三轮结论）

- 第 1 轮：跨年季节桶真值修复——seasonKeyOf（标签 + 冬季起始年；1/2 月归上一年 12 月开始的冬季），滚动 365 天窗口同标签不同年份分桶；锚点：分键矩阵 / 跨年窗口五桶逐日序列（桶跨度 ≤5 月）/ 转变点年份归属。
- 第 2 轮：§87 解释兑现——JourneyMajorShift.beforeDate + shiftExplanationLines（时间范围 + 用户可读中性解释 + 「整体视觉风格转变」诚实兜底，技术维度名零泄漏断言）；身份演化逐点呈现（identityEvolutionLines：每月快照 + 与上一记录一致/细微调整标注，仅比较基因组字段）；YearViewSection 渲染 smoke 锚定。
- 第 3 轮：装配级回归——YEAR 尺度 365 天窗口（2025-08-15 → 2026-08-14）经 assembleJourneyUiState 端到端：双 SUMMER 分桶 + 全窗口 365 天无重无漏（sumOf dayCount == 365）+ 桶日期单调。
- 结论：ERA 16 §86/§87 真值审计完成——年视图不是 365 个点；季节聚合跨年正确、转变点可解释（§87）、身份演化逐点可读、UI 逐项 smoke 锚定。

## ADR-064：下一长阶段选型——Journey 长历史性能与正确性（§108，ERA 72）

- **决策**：Journey 年视图深化（ADR-063）结项后，下一长阶段选定 **§108 Journey 长历史性能与正确性审计**——365 天窗口全路径实测（时间线加载 / 视觉日装配 / 河流 / 年视图 / 生活阶段计算）与修复（§108 明确「禁止每次全量实时计算 365 天；允许 preaggregation / canonical snapshots / cache / lazy composition」）。不选 CI 模拟器矩阵扩展（连续三轮理由不变：基础设施无直接收益、本环境无模拟器无法本地验证 CI-only 循环）。
- **理由**：宪法优先级 Performance（15）> 基础设施；与 ADR-061（Memory §109）镜像——同审计方法（本地 JVM 锚定 + 宽预算护栏区分真退化）；刚完成的 Journey 年视图阶段天然暴露 365 天装配路径为下一个性能真值缺口；PERFORMANCE_BASELINES 12 行将补 Journey 长历史行。
- **后果**：每轮维持全门禁 + 发布链；发现缺陷以测试固定后修复；Affective 冻结不受影响。结项标准：365 天装配 JVM 锚点入预算表 + 任何发现的全量重算路径修复或论证为已预聚合 + ADR-064 结项记录。

### ADR-064 结项记录（ERA 73，两轮结论）

- 第 1 轮：结构缺陷修复——九流 combine 使 UI 轻量输入（evidence/叙事/运行时快照）每次触发 365 天全量重算；两段式装配（assembleJourneyMemoryState + VM distinctUntilChanged 缓存）后轻量变化零重算（实例级锚定）；单段↔两段契约等价 + 确定性 + 记忆装配预算行（13 行）。
- 第 2 轮：数据级缺陷修复——feature_vectors 无索引且时间线查询全表扫描（allPassiveCoreRows 无限历史）；v11→v12 复合索引 (userId, schemaVersion, windowStart)（MIGRATION_11_12 纯增量幂等）+ passiveCoreRowsBetween 窗口化查询接入 computeTimeline（旧历史/他用户零混入锚定）；computeToday/baselineStatus 保留全量扫描（基线连续有效日 streak 语义需要全历史——剩余增长边界另立审计项）。
- 结论：§108 Journey 长历史审计完成——计算层（两段式缓存）与数据层（索引 + 窗口化查询）双重证据；「禁止每次全量实时计算 365 天」具备结构性与查询级两层保障。

## ADR-065：下一长阶段选型——Presence 性能与功耗真值审计（§65/§64，ERA 74）

- **决策**：Journey 长历史审计（ADR-064）结项后，下一长阶段选定 **Presence 性能与功耗真值审计**——§65 Wallpaper benchmark（CPU/GPU/frame time/memory/wakeups/battery、不可见时 continuous rendering = 0）与 §64 Surface 功耗预算（privacy/layout/animation strength/interaction/power budget）的代码真值审计：JVM 可测段（渲染帧成本/状态装配/平滑插值/低功耗降级路径）本地锚定，设备段维持 CI connected-test 矩阵承接（不本地伪造）。
- **理由**：PART PERFORMANCE 首个未审计域（Wallpaper/Dream 功耗是「桌面 ECHO 在」的产品承诺成本）；§65「不可见：continuous rendering = 0」是可代码审计的硬指标（渲染循环生命周期 + 低功耗降级），本地 JVM 可锚定；User trust（功耗透明）> 剩余候选。
- **后果**：每轮维持全门禁 + 发布链；设备实测数字仍由 CI 真机矩阵执行（本环境无模拟器不伪造）；Affective 冻结不受影响。结项标准：渲染循环可见性/生命周期锚点 + 低功耗降级路径锚点 + ADR-065 结项记录。
