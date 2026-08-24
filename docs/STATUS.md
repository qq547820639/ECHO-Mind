# ECHO Mind — STATUS（当前状态唯一锚点）

> 本文件是**当前可交付 main**（git 受控状态）的唯一状态锚点，每轮自主演进结束更新；禁止虚假完成状态。
> - 历史轮次细节 → `docs/CHANGELOG/`
> - 发布锚点 → `docs/RELEASE_BASELINE.md`
> - Build Status 段由 `scripts/refresh_status_numbers.py` 自动生成，禁止手写数字。

## 1. Current Development HEAD

| 字段 | 值 |
|---|---|
| Product Era | ERA 33 — ECHO Wrist / Second Body（ERA 32 治理/Personal Intelligence 升级已合入；ERA 33 新增 wearable 产品面：手机 = BRAIN，手环 = BODY + PRESENCE SURFACE，ONE ECHO） |
| 版本线 | v0.11.0（versionCode 8；开发态，不伪造已发布 v0.12.0——见 §6 批次 8） |
| 相对 Release Baseline | closure 收口文档提交（发布内容 = LAST_RELEASE_BASELINE `88db3b9`）；ERA 33 为开发态，Release Baseline 不更新 |
| 状态 | `pilot-candidate`（外部发布门未完成前不得标记生产上线）；Wearable = Developer Preview / Integration Preview（真机验证前不得宣称 Band10 Production Verified）。ERA 33 Production wiring 已接通（Application scoped 唯一启动点 + 入站自动 collect + Integration Test 全绿，见 §6 批次 8 状态表） |

## 2. Last Verified Release

| 字段 | 值 |
|---|---|
| 已发布版本 | v0.11.0（见 `docs/RELEASE_BASELINE.md` 完整锚点） |
| APK | `ECHO_Mind_v0.11.0.apk`（本地测试密钥签名 v2,v3；生产签名由运营环境执行） |
| Release 包 | `releases/ECHO_Mind_v0.11.0.release.zip` |

## 3. Build Status（自动生成）

<!-- AUTO:BUILD_STATUS:BEGIN -->

> 自动生成（`scripts/refresh_status_numbers.py`，git HEAD `3e1aff9e`，2026-08-24 20:26 UTC）；缺失实测产物处如实标注，禁止手写数字。

| 面 | 实测结果 |
|---|---|
| Android 单测（testDebugUnitTest） | **1387 全绿**（app 981 / core:visual 70 / feature:intelligence 46 / feature:journey 16 / feature:memory 7 / feature:presence 27 / feature:presencevisual 30 / feature:qa 113 / feature:wearable 97） |
| backend pytest | **1120 passed + 1 skipped**（全绿） |
| Production Kotlin | 240 |
| Test Kotlin | 173 |
| QA Kotlin（:feature:qa，非 Production Runtime） | 38 |
| Python | 69 |

<!-- AUTO:BUILD_STATUS:END -->

其余门禁（lint 4 安全规则 / detekt 27 规则 / mypy strict / ruff / 五套 CI 全 SHA 锁定）在
`docs/CHANGELOG/IMPLEMENTATION_STATUS_ERA31.md` 的最近轮次记录中可查，不在本文件重复维护数字。

## 4. Known Product Risks

- **无真机实拍**：Wallpaper 真机电池/帧率/进程死亡场景尚未真机采集（清单 `qa/visual-review/DEVICE_CHECKLIST.md`，
  采样脚本 `scripts/collect_wallpaper_metrics.sh`）；软件侧锚点已修复（快照 commit 落盘 + 恢复回归）。
- **人眼评审待确认**：机器代理全 PASS；画廊人眼结论需人打开 `qa/visual-review/index.html` 作答。
- **Affective 冻结**：AFFECTIVE_CONTRACT §8/§9/§10 人工评审门未满足（affectiveState 恒 null，测试强制），不得解除。
- **30 天 dogfood 未开始**：协议 `qa/DOGFOOD_PROTOCOL.md` 已就绪，真实长期数据回流后才能验证
  Correction Reuse Rate / False Interpretation Rate / Wallpaper 留存等主指标。
