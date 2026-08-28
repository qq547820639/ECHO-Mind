# P2/P3 台账（HEAD b90b5da 复核）

复核基线：HEAD `b90b5da`（c598143 已修复 P0×2 / P1×22）。全部 61 条 P2 逐条在当前代码复核；105 条 P3 按文件聚合供各模块子代理「触碰文件内顺手修」。
指派任务列 = 本轮深化迭代对应的模块子代理（T2 视觉链 / T3 感知身份 / T4 app / T5 领域特性 / T6 backend / T7 边界 / T8 测试质量）。

## P2 分诊总表

| ID | 来源 | 位置 | 问题摘要 | 现状（证据） | 分诊 | 指派任务 |
|---|---|---|---|---|---|---|
| T2-P2-1 | T2-visual-chain.md P2#1 | OrganismTopology.kt:132-135 | 拓扑缓存 clear-on-overflow：15 键组合 > MAX_CACHE=8，双 identity 交替即逐帧全量重建；getOrPut 内 clear 有竞态 | 仍存在：L133 `if (cache.size > MAX_CACHE) cache.clear()` 原样 | FIX_THIS_ROUND（改按 key LRU/提高容量） | T2 |
| T2-P2-2 | T2-visual-chain.md P2#2 | OrganismTopology.kt:180-254 | DeterministicRandom 盐空间大量冲突（longs 400+7i / frags 500+5i 与 540-542+i / particles 600+i 与 610/615/630 / knots 700-720+i 互撞） | 仍存在：L180(400+i*7)、L195-207(500+i*5, 540/541/542+i)、L227(600+i) vs L212(610+i)/L221(615+i)/L240(630+i)、L249-253(700/710/720+i) 全部原样 | FOLLOW_UP（改盐=全部 ECHO 视觉身份变化，需黄金集与视觉画廊整体再生成 + 产品确认，超出单轮） | T2 |
| T2-P2-3 | T2-visual-chain.md P2#3 | EchoRenderPacket.kt:44-57 + AgslEchoBackend.kt:43-48 | 材质双事实源：shader 硬编码 knee=0.58/comp=2.4，packet 字段无后端消费；warmAreaCap/highlightCap 无执行路径 | 仍存在：AgslEchoBackend.kt:44-45 `const half knee = 0.58; comp = 2.4`，无 uniform 注入 | FIX_THIS_ROUND（shader 改由 packet 同值注入 uniform，视觉零变化；未消费字段收归 QA 门或删除） | T2 |
| T2-P2-4 | T2-visual-chain.md P2#4 | OrganicNoise.kt（全文件） | 整模块死代码：fbm1/membraneRadius/driftPhase 生产与测试零引用 | 仍存在：全仓 grep 仅 SOURCE_MANIFEST / VISUAL_RUNTIME_V3_SOURCE_REALITY.md / 自身 | FIX_THIS_ROUND（删除 + SOURCE_MANIFEST 再生成） | T2 |
| T2-P2-5 | T2-visual-chain.md P2#5 | AgslEchoBackend.kt:255-307 | AGSL 静默丢弃 ripples/halos/frontMembrane/atmosphere 层与 cavity 谐波形变，两后端行为不对齐 | 仍存在：rasterizeMask 仅 4 类 stroke + particles + knots + warmAccents（L278-306）；shader cavity 仍正圆 smoothstep（L79-81） | FOLLOW_UP（需两后端能力矩阵设计 + AGSL shader 扩展 + 黄金集再生成，超出单轮；可先在 KDoc 明示能力矩阵） | T2 |
| T2-P2-6 | T2-visual-chain.md P2#6 | VisualGenomeCompiler.kt:23-48 | KDoc 宣称参数→genome 一一映射，实际 contrast/accentIntensity/structureComplexity 被静默丢弃 | 仍存在：EchoVisualParameters.kt:36-40 三字段在，compile() 无对应映射，KDoc 仍称「一一映射」 | FIX_THIS_ROUND（KDoc 明示「视觉不消费」，消除参数语义假象） | T2 |
| T2-P2-7 | T2-visual-chain.md P2#7 | EchoPortraitSnapshot.kt:29 + JourneyVisuals.kt:85 | PORTRAIT_/JOURNEY_CANONICAL_TIME_SECONDS 同值 12f 双常量重复事实源 | 仍存在：两处 `= 12f` 并存（全仓 22 处引用分裂在两常量间） | FIX_THIS_ROUND（journey 复用 core:visual 常量） | T2+T5 |
| T2-P2-8 | T2-visual-chain.md P2#8 | OrganismCanvasRenderer.kt:42-159 | CANVAS draw() 每帧分配 12 个 Paint + 多个 RadialGradient + cavityPath，Wallpaper/Dream 热路径无复用 | 仍存在：L42/58/71/80/91/98/105/113/123/131/140/154 全为局部 Paint | FIX_THIS_ROUND（提为成员按需重置，参照 AgslSession） | T2 |
| T2-P2-9 | T2-visual-chain.md P2#9 | OrganismFrameComputer.kt:202,265 | cavity 半径公式 `(0.30f+0.06f*coreOpenness)*breathScale` 两处独立计算 | 仍存在：L202 与 L265 同式重复 | FIX_THIS_ROUND（单次计算复用） | T2 |
| T2-P2-10 | T2-visual-chain.md P2#10 | OrganismFrameComputer.kt:627-628 + OrganismFrame.kt:55 | depth01 输出上限 0.5 动态范围减半；StrokePoint KDoc「-1..1」与实现 0..1 矛盾 | 仍存在：L628 公式原样；OrganismFrame.kt:55 KDoc 仍写「归一化深度 -1..1」 | FIX_THIS_ROUND（满幅映射 + 修 KDoc + 黄金集同步） | T2 |
| T3-P2-1 | T3-presence-identity.md P2#1 | LifeSeasonTracker.kt:112-141 | 候选分支只推进 computedDays，不推进 daysSinceCommit/agreeDays → confidence 虚高 | 仍存在：L136-141 else 分支仅更新 candidate/candidateStreak | FIX_THIS_ROUND（候选日计入 daysSinceCommit + 单测） | T3 |
| T3-P2-2 | T3-presence-identity.md P2#2 | DailyCompositionGate.kt:22-24 | hasCompositionForToday 仅判 cached != null，不校验 forDate == 今日 | 仍存在：L22-23 `get() = cached != null` 原样 | FIX_THIS_ROUND（补日期校验） | T3 |
| T3-P2-3 | T3-presence-identity.md P2#3 | VisualProfile.kt:92 | hasDaily 用哨兵值推断「日构图已填充」，二者皆 0 时误判回退旧路径 | 仍存在：L92 `daily.flowSpeed > 0f \|\| daily.coherence > 0f` | FIX_THIS_ROUND（状态/快照增加显式 daily 已填充标志） | T3 |
| T3-P2-4 | T3-presence-identity.md P2#4 | LocalPortraitEngine.kt:318-325 | SCREEN_TIMING 驱动指标是分钟数量（late_screen_minutes），取值标签却是时间方向词 EARLIER/LATER | 仍存在：L322 `classify(lateValue, lateStats, "EARLIER", "LATER", …)` 原样 | FOLLOW_UP（需产品决策换指标（如 last_screen_end_minute）或改量词标签，且端侧声称镜像后端 dimensions.py，需两端同步裁定） | T3 |
| T3-P2-5 | T3-presence-identity.md P2#5 | EchoIdentity.kt:126-147 | dayZeroSeedPresence KDoc 宣称「与第一次刷新完全一致/motionPersonality 同参同值」强于实现 | 仍存在：L131-132 KDoc 原文未改 | FIX_THIS_ROUND（KDoc 改为实际保证：seed 相关 6 维同参；motionPersonality ≤15% 当日塑形由 §60 平滑兜底） | T3 |
| T3-P2-6 | T3-presence-identity.md P2#6 | EchoPresenceState.kt:182-188 | 原 EchoStateStore 类注释悬挂在 PRESENCE_COPY_SEED_TITLE 常量之上（孤儿 KDoc） | 仍存在：L182-188 注释 + L190 常量紧随 | FIX_THIS_ROUND（删除或移至文件头） | T3 |
| T4-P2-1 | T4-app.md P2#1 | AppPreferences.kt:343-368 | clearServiceState 两步 apply：clear 落盘后恢复未落盘间进程死亡 → DB 秘密丢失 | 仍存在：L353 `clear().apply()` + L364 恢复 `.apply()` | FIX_THIS_ROUND（恢复写改 commit / 单次事务式编辑） | T4 |
| T4-P2-2 | T4-app.md P2#2 | EchoWallpaperService.kt:149,190-192 / EchoDreamService.kt:144-146 / AppPreferences.kt:488-499 | 视觉偏好三键 13 处字符串字面量复制，无共享常量锚点 | 仍存在：grep `presence_reduce_motion\|presence_motion_level\|presence_night_mode` 13 处硬编码 | FIX_THIS_ROUND（无 Android 依赖常量对象，两侧引用） | T4 |
| T4-P2-3 | T4-app.md P2#3 | EchoActionOverlay.kt:58,73-91 | genome compile 未 remember，withFrameNanos ticker 每帧重组执行完整 mapper+compile | 仍存在：L58 withFrameNanos + L73-91 裸 compile | FIX_THIS_ROUND（remember(presence,hourOfDay,config,mode)） | T4 |
| T4-P2-4 | T4-app.md P2#4 | AppContainer.kt:88-103 | 5 个 Transient 工厂方法（newSensorCollector/newScreenCollector/newAppActivityCollector/newMicCollector/newSensingEventHub/newSensingWindowScheduler）生产零调用 | 仍存在：L88-103 原样；grep 生产调用仅 AppContainer 自引用 | FIX_THIS_ROUND（删除） | T4 |
| T4-P2-5 | T4-app.md P2#5 | EchoPortraitStates.kt:33,85 | SeedPortraitBlock / PortraitSummaryOnly 生产死代码（仅 SmokeTest 引用） | 仍存在：src/main 仅定义处 2 hits，无 UI 调用 | FIX_THIS_ROUND（删除 composable + 同步测试） | T4 |
| T4-P2-6 | T4-app.md P2#6 | EchoDatabase.kt:227 + EscalationRepository.kt:88-95 + SupportSection.kt:53 | EscalationStatus.ACKNOWLEDGED 不可达：parseServerStatus 把 human_acknowledged→TAKEN_OVER | 仍存在：L91-93 映射原样，枚举值+字符串资源仍在 | FOLLOW_UP（需产品/服务端确认是否存在独立 ack 阶段，再决定删值还是改映射） | T4 |
| T4-P2-7 | T4-app.md P2#7 | PortraitRepository.kt:48-50 | observePortraits(days) 无视参数恒返回同一 _timelineState | 仍存在：L48-50 `= _timelineState` 原样 | FIX_THIS_ROUND（签名去 days 或 per-days 缓存） | T4 |
| T4-P2-8 | T4-app.md P2#8 | MeViewModel.kt:59-88 / DataAndSensingViewModel.kt:78-108 | 装配 lambda 主线程同步读 Keystore 解密（localMode→accessToken）/deadLetterCount()/capabilityState×6 | 仍存在：两 VM map 内同步 container.preferences.* 原样 | FIX_THIS_ROUND（输入快照化/Flow 化 + 解密结果缓存；中等改造，注意 UI 行为不变） | T4 |
| T4-P2-9 | T4-app.md P2#9 | VisualLabScreen.kt:171-173 | exportLab 主线程同步 1080×1920 离屏渲染 + PNG compress(100) + 双文件写盘 | 仍存在：onClick 内同步 exportLab 原样 | FIX_THIS_ROUND（移 IO 协程；debug-only） | T4 |
| T5-P2-1 | T5-domain-features.md P2#1 | PersonalAnswerEngine.kt:529-575 | travelContext 负 fromDay/toDay 直接数组越界（days[recent.fromDay] 无下界保护） | 仍存在：L554 `days[recent.fromDay]`、L555 toDay 仅 coerceAtMost；无输入校验 | FIX_THIS_ROUND（coerceAtLeast(0) + 负值/越界用例） | T5 |
| T5-P2-2 | T5-domain-features.md P2#2 | JourneyCanonical.kt:96-107 + JourneyVisuals.kt:29 | reconstructJourneyFrame fallback 不传 earliestDate，长期用户成熟度退 baselineDays | 仍存在：L96 `journeyDayParams(fallbackPortrait)`（默认 null）、L106 `earliestDate = null` | FIX_THIS_ROUND（重建入口接时间线上下文参数） | T5 |
| T5-P2-3 | T5-domain-features.md P2#3 | ContextExceptions.kt:23-45 | note 含（ ）@ 时 roundtrip 不闭合 → contextExceptionInfo null，日期与 kind 双丢 | 仍存在：L27-31 原样 append，L62-63 regex 无转义 | FIX_THIS_ROUND（转义或拒绝保留字符 + 边界用例） | T5 |
| T5-P2-4 | T5-domain-features.md P2#4 | journey 多文件（JourneyLayer.kt 全套、JourneyVisuals/JourneyDomain 死函数群、frameFor/snapshotFor/reconstructFrame） | 旧渲染链死代码群 + journeyAggregateParams vs journeyAggregateOfDays 双事实源 | 仍存在：全部函数在位；buildJourneyDays/journeyAggregateParams/assembleJourneyLayer 仅测试引用；reconstructJourneyFrame 仅 QA（VisualReviewRenderer.kt:60）+测试 | FIX_THIS_ROUND（机械删除 + 测试清理 + 清单再生成） | T5 |
| T5-P2-5 | T5-domain-features.md P2#5 | JourneyOrganismVisuals.kt:146-152 | genomeFromParams 默认 dayComposition=0.47f 无条件覆盖编译结果，canonical v2 字段 21 重建时丢弃 | 仍存在：L149 默认值 + L152 copy；调用方（JourneyCanonical:83/97、QaTimeline:207、JourneyMemoryView:58/135）均不传第三参 | FIX_THIS_ROUND（接线 canonical.dayComposition / fallback 按日期派生 + 黄金图同步） | T5 |
| T5-P2-6 | T5-domain-features.md P2#6 | ProviderConfigValidator.kt:22-31 | normalizeBaseUrl 注释「/v1/xxx 不重复追加」与实现不符（只判 endsWith("/v1")）；withoutV1 两分支同值死变量 | 仍存在：L29 `val withoutV1 = if (url.endsWith("/v1")) url else url` 原样 | FIX_THIS_ROUND（修正逻辑 + 删死变量 + 补 `/v1/chat` 用例） | T5 |
| T5-P2-7 | T5-domain-features.md P2#7 | WearablePrivacyProjector.kt:40-65 | headlineFor KDoc（默认→null / WHY 门控 / 其余 null）与实现（无 WHY 参、永不 null）漂移 | 仍存在：L47-65 实现原样，KDoc 原样 | FIX_THIS_ROUND（函数对齐契约或 KDoc 如实化 + 调用方保证测试锚定） | T5 |
| T5-P2-8 | T5-domain-features.md P2#8 | QaHeadlineEngine.kt:63-75 | QA 差异日一句话自有词表（publicVoice），与生产 Scene Layer1 双事实源 | 仍存在：publicVoice 词表原样（「今天开始得比通常慢一些。」等） | FIX_THIS_ROUND（QA 优先消费生产 portrait.summary/learningPhaseHeadline，QA 词表仅兜底并标注） | T5 |
| T5-P2-9 | T5-domain-features.md P2#9 | QaProductSnapshot.kt:110-152 | Journey 地标为 QA 手写镜像（不走 buildLandmarks）+ memory 装配传空 JourneyMemoryAssemblyInputs | 仍存在：L114-128 硬编码地标、L136 `JourneyMemoryAssemblyInputs()` 空输入 | FIX_THIS_ROUND（走生产 buildLandmarks + specialWindows 映射 contextExceptions 喂装配） | T5 |
| T6-P2-1 | T6-backend.md P2#1 | app/api/skills.py:144-168 | POST /v1/skills/completions 无订阅门禁 | 已消失：skills.py:163-168 已挂 require_write_role + require_active_subscription（代码注释标明「P1-1/P2-1 修复」） | ABSORBED（被 c598143 P1-1/P1-2 修复吸收） | — |
| T6-P2-2 | T6-backend.md P2#2 | app/api/portraits.py:99（现 106） | fallback 有效日查询硬编码 `coverage_score >= 0.25`，与 baseline.calculator.MIN_COVERAGE 重复字面量 | 仍存在：portraits.py:106 字面量原样，无 MIN_COVERAGE 引用 | FIX_THIS_ROUND（改引用常量） | T6 |
| T6-P2-3 | T6-backend.md P2#3 | app/api/skills.py:97-147 + app/api/admin.py:94-110 | GET 路由写副作用（append_audit + db.commit），违背「GET 绝不写库」自家约定 | 仍存在：skills.py L104/114、L137/147；admin.py L80/90、L101/110 | FIX_THIS_ROUND（GET 去写副作用 + 测试同步） | T6 |
| T6-P2-4 | T6-backend.md P2#4 | app/api/escalations.py:83 + services/escalation.py:31-37 | trigger 为客户端自由字符串，传豁免词（help_requested 等）即绕过 20/h 上限 | **已清偿（2026-08-28 端到端交付轮，P0-3）**：删除 `ESCALATION_CREATE_EXEMPT_TRIGGERS` 客户端自证豁免；新增 `resolve_rate_limit_exemption()`，豁免只授予**服务端写入的证据**（L0 准入 `current_danger=True` / 服务端红色 `RiskSignal`），且须落在 30 分钟新鲜窗口内；`trigger` 降级为展示标签；伪造留痕 `escalation.exemption_denied`、放行留痕 `escalation.rate_limit_exempted`。`backend/tests/test_support_rate_limit.py` 扩至 10 例（含伪造豁免负向、跨用户证据、陈旧证据不豁免）全绿 | DONE | T6 |
| T6-P2-5 | T6-backend.md P2#5 | models.py Escalation 表 | 列表按 (opened_at desc, id desc) keyset 分页但缺 (tenant_id, opened_at) 复合索引 | 仍存在：Escalation `__table_args__` 仅唯一约束（models.py:275），opened_at 无索引；alembic 无新迁移 | FIX_THIS_ROUND（迁移 + 复合索引） | T6 |
| T6-P2-6 | T6-backend.md P2#6 | app/api/narratives.py:64-92 | from/to 无范围上限：1970→9999 可生成约 3M 个 missing_dates（认证用户 DoS 面） | 仍存在：L64-66 仅校验 from<=to，无任何 clamp | FIX_THIS_ROUND（范围上限 clamp + 测试） | T6 |
| T6-P2-7 | T6-backend.md P2#7 | app/api/data_rights.py DSR delete | 删除后 users.refresh_token_hash/refresh_expires_at 未清除，已「删除」用户凭证仍可换新 token | 仍存在：data_rights.py 全文无 refresh_token 处理（grep 0 hits；P0-1/P1-6 修复未覆盖此项） | FIX_THIS_ROUND（delete 时吊销凭证 + 测试） | T6 |
| T6-P2-8 | T6-backend.md P2#8 | services/scoring.py + services/safety.py(evaluate_text) | 死代码：score_phq9/score_gad7/evaluate_text 生产零调用（仅测试引用） | 仍存在：生产调用 0（test_scoring/test_workbench_v03/test_safety 引用） | FOLLOW_UP（审计已认定为 v0.8 计划内退役物；删除需同步问卷 410 存根/content-packs 语义与 workbench 历史只读口径裁定） | T6 |
| T6-P2-9 | T6-backend.md P2#9 | app/api/onboarding.py:187（现 189） | legacy 回退 `select(User).where(external_ref == code)` 无租户过滤无 order_by，多租户同 ref 命中不确定 | 仍存在：L189 查询原样（c598143 仅加了 P0-2 的 commit 修复） | FIX_THIS_ROUND（加确定性排序/收紧语义；该路径已标 v0.8 退役） | T6 |
| T6-P2-10 | T6-backend.md P2#10 | models.py AuditEvent 表 | audit_events 高频 append 读头查询缺 (tenant_id, occurred_at) 复合索引 | 仍存在：全 models.py 仅 3 处 Index()（activation_codes/activation_attempts/derived_features），audit_events 无 | FIX_THIS_ROUND（迁移 + 复合索引） | T6 |
| T7-P2-1 | T7-boundary.md P2#1 | sbom.spdx.json | SBOM 为 pyproject 回退版（45 包）而非 uv.lock 闭包（80 包），静默降级 | 已消失：实测 packages=80（c598143 以 python3.12 全 lock 闭包再生成） | ABSORBED（被 c598143 发布元数据再生成吸收） | — |
| T7-P2-2 | T7-boundary.md P2#2 | scripts/update_release_metadata.py:163-174 | DELIVERY validation 硬编码 passed/650/4/ci_emulator_gate，--skip-preflight 下照样输出 | 仍存在：L168-172 硬编码原样 | FIX_THIS_ROUND（先做诚实化：skip/未实测路径输出 not_run 而非 passed；全量真值注入留待 CI 管线改造） | T7 |
| T7-P2-3 | T7-boundary.md P2#3 | scripts/check_dynamic_code.py + claim_scan.py | 静态安全门禁停留在单 module 时代：只扫 backend/app + android/app/src/main | 仍存在：两脚本 grep `android/feature\|android/core` = 0 命中；claim_scan.py TARGETS 仍 2 目录 | FIX_THIS_ROUND（参照 generate_source_reality.py 多模块自动发现扩展 + 清理新暴露项） | T7 |
| T7-P2-4 | T7-boundary.md P2#4 | scripts/verify_final_package.py:99-112 | 包内无 SOURCE_MANIFEST 时第 4/5 步校验整体静默跳过；provenance 记录的 APK 不在包内也不报 | 仍存在：L99-108 manifest=None → 跳过不计 problems；L81 `apk.is_file()` 为假即过 | FIX_THIS_ROUND（缺失计入 problems） | T7 |
| T7-P2-5 | T7-boundary.md P2#5 | wearable/xiaomi-vela/tests/simulator/generate.js vs 生产 .ux/echo.css | 模拟器「逐行镜像」失真：旧全谱色板（含被禁红/橙）、空态尺寸类不一致、V3 §73 结构（粒子/loops/spin/中空核）全部缺失 | 仍存在：L197-204 仍 rgb(230,103,103) 等旧全谱 | FOLLOW_UP（V3 结构完整镜像工作量明显超出单轮；色板与空态尺寸两个子项可由 T7 顺手修） | T7 |
| T7-P2-6 | T7-boundary.md P2#6 | wearable/xiaomi-vela/src/action/index.ux:193-207 | 触觉速率限制 ≥1.5s 未实现（契约 §7），仅 hapticsEnabled 硬门 | 仍存在：vibrateShort/vibrateLong 无节流原样 | FIX_THIS_ROUND（加 1.5s 节流 + Vela 测试） | T7 |
| T7-P2-7 | T7-boundary.md P2#7 | docs/STATUS.md:29,121,129 | STATUS 数字漂移：HEAD 锚点、Vela 测试计数、SOURCE_MANIFEST 文件数 | 仍存在：L29 仍 `d3c87fa`（现 HEAD b90b5da）、L121 仍「24/24」（c598143 后 run.js 为 28/28）、L129 仍「1171 文件」（现 SOURCE_MANIFEST 为 1313） | FIX_THIS_ROUND（重跑 refresh_status_numbers.py + 手修计数） | T7 |
| T7-P2-8 | T7-boundary.md P2#8 | README.md:127 + docs/STATUS.md | 「五套 CI 门禁实测全绿」对 release-closure 不成立（无 CI 实测记录） | 仍存在：README L127 原句；BUILD_PROVENANCE builder_environment 已改 "ci" 但 ci_run_id 仍为 ""（无实际 run 佐证） | FIX_THIS_ROUND（措辞诚实化：closure 门禁标注为本地/无 CI run 记录；真 CI 化留待基建） | T7 |
| T7-P2-9 | T7-boundary.md P2#9 | scripts/smoke.sh:4-7 | curl -s 无 -f（HTTP 4xx/5xx 静默「通过」）；依赖 `python` 命令名；假定 8000 端口已起 | 仍存在：L4-7 原样 | FIX_THIS_ROUND（curl -f + python3 回退 + 端口探测提示） | T7 |
| T8-P2-1 | T8-test-quality.md P2#1 | JourneyRiverGoldenTest.kt:43-45 | 声称验证 identity evolution 连续可辨，断言仅 PNG>1KB + 末尾 assertTrue(true) 永真 | 仍存在：L43/L45 原样 | FIX_THIS_ROUND（补跨帧 accent 色相恒定/结构连续像素级断言，参照 VisualReviewRenderTest） | T8 |
| T8-P2-2 | T8-test-quality.md P2#2 | BaselineConductionTest.kt:45 等 | 枚举序数编译期常量比较永真；命名/注释延续 baseline.validDays 过时心智模型 | 仍存在：L45 `assert(KNOWN.ordinal <= MATURE.ordinal)`；文件头注释与测试名未改 | FIX_THIS_ROUND（改真实 BASELINE_READY↔KNOWN 检查 + 改名/注释去 validDays 心智） | T8 |
| T8-P2-3 | T8-test-quality.md P2#3 | PresenceRepository.maturityCalendarDays:166 + JourneyVisuals.portraitMaturityProxy | echoMaturity 日历钟只测阶梯未测钟（时区/跨午夜/DST 装配无测试） | 仍存在：maturityCalendarDays 仍 private、测试 0 引用；portraitMaturityProxy 测试 0 引用 | FIX_THIS_ROUND（提纯函数/注入用例补钟装配测试） | T8 |
| T8-P2-4 | T8-test-quality.md P2#4 | AiNarrativeService.longitudinalNarrative:159 | 生产公开 API（JourneyRepository:105 调用）全仓零测试 | 仍存在：grep 仅生产定义 + 调用 2 处，测试 0 引用 | FIX_THIS_ROUND（补 fallback/词表门禁/来源标注用例） | T8 |
| T8-P2-5 | T8-test-quality.md P2#5 | backend/tests/test_version_consistency.py:142-144 | test_postgres_migration_roundtrip 永久 skip + 函数体仅 docstring，制造「已覆盖」假象 | 仍存在：L142 @pytest.mark.skip（已补 reason 文案，占位实质未变） | FOLLOW_UP（PG round-trip 需 CI postgres service job 基建实装才能真实落地） | T8 |
| T8-P2-6 | T8-test-quality.md P2#6 | feature/qa DebugJourneyTest.kt + OrganismQualityPerfTest.kt | 零断言测试混入正式 suite（纯 println / 仅 stdout 证据无预算门） | 仍存在：DebugJourneyTest 纯 println 原样；OrganismQualityPerfTest L71-73 仅打印无断言 | FIX_THIS_ROUND（删 DebugJourneyTest；PerfTest 补性能预算断言） | T8 |
| T8-P2-7 | T8-test-quality.md P2#7 | E2EFlowTest.kt:549-553 | emptySkillListRepresentsColdStart 对测试自建 emptyList() 断言 isEmpty，纯永真 | 仍存在：L551-552 原样（c598143 重写该文件时未触及此条） | FIX_THIS_ROUND（删除或改测生产冷启动映射） | T8 |
| T8-P2-8 | T8-test-quality.md P2#8 | 跨模块（app/core:visual/presencevisual/intelligence/backend 多路径） | 关键生产路径无测试触达（EchoSceneViewModel 交互群/SyncStateRepository 观察群/EscalationRepository/MotionPolicy 三函数/EchoRenderEnvironment 降级群/AiProviderManager.providerFor/crypto.decrypt_text/POST /users 等） | 仍存在：motionScaleFor/reducedMotionFor/defaultQualityFor、retryPortrait、observePendingCount 等仍 0 测试引用；子项 rebuildTodayPortrait 已被吸收（SourceIntegrityTest:188 + LocalModeTest:258，随 P1-1 修复补测） | FIX_THIS_ROUND（分模块认领高价值项；rebuildTodayPortrait 子项勿重复） | T8（分派各模块） |

