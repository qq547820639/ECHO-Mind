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