- **Wearable 外部门（ERA 33）**：`BLOCKED_EXTERNAL_XIAOMI_SDK`（官方穿戴 SDK AAR 未获得）、
  `BLOCKED_EXTERNAL_BAND10_DEVICE`（无真机）、`BLOCKED_EXTERNAL_XIAOMI_THIRD_PARTY_CHANNEL`
  （Mi Fitness 第三方应用 Debug 通道未可用）、
  `BLOCKED_EXTERNAL_LONG_RUN_DEVICE_TIME`（1h/8h/24h 真机长跑未执行；协议与软件仪表已就绪，
  见 `docs/wearable/LONG_RUN_PROTOCOL.md`）、
  `BLOCKED_EXTERNAL_PRODUCTION_SIGNING`（生产签名材料不进仓库）、
  `BLOCKED_EXTERNAL_ANS_HARDWARE`（ANS 无硬件）——每个阻塞的缺失资源/已完成测试/确切人工下一步/
  禁止的宣称见 `docs/wearable/XIAOMI_BAND10_CAPABILITY_MATRIX.md` §4；
  真机安装流程基准与验收清单见 `docs/wearable/BAND10_INSTALL_GUIDE.md`。
  （ERA 33 R3：原 `BLOCKED_EXTERNAL_AIOT_IDE_PACKAGING` 已解除——官方 `aiot-toolkit` CLI
  在本环境真实打包出 debug RPK，无需 AIoT-IDE。）

## 5. Known Engineering Risks

- **外部发布门未执行**：真实设备回归 / 责任矩阵 / 临床签署 / 法务定稿 / 外部渗透 / 值班演练 / 生产域 /
  伦理审查——见 `docs/CHANGELOG/RELEASE_READINESS_v0.9.0.md`（移交包），不由代码生成替代。
- **v0.11.0 发布物边界**：本地测试密钥签名（生产签名在运营环境）；release 包内 SOURCE_MANIFEST 为
  closure 时点快照，closure 后文档更新以仓库清单为准（`docs/RELEASE_BASELINE.md` §3 纪律 5）。
- **PersonalAnswerEngine 复杂度**：626 行 / 16 回答族单 object，已审计——三层拆分暂不必要（触发条件入册），
  ERA 32 R02 答案复核轮修复 3 个真实缺陷（q032 答非所问 / z 距离泄漏 / 无变化结论不可验证）；
  见 `qa/reports/PERSONAL_ANSWER_ENGINE_AUDIT.md`。
- **QA mirror 漂移面**：QaPortraitMirror 是必要镜像（跨语言黄金门已锁）；QaHeadlineEngine 文案重复已消除
  （learningPhaseHeadline 单点）；结论见 `qa/reports/QA_MIRROR_AUDIT.md`。
- **本机 assembleDebug 环境限制（ERA 33 实测）**：本工作区路径含空格（"ECHO Workspace"）时 AGP 8.13.2 的
  dexing transform（DexingNoClasspathTransform）报 "file located outside the root directory"；
  无空格路径实测 assembleDebug 全绿（APK 含 wearable dex），CI 路径无空格不受影响。
  本地 assemble 请使用无空格路径副本（如 /private/tmp/echoverify）。
- **字段级加密豁免（ERA 32 R26 已评审记录）**：`QuestionnaireEntity.answersJson`、
  `EchoMemoryEntity.content`、`DailyPortraitEntity.summary/headlineJson/dimensionsJson/factsJson` 与
  FeatureVector 的 `vector/sources_present` 仅由 **SQLCipher 全库加密**保护，不做第二层字段加密。
  理由：① 全库 AES-256（HKDF 口令，Keystore 包装）已满足静态数据保护，字段加密是 outbox/缓存
  等「离开全库边界」字段的纵深防御，非普遍要求；② 存量明文行迁移需读改写全表，风险高于收益；
  ③ 这些字段从未离开设备（本地模式零上行；云端同步仅传派生特征摘要，画像为端侧产物）。
  若未来引入「无全库加密的导出路径」，必须先补字段加密再放开。