## P3 聚合清单（按模块分组，供顺手修）

标注：★ = 与本轮 FIX_THIS_ROUND 修复同文件（顺手修优先级最高）；○ = 同模块但本轮 P2 修复不直接触碰该文件（按需认领）。P3 不做逐条代码复核，按审计时点记录聚合。

### T2 视觉链（16 项）
- ★ EchoRenderPacket.kt：①:61-69 EchoMotionSpec KDoc 区间漂移（breath/orbit/filamentPhase 与实现不符）；②:101 EchoInteractionSpec.radius=0.34f 死字段；③:115 canonicalTimeNanos 编译后零消费（+EchoSceneCompiler.kt:70）；④EchoMaterialSpec.hdrAllowed 无后端消费（随 T2-P2-3 一并处理）
- ★ AgslEchoBackend.kt:287 粒子 mask B 通道硬编码 200（SceneParticleV3 无 depth；随 T2-P2-3 处理）
- ★ OrganismCanvasRenderer.kt：①:222-225 私有 withAlpha 与 ColorSpace.Argb.withAlpha 重复；②:157 warmAccent 用 WARM_GOLD 而 AGSL 用 palette.warm 双源（+EchoOrganismRenderer.kt:272）；③:82 Halo 借 frontMembrane.color 隐藏耦合（+EchoOrganismRenderer.kt:195）（均随 T2-P2-8 重构顺手处理）
- ✓ ColorSpace.kt:108-109 BG_CENTER/BG_EDGE 死常量删除（R10）
- ✓ EchoIdentitySpec.kt:70,75 lobeCount/baseFrequency 添加 coerceIn(2,5) 边界保护（R13）
- ✓ DeterministicRandom.kt:27-29 range KDoc 已为 [min, max] 闭区间（R9 确认）
- ✓ VisualLabMetrics.kt:49-50 @Deprecated highlightRatio getter 删除（R10）
- ✓ MotionEvaluator.kt:34 interactionEnvelope KDoc 明确 reserved（R16 补充）
- ✓ EchoRendererFacade.kt:163 correctionPulseTrigger Long→Int 类型修正（R10）
- ✓ VisualLabFixtures.kt:30 warmAccent KDoc 标注 reserved（R16 补充）
- ✓ WristVisualSpec.kt:38 WristVisualProjector KDoc 标注保留要求（R17）
- ○ D7 测试缺口 6 项：ColorSpace.lch 单测 / OrganicNoise（随 T2-P2-4 删除即消）/ VisualLabMetrics JVM 单测 / sampleStroke 触摸形变单测 / renderToBitmap+offscreenActualBackend 单测 / 盐冲突防护测试（随 T2-P2-2 FOLLOW_UP）