## 6. Next Product Slice

1. **治理减法**（本轮）：架构/QA 冻结 + Source Reality 多模块修复 + 状态文档收敛 + 状态数字自动生成。✅
2. **真机验证**（Batch B）：fresh install / Time-to-ECHO / 授权自动推进 / Wallpaper lifecycle / 24h 运行 /
   电池采样——本环境无真机，保持协议与清单，可用设备立即执行。
3. **真实 Dogfood**（Batch D）：30 天真实使用 → 六类缺陷回流 → 修产品 → fixture 化 regression。
4. **Personal Reasoning / Correction Loop**（Batch C）✅ 完成——26 条 Core Set 四层复核（R02：q032 答非所问、
   z 距离泄漏、无变化结论不可验证）+ Context/Correction 深度走查（R03：上下文永不过期 P1 缺陷 +
   AI 路径内部格式泄漏，均已修）；PersonalAnswerEngine 保持单 object 不拆分（触发条件入册）。
5. **Journey / Memory**（Batch E/F）：§41 90 天测试 ✅（R06：期间故事 + 现在 vs 一个月前接入
   production 屏幕，四问全部可答；QA 快照 Journey 段改吃 production 装配——mirror 收口）；
   Memory consolidation 仍待真实 dogfood 数据回流评估。
6. **Delete Review**（Batch G）✅ 预检完成——删除 v0.7 遗留零消费函数（进度条/覆盖率文案），
   复查 Skills/Subscription/QA mirror/reports 均保留（判定见 `docs/CHANGELOG/ERA32_ROUND04_DELETE_REVIEW.md`）。
7. **Release Candidate**（Batch H）✅ v0.11.0 Closure 完成——版本收口 8 处同步 + 全门禁 +
   assembleRelease 全 40 位 commit 钉定 + 测试密钥签名 v2,v3 + provenance 绑定 + final package §18 门禁 +
   test_release_set/test_source_archive 16/16（`docs/CHANGELOG/ERA32_ROUND05_RELEASE.md`）。
   下一批：真实数据回流后 Batch D/E/F；真机可用即执行 Batch B。
8. **ECHO Wrist / Second Body**（ERA 33）✅ 软件侧实施完成（状态表见下，不再笼统写"closure complete"）——
   `:feature:wearable` domain + Wear Protocol v1 + 隐私投影 + same-ECHO 投影器 +
   Vela 快应用（`wearable/xiaomi-vela/`）+ Android vendor boundary（Noop/Fake + XiaomiWearCapabilityMapper）+
   同一 EchoActionRuntime + 腕上观察模型 + ANS_FRAME_V1 schema/golden/decoder/mapper/promotion policy +
   Me → "ECHO on Wrist"。契约：`docs/wearable/ECHO_WRIST_CONTRACT.md`（能力矩阵/隐私/ANS 集成同目录）。

   **ERA 33 R1 状态表（每一项独立标注，禁止用一个总 ✅ 掩盖外部门）**：

   | 项 | 状态 | 证据 |
   |---|---|---|
   | Wearable Domain | PASS | `:feature:wearable` 96 单测全绿 |
   | Protocol | PASS | Kotlin codec + Vela JS parity 测试全绿 |
   | Privacy | PASS | payload 扫描测试全绿 |
   | Production Runtime Wiring | **PASS** | Application scoped 唯一启动点（AppContainer 组合 → `WearableContainer.start()`，幂等）；`WearableRuntime.start()` 自动 collect `inboundMessages`；`WearableApplicationIntegrationTest`（:app）11/11 证明全链（连接推送/WHY 往返/同一 Action/观察 sink/断连重连/重复/伪造 Presence/长跑仪表） |
   | Haptics 端到端 | **PASS**（软件侧） | `prefs.hapticsEnabled → envelope.surface.hapticsEnabled → 腕上 vibrate 硬门`（默认 SILENT）；降级 surface 不重置开关；Kotlin + Node 双端测试锁定 |
   | Vela Static Tests | PASS | `node tests/run.js` 31/31 + `node tests/preflight.js`（结构/manifest/i18n/语法/212×520 布局门 + 8 项模拟器实测缺陷类回归门）+ simulator 镜像生成门（26 态渲染 0 裁剪 / 0 文本截断 / identity 连续，见 `BAND10_VISUAL_REVIEW.md` §0） |
   | Vela RPK Build | **PASS** | 官方 `aiot-toolkit` 2.0.5 CLI 真实构建：`wearable/xiaomi-vela/dist/com.yunjue.echo.mind.debug.1.0.rpk`（42,942 B，SHA256 `1b90a61c…`，debug 模式，ERA 33 R4 HEAD，JSC 字节码）；签名 = Android debug 身份（`verify_wrist_signing.py` 实测 APK↔RPK MATCH） |
   | Band10 模拟器 | **PASS（R4）** | 官方 Vela 模拟器（VVD `Vela_Band10`，system-image vela-miwear-watch-5.0 + 官方 `xiaomi_band_10` skin，212×520）：RPK 安装成功（重启持久）、app 全生命周期无异常、页面渲染色彩/几何像素级验证；过程中修复 **8 个真机级缺陷**（布局约定/i18n 命名/features 声明/toFixed 字符串污染/`private:`/app `onCreate`/app 上下文无 require/div 绑定 style 不渲染→class+CSS keyframes）。细节见 `ECHO_WRIST_REAL_DEVICE_REPORT.md` |
   | Band10 Install（真机） | **BLOCKED** | `BLOCKED_EXTERNAL_BAND10_DEVICE` + `BLOCKED_EXTERNAL_XIAOMI_THIRD_PARTY_CHANNEL`（RPK 已生成；流程基准见 `docs/wearable/BAND10_INSTALL_GUIDE.md`） |
   | Interconnect | **BLOCKED** | `BLOCKED_EXTERNAL_XIAOMI_SDK`（vendor bridge）+ 真机；签名身份已满足（APK↔RPK MATCH） |
   | Xiaomi Vendor SDK | **BLOCKED** | `BLOCKED_EXTERNAL_XIAOMI_SDK`（Noop 恒 DISCONNECTED，不伪装 vendor connectivity） |
   | ANS Contract | PASS | frozen REQUIRED_FIELDS（35）+ schema + Kotlin decoder 黄金门 |
   | ANS Cross-repo Validation | PASS（本工作区）/ SKIP（无 ANSWatch 时） | `verify_golden.py`：ANSWATCH_ROOT > sibling ../ANSWatch > SKIP with reason；frozen 验证恒 PASS；CI 不再强依赖 ../ANSWatch |
   | Source Closure | PASS | SOURCE_MANIFEST 1313 文件 verify 0 missing / 0 mismatch |

   版本：开发态不动 Release Baseline；v0.12.0 / versionCode 9 只在
   Production wiring + Source closure + RPK build + 至少真机安装全绿后再决定；
   Interconnect/24h/battery/signing 也完成才考虑正式 `v0.12.0`。
   Release claims 纪律（Phase 24）：可宣称 "ECHO Wrist buildable，且已在官方 Vela 模拟器
   （Band 10 profile，212×520）安装并运行、页面渲染验证通过"（ERA 33 R4 实测）；
   不得宣称 installed on Band 10 / connected / Production Ready
   （真机安装验收清单见 `docs/wearable/BAND10_INSTALL_GUIDE.md` §3）。