### T3 感知与身份（18 项）
- ★ EchoIdentity.kt:264-265 computeLifeSeason z 全缺提前返回丢已算信息（随 T3-P2-5 同文件顺手）
- ★ EchoPresenceState.kt:121 phaseIndex KDoc「1=7-30;2=30-90」边界矛盾（随 T3-P2-6 同文件顺手）
- ✓ EchoPresenceCodec.kt:37/99 KDoc 字段名 updatedAtEpochMs → updatedAtEpochSec 对齐实现（R12）
- ✓ LocalPortraitEngine.kt / LocalPortraitDigest.kt 六维列表统一为 PORTRAIT_DIMENSIONS（R14）
- ○ PortraitCore.kt:227-238 与 LocalPortraitDigest.kt:31-67 「最接近/变化明显」统计双实现（设计意图：Journey 页 vs 消息小结，暂不合并）
- ✓ SensingEventHub.kt:153-155 trim 改用 AtomicInteger 计数器，O(n) → O(1)（R12）
- ✓ SensorCollector.kt:72-73 registerListener 添加 sensorHandler 指定线程（R20）
- ○ ScreenCollector/SensorCollector/AppActivityCollector 写 hub 无 consent 门控（已知 fail-soft 隐私缺口；NotificationCollector 已门控）
- ✓ MicFeatureExtractor.kt:16 措辞改为「基础声学基频特征」（R17）；v[5]/v[6] 归一化留 P3 观察
- ✓ FeatureExtractor.kt:278-311 carry 贯穿窗口时防止重复累计（hasCarryThroughWindow 标记）（R22）
- ✓ AmbientEngine.kt:106 已用 maxOrNull()（R12 确认）
- ✓ DatabaseOpenOrchestrator.kt:66-79 verify 失败异常直抛，不掩盖真实原因（R10）
- ✓ KeystoreKeyProvider.kt:74-88 key() 方法添加 synchronized 锁防止双检竞态（R18）
- ✓ CipherSelfHealing.kt:15-31 resolveCipher KDoc 补充 build lambda 约定（R17）
- ✓ Models.kt:237 PortraitDimensionDto.parse 使用 org.json KDoc 已澄清为必要例外（R18）
- ✓ PresenceSurfacePolicy.kt:61 KDoc 与 15 分钟刷新节奏一致（R14 确认）
- ○ D7：observation 测试寄宿 :app（需评估迁回 :feature:observation）；SensingCapabilities.capabilityState 无单测