9. **ECHO Organism Quality Pass + Runtime Finish**（2026-08-18）✅ 软件侧完成——
   修复两处颜色数学根因（palette chroma 量纲 + Lab→XYZ 分母）使 ECHO 呈现真实蓝紫彩度；
   拓扑 v4（非闭合结构环 4–7 / 长丝 0.55–0.92R / 局部碎片 24–40 成主体 / 粒子反星空收敛 /
   有机形变暗腔 + 暖结核心解剖）；体积大气层（haze+rim）；运动对齐 Art Direction
   （呼吸 8.2–10.2s / 自转 30–55min / 丝相位 35–55s）；AGSL mask 升级 R/G/B(depth)/A +
   depth fog shader；后端真值纪律（离屏 raster 恒 CANVAS + reason，修复设备 AGSL 离屏导出
   崩溃缺陷）；App Home 请求 STANDARD(AGSL)，Wallpaper/Dream 维持 CANVAS；
   JourneyScreen 按 cohesion 拆 10 文件（UX 不变）；Me mini 统一 facade（MINI 预算）；
   maturity 语义统一（日历单一定义 + Journey 显式代理）；docs/current 版本收口 v0.11。
   证据：`qa/reports/ECHO_ORGANISM_QUALITY_{BASELINE,RENDERER,PERFORMANCE,FINAL}.md` +
   `qa/visual-review/organism-quality/`（5 张人眼评审图 + appendix，**视觉审美结论
   PENDING_PRINCIPAL_VISUAL_REVIEW**；AGSL raster/真机门 BLOCKED_EXTERNAL_DEVICE）。

10. **全仓逐行审计（Repo-Wide Line-by-Line Audit）**（2026-08-18）✅——396 生产文件/53,821 LOC
   + 237 测试文件 100% 逐行覆盖（P2/P3 明细与真值结论见 `.trae/specs/audit-repo-line-by-line/findings/T2–T8`）。
   发现 **P0×2 / P1×22 / P2×61 / P3×105**，全部 P0/P1 当轮修复 + 回归锁定（Android +18、backend +15
   新测试；证据 `qa/reports/REPO_LINE_AUDIT.md` + `REPO_LINE_AUDIT_FIXES.md`）。要点：backend DSR
   删除矩阵补全 v0.7 派生表 + 激活码防爆破拒绝路径持久化（两处 P0）；5 写端点 RBAC/consent/订阅门
   补齐；escalations PG 分页修复；narrative 本地日界线统一；DSR export 真实导出；app 三处功能级缺陷
   （画像重建主线程网络/FGS 崩溃路径/提醒自取消）；视觉链三处正确性（AGSL exposure 未裁剪/时钟
   Float 长期精度→Long nanos/呼吸量纲 KDoc-实现统一）；JourneyCanonical v1 真解码；LAN 判定防域名
   伪装；腕上加速度计 10s 窗口落地（契约 §8）；三处测试质量缺陷（自证断言/黄金缺条目静默过/假迁移
   测试）；发布元数据三件套按纪律 §5 在本轮最终树原子再生（SOURCE_MANIFEST/SBOM(3.12 全闭包)/
   provenance/artifact manifest，RELEASE_BASELINE 时间锚修正）。门禁：Android 1339 全绿 + detekt +
   lint；backend 1099+1；Vela Node 28/28；assembleDebug 无空格路径实测通过。

11. **深化迭代：P2 清偿 + UX 优化（Deepen Iteration）**（2026-08-18）✅——审计遗留 P2×61
   分诊（51 修复 / 8 FOLLOW_UP 附因 / 2 已吸收，台账 `.trae/specs/deepen-iteration-p2-ux/notes/LEDGER.md`）
   并全部处置：视觉链（拓扑 LRU 化、确定性盐分段独立流、材质/shader 单源、删 OrganicNoise 死文件、
   **AGSL 后端补齐 ripple/halo/membrane（触摸反馈对齐=UX-B1）**、Canvas 渲染器 Paint/Path 复用、
   cavity 公式单源、depth01 满幅）；感知（LifeSeasonTracker 候选日、Gate forDate、日构图显式 filled
   标志 codec v2 兼容）；app（死工厂/死 composable 删除、壁纸偏好键单源+事务式清除、escalation 映射
   合一、VM 装配下 IO、**onboarding 尊重 reduceMotion=UX-B2**）；领域（journey 旧渲染链死代码群删除、
   ContextExceptions roundtrip 修复（memory 模块首个测试）、travelContext 防护、dayComposition 真实化、
   /v1 幂等、腕上 WHY 门控落地「宁缺勿造」、**EchoActionOverlay 统一 facade=UX-B5**）；backend（查询
   复合索引迁移 20260818_0001、GET 零审计写副作用、narratives clamp、**DSR/revoke 吊销 refresh_token**、
   legacy 命中确定性、bootstrap compare_digest）；边界（SBOM fail-closed、dynamic-code/claim-scan 扩全
   15 模块、final-package 缺清单必败、DELIVERY 真值化、smoke -f、Band10 色板/空态两子项、**腕上触觉
   1500ms 节流（契约）**）；测试缺口 8 组关闭（maturity 时钟/代理、MotionPolicy/Environment 全矩阵、
   AiNarrativeService、POST /users、decrypt_text、永真断言清理——并暴露修复 users 重复 500→409）。
   Visual Lab 补 LOCK/WRIST（UX-B6）。42 黄金哈希再生意图见 LEDGER（盐独立流+dayComposition 真实化）。
   新增 **docs/architecture/ARCHITECTURE_WALKTHROUGH.md**（301 行架构走读：模块全景/入口地图/
   四条端到端链路/改进点索引）。门禁：Android **1375 全绿**+detekt+lint；backend **1120+1**；
   Vela Node **31/31**；assembleDebug 无空格路径实测；SOURCE_MANIFEST **1321** verify OK。

## 7. Governance（冻结纪律）

- **架构冻结**：14 个 Gradle module 体系冻结，以 `android/settings.gradle.kts` 为唯一事实源
  （`:app` / `:core:model|ports|security|visual` / `:feature:observation|presence|presencevisual|intelligence|memory|journey|actions|wearable|qa`）。
  不新增 module、大框架、抽象层、Contract 类型；只有真实 Dependency Violation 或直接阻碍
  用户体验/reasoning/性能/电池/安全/发布/可维护性时才调整。
  module 职责边界（V3 Simplification 轮确认）：`core:visual` = 纯确定性视觉数学/编译（无 Android 渲染）；
  `feature:presencevisual` = Android 渲染 adapter/backend（AGSL/Canvas/facade）。
  **ERA 33 例外（一次性，已执行完毕）**：新增产品边界 module `:feature:wearable`
  （只依赖 `:core:model` + `:core:ports`）；不因 wearable 再拆子 module；vendor adapter 属 `:app` adapter 层。
- **QA 冻结**：synthetic QA 不再扩张。顺序固定为：真实产品问题 → 复现 → 修复 → 有普遍意义的 fixture 化 → regression。
  QA 必须测试 Production，不得重写 Production（mirror 审计见 `qa/reports/QA_MIRROR_AUDIT.md`）。
- **ADR 纪律**：只有真正 Architecture Decision 才新增 ADR；每轮重构/体验修改/threshold 不再产生 ADR。
- **删除是一等开发能力**：每轮执行 Delete Review（UI 还属于 ECHO 吗？repository/report/setting 还有价值吗？）。
- **文档权威顺序**：产品宪法（`docs/product/ECHO_PRODUCT_CONSTITUTION.md`）＞ 冻结契约
  （`PORTRAIT_CONTRACT.md` / 个人智能 / AI Provider / Presence 架构 / Motion Language）＞
  本 STATUS（开发事实）＞ 架构文档（`docs/architecture/`，ADRS 为历史决策记录）＞
  `docs/CHANGELOG/`（历史轮次记录，禁止作为当前要求来源）。
- **数字纪律**：README/STATUS 不手写测试计数；数字由 `scripts/refresh_status_numbers.py` 从实测产物生成，
  或干脆不写。