### T4 app 模块（17 项）
- ★ AppPreferences.kt identitySeed getter check-then-act 非线程安全（随 T4-P2-1 同文件顺手）
- ★ AppContainer.kt 通配符导入 `data.database.*`（随 T4-P2-4 删除死工厂时顺手改显式）
- ✓ PassiveSensingService.stopMic 改用 startForegroundService（Android 14+ 后台兼容）（R25）
- ✓ MicCollector.scope 已改用 SupervisorJob + cancel()（R22）
- ✓ MainActivity.kt:84 ifBlank 死分支删除（simpleName 永不为空）（R17）
- ✓ EchoDreamView onDetachedFromWindow：注释已说明「View 脱离窗口后 invalidate 不再触发 onDraw，回调链自动停止」；实害≈0，无需额外修复
- ✓ EchoSceneScreen.kt:231 askOpen 由 remember → rememberSaveable（R13）
- ✓ EchoStatusOverlay KDoc 与实现一致（else -> Unit 即非 ACTIVE 时隐藏，R15 确认）
- ✓ SkillSessionCoordinator.kt:125-127 touchDuration 为单行表达式，无空 let 块（LEDGER stale，R16 修正）
- ✓ MeScreen:467 已用 maturityLabel（learningPhaseHeadline）展示中文（R13 确认）
- ✓ EchoConversationLayer chips 改用 FlowRow 与 EchoInlineEvidence 一致（R15）
- ○ OnboardingScreen SensingCapabilityStatus vs model CapabilityState 平行枚举（需架构决策统一）
- ✓ MemoryManagementViewModel.init 已用 viewModelScope.launch 异步采集（LEDGER stale 误报）
- ○ ApiClient/AuthTokenRefresher 阻塞 IO：已知设计，调用方须在协程中调用
- ✓ EchoAskScreen.EchoAskMiniOrganism session remember 键已包含 maturityName（R20）
- ✓ EchoMindApp.QuietWorldBar radialGradient 提到 Composable 顶层计算（R21）
- ✓ EchoSceneScreen 三 slot 共享同一 config，用 remember 避免每次重组重复 IO（R21）

### T5 领域特性（12 项）
- ★ JourneyDomain.kt:79-84 / JourneyVisuals.kt:89-96 journeyRepresentativeDay/Index「平手取最近」实取最旧 —— 该函数本身在 T5-P2-4 死代码清单内，删除即消
- ✓ JourneyStory.kt:173 shiftedBehaviorAspects 已按 abs(delta) 占比差排序（LEDGER stale 误报）
- ✓ JourneyStory KDoc 已修正为「第 31-37 天窗口」与实际实现一致（R23）
- ✓ JourneyRiver.kt:59-75 pulsePeriodSeconds 归一化到 0..1 后参与欧氏距离（R18）

- ✓ GroundingValidator：emo 改用 emo 单词边界（R9）；claim 否定/label.take(6) 留 P3 观察
- ✓ QaDaySimulator.kt:77-81 lateScreen 噪声 overflow 修复：base clamp → add noise → final clamp（R19）


- ✓ JourneyYearView.buildYearView:128 跨季 contextPeriod 改用区间相交判定（start≤seasonEnd && end≥seasonStart）（R21）
- ✓ AiProviderManager healthCheck/reason 已 normalize baseUrl（R20）；extraHeaders/timeout 字段缺失属架构债，留待 schema 扩展时处理
- ✓ WearableRuntime 构造器 cache initialRevision 消除 3x load revisionStore（R23）

### T6 backend（12 项）
- ★ onboarding.py:47 bootstrap key 用 != 非常数时间比较（随 T6-P2-9 同文件顺手改 compare_digest）
- ★ admin.py /v1/config/flags 无角色/状态限制（随 T6-P2-3 同文件顺手评估）
- ★ admin.py build_tenant_portrait / escalation_metrics Python 侧聚合可下推 SQL（同上顺手）
- ★ services/audit.py verify_audit_chain 全表载入排序（随 T6-P2-10 同域索引工作顺手）
- ✓ main.py:58-62 call_next 异常时安全头兜底（except 分支返回 500 + 安全头）（R21）
- ○ /v1/auth/refresh 无速率限制（备忘网关层）
- ○ deps.py:180-186 open_escalation 幂等键 event_id 客户端可控（信息量极小，暂不修复）
- ✓ deps.py:forbid() 改用 try/commit 或 rollback 防止连带提交（R23）
- ○ deps.py list_journals limit*4 截断：revision 密集时可能少于 limit（已知边界行为）
- ○ sandbox/_check_sandbox_rate aware cutoff vs naive SQLite 列：依赖写入时区纪律（已用 UTC，备忘）
- ○ _execute_dsr_delete Core 批删绕过 ORM immutability guard（矩阵现无 append-only 表；护栏备忘）
- ✓ console.html renderCard 修正：仅 breach 卡片着红边（R23）

### T7 边界（22 项）
- ★ docs/STATUS.md 尾部约 200 行空行 + DELIVERY git_ref 硬编码模式（随 T7-P2-7 同文件顺手）
- ✓ wear_protocol.js:147-148 action/ack/observation 返回 {ok:false} 无 malformed flag（R8）
- ✓ presence_cache.js state() degraded 分支 null-safe moment/surface 访问（R8）
- ○ 腕上 messageId 去重：Android WearableRuntime 已实现 seenMessageIds；手环端发送前无去重（已知设计，同 revision 重复无害）
- ✓ echo/index.ux onConnectionChanged 添加 connected 判断，断开时不请求（R23）

- ✓ i18n 死键 echo_time/maturity_* 共 6 键已从 wearable/i18n 源文件删除（R25）
- ✓ preflight.js [--src] 参数已解析（R25）；红色正则已含 d00000/c00（LEDGER stale）
- ✓ hue-8 桶量化提取到 wear_visual.hueBucket() 单一来源（R7）
- ○ 模拟器 ALLOWLIST 是手机端 HeadlineAllowlist 硬拷贝无一致性门（与 T7-P2-5 FOLLOW_UP 关联）
- ✓ release_preflight.sh 空操作 case 分支已移除（R25）
- ✓ build_source_archive.py 添加 exists() 守卫，不再覆盖既有产物（R25）
- ✓ build_final_package.py mtime 改用 dist.commit_timestamp_utc(repo)（R9）
- ✓ generate_provenance.py artifact_manifest_lines 移除未使用 provenance/version 参数（R9）
- ✓ fault_injection_check.py ANDROID_MAIN/ANDROID_TESTS 死常量删除（R9）
- ✓ verify_workflow_pins.py PyYAML 缺失时明确报错（R9）
- ✓ audit_dependencies.py pip-audit rc=2 现正确标记为 error（R11）
- ○ affective_eval.py --mock-provider/--endpoint 模式忽略 --fixtures（已知行为设计）
- ✓ verify_golden.py json_equal 修正 bool/int 混同（type 优先检查）（R25）

- ✓ pip install uv/pytest 版本锁定（R11）

### T8 测试质量（8 项）
- ★ E2EFlowTest 类注释「E2E 数据流」名实不符（随 T8-P2-7 同文件顺手改名或补真实链路）
- ✓ EchoIdentitySpecTest KDoc 已修正 palette 色域描述（R32）
- ✓ VisualRegressionGoldenTest.frameHash localFragments 只 feed size（设计意图：结构指纹不计位置，有意选择）
- ○ EchoSceneCompilerTest.breathWindowAndSurfaceAmplitudes：单 seed 单 surface 测试覆盖（可考虑多 seed 扩展，非阻塞）
- ○ OrganismGoldenRenderTest.hash step=17 抽样（有意设计，备忘）
- ○ HardeningV061Test 手工 set→读回验证（有意设计，备忘）
- ✓ VisualRuntimeV3RegressionTest oldRenderPipelineStaysDeleted 已实现文件存在性检查（R26）
- ○（其余见 P2-8 覆盖缺口清单，随 T8-P2-8 分模块认领时一并考虑）

## 分诊统计

**P2 共 61 条：FIX_THIS_ROUND = 51 ／ FOLLOW_UP = 8 ／ ABSORBED = 2（仍存在 59）。**

### FOLLOW_UP 清单（8 条，附原因）
1. **T2-P2-2**（OrganismTopology 盐空间冲突）——改盐 = 全部 ECHO 视觉身份变化，需黄金集与视觉画廊整体再生成并经产品确认，超出单轮。
2. **T2-P2-5**（AGSL 丢弃多层）——需两后端能力矩阵设计 + AGSL shader 扩展 + 黄金集再生成，超出单轮；可先补 KDoc 能力矩阵。
3. **T3-P2-4**（SCREEN_TIMING 量-时混义）——需产品决策（换时间点指标 or 改量词标签），且端侧镜像后端 dimensions.py 需同步裁定。
4. **T4-P2-6**（ACKNOWLEDGED 不可达）——需产品/服务端确认升级状态机是否存在独立 ack 阶段，再决定删值或改映射。
5. **T6-P2-4**（升级事件 trigger 豁免绕过）——豁免判定须改为服务端可验证信号源（涉及安全设计，需评审）。
6. **T6-P2-8**（scoring.py/safety.evaluate_text 死代码）——v0.8 计划内退役，删除需同步问卷入口/content-packs/workbench 历史只读口径。
7. **T7-P2-5**（Band10 模拟器三处漂移）——V3 §73 结构完整镜像（粒子/loops/spin/中空核）明显超出单轮；色板与空态尺寸两子项可顺手修。
8. **T8-P2-5**（PG 迁移 round-trip 占位 skip）——需 CI postgres service job 基建实装。

### ABSORBED 清单（2 条）
1. **T6-P2-1**（skills completions 缺订阅门禁）——被 c598143 的 P1-1/P1-2 修复吸收（require_write_role + require_active_subscription，skills.py:163-168 有注释锚点）。
2. **T7-P2-1**（SBOM 回退 45 包）——被 c598143 发布元数据再生成吸收（python3.12 全 lock 闭包，实测 80 包）。

另注：T8-P2-8 中 rebuildTodayPortrait 子项亦已被 P1-1 修复顺手补测（SourceIntegrityTest.kt:188、LocalModeTest.kt:258），该条目整体仍判仍存在（其余路径缺口未补）。


## 最终处置结果（Task 9 收口，2026-08-18）

- **FIX_THIS_ROUND 51/51 全部完成**：T2 10/10（含主代理裁定实施的盐分段与 AGSL 层对齐）、T3 6/6、T4 9/9（ACKNOWLEDGED 按分诊注记）、T5 9/9、T6 7 FIX + 2 注记（1 ABSORBED）、T7 8 FIX（Band10 修 2 子项）+ 1 ABSORBED、T8 8/8。逐项证据见同目录 T2–T8-notes.md。
- **FOLLOW_UP 8 项维持**：T2-P2-5 的 cavity 谐波 shader 子项（KDoc 已注记设备门）、T3-P2-4、T4-P2-6、T6-P2-4、T6-P2-8、T7-P2-5 剩余结构镜像、T8-P2-5 PG CI 基建，及盐分段后黄金/画廊已随轮再生完成（不再挂起）。
- **P3 顺手修**：各组触碰文件内合计 20+ 项（T2 9、T3 2、T4 3、T5 2、T6 5、T7 若干、T8 5 处断言改写），明细在各 notes；其余 P3 留档于本台账聚合清单。
- **附带捕获**：T8 新测试暴露并修复 backend POST /users 重复 external_ref 500→409（flush 移入 try）。
- **黄金再生意图（统一声明）**：42 哈希因 T2 盐分段（独立随机流）+ T5 dayComposition 真实化 + T2 depth01 满幅映射 再生；canonical↔当日同帧一致性与 IA/导航 smoke 全绿佐证语义未破坏。
