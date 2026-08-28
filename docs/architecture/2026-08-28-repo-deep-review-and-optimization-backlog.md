# ECHO Mind 全仓深度评审与优化任务清单（2026-08-28）

> 评审人：Gao（架构师 / software-architect-2）
> 评审对象：`/Volumes/Extra/CodeProj/ECHO Workspace/ECHO Mind`（ECHO Mind v0.11.0，pilot-candidate）
> 评审方式：全仓只读通读（后端 69 个 py 文件全结构 + 25 个核心文件精读；Android 14 模块边界全览 + 12 个关键文件精读 + 全仓代码异味扫描；契约/CI/文档/供应链逐项核对）。证据均以 `文件:行号` 标注；无法当场确认处标注「待核实」。

---

## 0. 执行摘要

**总体印象：这是一个工程质量远超其"个人项目"表象、但工程管理已经失控边缘的仓库。**

代码本身是我近年评审过的同规模项目里最干净的之一：后端 9,100 行 Python 是 mypy strict 零豁免、ruff、70% coverage 门禁下的产出；Android 68k 行 Kotlin 主代码里 **0 处 TODO/FIXME/HACK、0 处 GlobalScope、0 处 runBlocking（仅测试使用）**；安全设计（fail-closed 密钥校验、append-only 哈希链审计、SQLCipher+Keystore+HKDF 域分离、沙箱子进程 rlimit 隔离）逐条核实属实，不是纸面承诺。产品契约纪律（PORTRAIT_CONTRACT 冻结、维度禁评价词、"Me vs Me"）在数据模型层真实落地（`backend/app/models.py:484-521` DailyBehaviorAggregate 明确无情绪字段）。

但仓库治理与代码质量严重倒挂：

- **主干失序**：当前产品真源在 `agent/organism-visual-breakthrough` 分支（领先 origin/main 559 个提交），本地 main 另有 391 个分叉提交，28 个 dependabot 分支积压未合并（35 个本地分支）。`main` 已不是"当前可交付 main"，与 docs/STATUS.md 的自我定义直接矛盾。
- **二进制膨胀**：`.git` 达 1.0GB，其中 `qa/visual-review/` 167MB 二进制资产受控入库；根目录堆积 20+ 个本地 APK（约 250MB，未入库但散落）；`releases/` 本地 611MB。
- **交付证据断层**：DELIVERY_MANIFEST.json 中 `alembic_roundtrip / contract_drift_check / android_instrumentation / postgresql_docker_integration` 全部 `not_run`，且 BUILD_PROVENANCE.json 记录 `git_dirty: true`、`python_version 3.9.6`（pyproject 要求 >=3.12）——最后一次"交付"是本地脏树产物，CI 门禁虽然齐全但没有跑在这份交付上。
- **三份画像引擎镜像**：后端 `dimensions.py` ↔ Android `LocalPortraitEngine.kt` ↔ `QaPortraitMirror.kt` 三份逐语义镜像，目前靠三个黄金测试锁定，无单一 golden 数据源，漂移只是时间问题。

**主观评价**：代码 9/10，测试 8.5/10，安全 9/10，工程卫生 4/10，发布治理 5/10。综合 7.5/10 —— 修好 P0 四项即可达到 pilot 可信交付水位。

**关键数字**：git 受控 1,612 文件；backend/app 69 py / 9,100 行 + 53 个测试文件（1120 passed + 1 skipped）；Android 456 kt / 67,962 行（app 主代码 110 + 测试 136）；Android 单测 1,387 全绿；docs 339 文件（CHANGELOG 277 轮）；OpenAPI 61 路径；本地分支 35 个；`.git` 1.0GB。

**P0 速览**：
1. 主干归位与 dependabot 清算（分支失序）
2. 仓库二进制瘦身（.git 1GB / qa 167MB 入库）
3. escalation 触发豁免伪造绕过 429（安全债兑现）
4. 发布门禁执行断层（not_run + dirty provenance）

---

## 1. 仓库全景与工程卫生

### 1.1 全景

| 顶层目录 | 受控文件数 | 体量 | 说明 |
|---|---|---|---|
| `android/` | 554 | 14 个 Gradle 模块 / 456 kt / 67,962 行 | 主产品（手机 + 手环） |
| `qa/` | 397 | 磁盘 167MB（几乎全在 visual-review） | 视觉评审画廊 + 协议 + 82 份报告 |
| `docs/` | 339 | CHANGELOG 277 个文件 | PRD/ADR(72)/契约/发布基线 |
| `backend/` | 166 | app 69 py / 9,100 行 + tests 53 文件 | FastAPI 机构后端 |
| `scripts/` | 35 | 30+ 门禁脚本 | 发布/完整性/安全评估 |
| `wearable/` | 24 | Xiaomi Vela JS 快应用 | 手表端 |
| 其余 | — | content-packs / integrations / safety-eval / pilot-pack | 内容包与集成 |

本地未入库占用：根目录 APK+idsig 约 250MB（`ECHO_Mind_v*.apk*`，.gitignore 已覆盖 `.gitignore:44-47`）；`releases/` 611MB（.gitignore:49-50 覆盖）；`dist/` 145MB；`backend/.venv`、`backend/echo_mind.db`（`*.db` 规则覆盖）。

### 1.2 工程卫生问题（逐条证据）

1. **`.git` 1.0GB（size-pack 1.03GiB，`git count-objects -vH`）**：主因是 `qa/visual-review/` 167MB 图片/视频资产受控入库（`git ls-files qa` 397 个文件）。历史里应还有已删除的大文件（待核实具体对象）。
2. **分支失序**：`git branch -a` 共 35 个本地分支；当前分支 `agent/organism-visual-breakthrough` 领先 `origin/main` 559 提交、落后 0；本地 `main` 与 HEAD 有 391 个互相不含的提交（历史被改写/分叉，待核实成因）。近 15 次提交全部是 docs 轮次（`git log --oneline`，Round 250~256）。
3. **dependabot 积压**：28 个 `dependabot/**` 分支未合并（github-actions 10 个、gradle 10 个、pip/uv 8 个）。同时 `backend/pyproject.toml:11-18` 使用范围版本（`fastapi>=0.128,<0.141` 等）+ `uv.lock` 钉死 + dependabot 三套机制并存，PR 互相冲突。
4. **.gitignore 小缺口**：根 `.mypy_cache/`、`.ruff_cache/` 未显式列入（`git check-ignore -v .mypy_cache .ruff_cache` 无匹配规则；当前靠缓存目录自带的嵌套 `.gitignore` 生效，属脆弱依赖）。另 `.gitignore:24-26` 声明忽略 `.trae/`，但 `.trae/specs/deepen-iteration-p2-ux/notes/LEDGER.md` 仍是受控文件且当前处于修改状态（`git status` 显示 M）——忽略规则对已跟踪文件无效，规则与事实不一致。
5. **本地构建 provenance 污染**：`BUILD_PROVENANCE.json` 记录 `"git_dirty": true`、`"python_version": "3.9.6"`、`"ci_run_id": "unknown"`、`"builder_environment": "local-dev"`——与 `backend/pyproject.toml:12` `requires-python = ">=3.12"` 不符，且 `.github/workflows/release-closure.yml:143` 注释明言"§15 禁 dirty release"。该 provenance 只能算开发产物，不构成发布证据（见 P0-4）。

---

## 2. 模块划分

### 2.1 后端分层（依赖方向：api → services → models，无反向）

```
app/main.py（FastAPI 组装、安全头/遥测中间件、health/ready/console）
 ├─ api/routes.py:33-47 —— 15 个 bounded-context 子 router 聚合
 │   onboarding / auth_refresh / consent / features(ingest) / narratives /
 │   portraits(+me/*) / messages / subscription / profiles / escalations /
 │   data_rights / skills / sandbox / admin / legacy(410 存根)
 ├─ api/deps.py —— 共享守卫：RBAC(require_write_role/step_up/psych_content)、
 │   租户隔离(ensure_user)、consent 门禁、feature flag、订阅门禁、审计
 ├─ auth.py —— JWT(HS256)+8 角色+刷新令牌轮换
 ├─ services/
 │   portrait/{engine,dimensions,explain,narrative,materializer}
 │   baseline/{calculator,metrics,circular,confidence,day_type}
 │   aggregates/{calculator,timezone}
 │   sandbox/{worker,runner,slots,audit_day,gap_finder,forge,validator,induct,sanitizer,scheduler}
 │   activation / audit / crypto / immutability / safety / feature_flags /
 │   schema_registry / subscription / telemetry / escalation / messages /
 │   profile / tenant_portrait / trends / scoring
 └─ models.py —— 24 张表；schemas.py —— Pydantic DTO + ACTION_TYPE_WHITELIST
```

### 2.2 Android 分层（依赖方向：app → feature → core）

| 模块 | kt 数 | 职责 | 依赖 |
|---|---|---|---|
| `app` | 246（main 110 / test 136） | 组装层：DI(EchoContainers)、data(Room/ApiClient/各 Repository)、sensing 服务、runtime 协调、Compose UI、Workers | feature/* + core/* |
| `core/model` | 7 | 纯 DTO（Models.kt 336 行，含 EchoPresenceState） | 无 |
| `core/ports` | 3 | 依赖倒置端口（Memory/Observation/Presence Ports） | model |
| `core/security` | 8 | Keystore 字段加密、HKDF、SQLCipher 口令派生与打开编排 | 无 Android UI 依赖 |
| `core/visual` | 30 | 生命体渲染纯计算（OrganismFrameComputer 904 行） | model |
| `feature/observation` | 17 | 被动感知：FeatureExtractor/MicFeatureExtractor + **LocalPortraitEngine** | core |
| `feature/intelligence` | 22 | **EchoContextCompiler**、PersonalAnswerEngine、AiNarrativeService | core |
| `feature/presence` | 14 | EchoIdentity（430 行）等身份/状态域 | core |
| `feature/presencevisual` | 16 | AgslEchoBackend(586)/EchoOrganismRenderer(533)/OrganismCanvasRenderer(464) | core/visual |
| `feature/qa` | 38 | QA 专用（QaPortraitMirror、QaAskEcho、黄金门测试）——非生产运行时 | 全部 |
| `feature/memory` `journey` `actions` | 7/18/2 | 记忆/Journey/行动域 | core |
| `feature/wearable` | 28 | Wear Protocol v1、隐私投影、运行时 | core |

**模块化评价**：分层方向正确、core 无反向依赖、QA 与生产运行时显式隔离（QA mirror 审计结论见 `docs/STATUS.md` §5）。唯一结构问题是 `app` 仍占 246/456 文件——data/sensing/runtime/ui 全在 app 内，feature 模块偏薄（见 P2-1）。

---

## 3. 核心链路（文件→函数级证据）

### 3.1 Observation Core 五段链路（README 声明 ↔ 代码核实：**属实**）

1. **被动感知 → 派生特征**：Android `PassiveSensingService.kt`（411 行）+ `sensing/FeatureExtractor.kt`(367)/`MicFeatureExtractor.kt`(368) 产出 5 分钟 22 维向量；原始传感仅内存缓冲不落盘（`PORTRAIT_CONTRACT.md:77`）。上行经 Room `feature_vectors`（`EchoDatabase.kt:80-96`，摘要加密）→ `SyncWorker` Outbox → `POST /v1/features/ingest`。
2. **日聚合**：`backend/app/services/aggregates/calculator.py:95-137` `compute_daily_aggregate` —— 覆盖度按 unique (window_start, schema_version, source) 去重（:100）、期望窗口数按时区动态算 DST（:85-92）、只消费 aggregate_eligible schema（:73-82）、movement_index = vector[7]（:104-108）。幂等 upsert（:161-232）。
3. **个人基线**：`baseline/calculator.py:70-152` —— 近 28 天、coverage≥0.25（:26）、weekday/weekend 分桶不足 2 天回退 all_days（:99-112）、时间类指标用圆周 median/MAD（:45-46，处理 23:55≈00:05）。
4. **画像**：`portrait/engine.py:176-252` `generate_portrait` 状态机（WARMING_UP ≤2 / EARLY_BASELINE 3-6 / BASELINE_READY ≥7，`baseline/calculator.py:49-55`）→ `dimensions.py:82-159` 确定性 z 分类（scale=max(mad*1.4826,(p75-p25)/2,MIN_ABS_DELTA)，:40-46；min meaningful delta 防 near-zero 基线 z 爆炸，:49-65）→ 叙事/事实。可复现性：`baseline_snapshot_digest` = 规范化 JSON SHA-256（`engine.py:47-56`）。
5. **自动物化**：`portrait/materializer.py` —— ingest 后 `mark_dirty`（:46-76，唯一约束合并同日多次）；15 分钟 debounce（:36, :79-91）；原子占位 `UPDATE...WHERE dirty=true`（:94-117）；失败恢复 dirty 可重试（:130-143）。

**契约符合性**：DailyBehaviorAggregate 表注释即契约（`models.py:484-490` "绝不包含 mood/anxiety/…"）；维度禁 GOOD/BAD/HEALTHY（`dimensions.py:16`）；"Me vs Me" 无群体均值比较——全部核实成立。

### 3.2 Presence 状态共享链路

单一状态源 `EchoPresenceState`（`core/model/Models.kt`）→ `app/runtime/EchoRuntimeCoordinator.kt:20-27` 统一广播 `EchoRuntimeState(sensing, presence, provider)`（"UI 不再各自拼状态"，:18-19 注释）→ 消费方：`EchoWallpaperService.kt`(327 行)、`EchoDreamService.kt`、`PresenceRepository.kt`、手环 `WearPresenceProjector.kt`（同一状态投影到腕上）。刷新由 `PresenceRefreshWorker` 驱动。链路完整，App/Wallpaper/Dream/Wearable 四面共享同一 StateFlow，符合"单一状态"承诺。

### 3.3 AI / BYOM Context Compiler 链路（声明 ↔ 代码核实：**属实，且是全仓最好的一段设计**）

`feature/intelligence/EchoContextCompiler.kt:40-132`：任何 LLM 请求唯一通道——Task → Context Policy（prohibited/allowed 剔除，:72-74）→ ContextRanker → **记忆先行占预算**（"观察证据再多也不得把用户自述挤出编译上下文"，:80-82）→ token 预算 3 字符/token 保守估算截断（:84-96）→ 编译最小上下文。系统指令是产品契约的机器执行版：禁用焦虑/抑郁等推断词、证据先行、"每条判断都要能回答为什么这么说"（:48-57）。模型**默认无数据库访问权**（:9 注释）。BYOM Provider 状态由 `AiProviderManager` + `runtime` 协调器管理；无 Provider 时回退 `DeterministicPersonalAnswerProvider`（AiNarrativeServiceTest 16 个用例覆盖回退矩阵）。

### 3.4 订阅激活链路

机构侧签发（`POST /v1/admin/activation-codes` → `activation.issue_code`，明文码仅响应出现一次）→ 用户兑换 `rede_code`（`activation.py:173-275`）：SHA-256(code+bootstrap pepper)（:56-60）、三维限流 code/IP/device 各自独立阈值 fail-closed（:103-137，v0.6.2 修复注释 :103-110）、原子消费 `UPDATE...WHERE used_at IS NULL` + returning（:236-251，并发竞争按 replay 处理）、成功授予订阅天数顺延（:255-263）、写审计链（:264-274）。续期：`auth_refresh` 轮换式刷新令牌（服务端只存哈希 `auth.py:23-25`；401 静默续期钩子在 `ApiClient.kt:31-33`）。订阅到期 → `deps.require_active_subscription` 402（`deps.py:76-84`）。

### 3.5 升级（Escalation）与数据权利链路

- 升级：Android Outbox → `POST /v1/escalations`（`escalations.py:64-113`，幂等 event_id 短路 :75-80）→ 服务端收件即写 `delivery_confirmed_at` ≠ 人工 ack（`deps.py:179-199` 注释明确"绝不把已送达当作人工已收到"）→ SLA 状态机 escalation_level 0→2（`models.py:255-262`）→ 用户侧仅见最小状态（`EchoDatabase.kt:216-229` ACKNOWLEDGED 枚举"当前不可达"如实注释）。append-only 守卫：`immutability.py:17-43` ORM 层 + Alembic 20260729_0002 PG 触发器。
- 数据权利：`api/data_rights.py`（304 行）DSR 矩阵 + Android 侧 `LocalDataRights.kt` 本地导出/删除（`EchoDatabase.kt:155-160,207-213,382-411` 各 DAO 均有 byUser 全量导出与删除方法，含软删行"不留盲区" :382-384）。测试 `test_dsr_matrix.py`(214)/`test_e2e_privacy.py`(289) 覆盖。

---

## 4. 关键实现逻辑（逐模块点评）

### 4.1 后端 —— 好设计点名

- **审计哈希链的并发正确性**（`services/audit.py`）：PG 用 `pg_advisory_xact_lock(hashtext(tenant_id))` 事务级串行化（:164-169），SQLite 回退 per-tenant RLock（:180-191）；`occurred_at` 冲突时 +1μs 保证 per-tenant 严格递增使链序=插入序（:107-112）；`verify_audit_chain` 全链重验（:194-218）。这是多数项目做错的地方，这里做对了。
- **fail-closed 秘密校验**（`config.py:43-57`）：任何环境（含 local）拒绝仓库默认 dev 秘密，缺省即拒绝启动；docker-compose 三个秘密 `:?` 必填（`docker-compose.yml:12-17`）。README 承诺属实。
- **字段加密**（`crypto.py`）：AES-GCM + AAD + `enc:v1:` 版本前缀；pilot/production 拒绝未加密明文（:22-26）。
- **append-only 三层防护**：PG 触发器 + ORM before_flush 守卫（`immutability.py:62-88`，Escalation 白名单可变字段 :19-43）+ 路由层 DELETE/PATCH 405。
- **沙箱真隔离**（`sandbox/runner.py:109-139` + `worker.py`）：multiprocessing 子进程 + join(timeout)→terminate→宽限→kill 真终止；rlimit CPU/内存（`worker.py:41-53`）；租户槽原子租约 + 心跳回收（`runner.py:153-178`、`models.py:467-481`）；in-memory SQLite 测试回退线程路径显式隔离（:97-107）。工具锻造模板硬编码"无情绪评分模板"（`tool_forge.py:6-7, 22-23`）。
- **物化 debounce/原子占位**（`materializer.py`，见 3.1.5）。
- **API 拆分纪律**：`routes.py` 仅聚合无业务；共享守卫集中 `deps.py` 并明令"禁止各 router 自行复制"（`deps.py:1-4`）。
- **遥测最小化**（`main.py:79-89`）：只记 method/path/status/duration/request_id，注释明确不记 body/传感/token。
- **legacy 410 存根 + 幂等语义统一**（`legacy.py`、各写路径 `idempotent_replay`）。

### 4.2 后端 —— 坏味道（附证据）

- **`api/escalations.py` 656 行 God-router**：状态机/分页/metrics 聚合/case-review/SLA 扫描全在一个文件，是拆分后仅存的"旧 service layer"遗风（对比 `routes.py:1-7` 的拆分宣言）。
- **429 豁免可被客户端伪造**（`escalations.py:83-102`）：`trigger` 是客户端自由字符串，传 `help_requested` 等豁免词即绕过 20/h 上限；代码注释自认"FOLLOW_UP…删除前须安全评审"（:84-86）。对安全产品这是应兑现的债（P0-3）。
- **legacy 表未按期退役**：`models.py:99-101,140-141,157-158` 注释"v0.8 removal target"，v0.11.0 仍在（Checkin/QuestionnaireResult/PracticeCompletion 及 `/v1/checkins` 410 存根）。
- **activate 哈希 pepper 复用 bootstrap_key**（`activation.py:56-60`）：bootstrap key 本是引导凭据，兼任 pepper 违反密钥用途分离（低危，P2-3）。
- **`DerivedFeature.summary` 明文落库**（`models.py:308`）：后端库内派生特征摘要不加密（与 Android 端全库 SQLCipher+摘要加密不同层）。行为数据非心理内容，且后端库本身应受磁盘加密/PG 权限保护——建议至少在隐私文档中显式登记该差异（P2-5）。
- **ruff 规则集刻意收窄**（`pyproject.toml:33-38` select=["E4","E7","E9","F"]，注释自认"避免新版扩大默认导致批量报错"）——静态检查深度浅于其 mypy strict 水准（P1-5）。

### 4.3 Android —— 好设计点名

- **端侧安全栈**：`AndroidKeystoreFieldCipher.kt` fail-closed（Keystore 不可用即抛，:13-17）；真机 IV 缺陷修复注释（:41-45，"JVM 测试用 JCEKS 替身不校验该参数，因此长期未暴露"——测试替身盲区的诚实记录）；密文过短显式抛错不静默吞（:54-60）。`DatabasePassphraseDerivation.kt` HKDF+域分离替代非标准派生（:6-13）；`DatabaseSecretFormat.decode` fail-closed（:47-70）。
- **Room v12 演进纪律**：12 实体全量迁移测试（`DatabaseMigrationTest.kt` 761 行）；复合索引 (userId, schemaVersion, windowStart) 服务 Journey 窗口查询（`EchoDatabase.kt:77-83`）；画像缓存 SQL 层 userId 隔离防账户串数据（:316-319 注释+查询）。
- **Outbox 同步状态机**（`SyncWorker.kt:15-30, 41-56`）：单事件隔离不中断队列、毒丸 dead-letter、410 迁移 telemetry、认证暂停直返、本地模式静默（:47-48）——边界枚举完整（SyncAction 六态）。
- **QA 与产品同源**：`PersonalAnswerEngine.kt:22-23` "QaAskEcho 现为本引擎的薄适配器——QA 测的引擎就是产品用的引擎（不再存在 QA 重写产品）"。
- **本地画像引擎镜像 + 黄金门**：`LocalPortraitEngine.kt:9-13` 明示与后端逐语义镜像；三处黄金测试锁定：`backend/tests/test_portrait_golden.py`(240 行)、`app/src/test/.../LocalPortraitGoldenTest.kt`(453 行)、`feature/qa/.../QaPortraitMirrorGoldenTest.kt`。
- **诚实的枚举注释**：`EchoDatabase.kt:222-225` ACKNOWLEDGED "当前不可达"——状态机与现实的差异写在代码里而非隐藏。

### 4.4 Android —— 坏味道（附证据）

- **三份画像镜像**：`LocalPortraitEngine.kt`（501 行，产品离线路径）与 `QaPortraitMirror.kt`（265 行，QA 路径）各自复刻后端 `dimensions.py` 语义（QaPortraitMirror.kt:13-20 注释自认镜像）。STATUS.md §5 已登记"QaPortraitMirror 是必要镜像（跨语言黄金门已锁）"，但 golden 用例数据分散在三个测试文件里，无单一共享 golden 向量文件（P1-1）。
- **SCREEN_TIMING 量-时名实混义**：驱动指标是晚间屏幕分钟数（量），标签却是 EARLIER/LATER（时间词）（`LocalPortraitEngine.kt:318-323` 详细注释，登记为 LEDGER T3-P2-4 待产品裁定；后端 `dimensions.py:121-128` 同病未登记注释）。用户会读到"晚间屏幕互动比通常更早结束"实则意思是"更少"。
- **本地 WARMING_UP 文案与服务端镜像分叉**（`LocalPortraitEngine.kt:155-160`）：v0.7.4 UX 故意让端侧第 1 天附加事实句而服务端保持固定文案——注释已声明"仅本地模式生效"，属有意漂移，但意味着两端画像 summary 不再逐字节一致，镜像测试需持续豁免该分支（建议在 golden 中显式标注差异向量，P1-1 一并处理）。
- **`AppPreferences.kt` 562 行 god-object**：token/身份/Skill 缓存/presence/sensing 十余个域挤在一个 SharedPreferences 包装类（:15-70 已见其混装）；token 加密存储依赖注入的 FieldCipher 正确（:41-61），但文件本身应拆分并迁 DataStore（P1-4）。
- **`OrganismFrameComputer.kt` 904 行、`compute` 函数约 350 行**（:100-449 单函数）：渲染纯计算虽可测，但单函数圈复杂度极高，后续调参回归定位成本大（P2-2）。
- **`PersonalAnswerEngine.kt` 688 行单 object / 16 回答族**：STATUS.md §5 已审计并"三层拆分暂不必要（触发条件入册）"——维持观察即可（P2-6）。
- **app 模块引力过大**：110 个 main kt 中 data/(24 文件)、sensing/、runtime/、me/、ui/ 都在 app，与 2.2 的模块表相抵（P2-1）。

---

## 5. 测试、CI 与发布门禁现状

### 5.1 测试

- **后端 53 个测试文件 / 1120 passed + 1 skipped**（DELIVERY_MANIFEST + STATUS 双源一致）。结构抽查显示安全面覆盖完整：`test_e2e_privacy.py`(289)、`test_dsr_matrix.py`(214)、`test_rbac_v03.py`(161)、`test_immutability_v03.py`(208)、`test_skill_signed_only.py`(200)、`test_sandbox_concurrency.py`(170)、`test_audit_p2_fixes.py`(240)、`test_portrait_golden.py`(240)、`test_activation_codes.py`(359)。SQLite/PostgreSQL 双轨（`-m "not sqlite_only"` 区分，backend-ci.yml:64）。
- **Android 1,387 单测全绿**（STATUS §3 自动生成，按模块分布列出）。亮点：`DatabaseMigrationTest`(761)、`E2EFlowTest`(565)、`PerformanceBaselineTest`(327)、`LocalPortraitGoldenTest`(453)、`WearableApplicationIntegrationTest`(:app 11/11 全链)。
- **覆盖率门禁**：仅 PostgreSQL job `--cov-fail-under=70`（backend-ci.yml:64-66）；SQLite 主路径无覆盖率要求；Android 无覆盖率门禁。

### 5.2 五套 CI（全部 action 钉 immutable SHA，`verify_workflow_pins.py` 门禁防漂移）

| workflow | 覆盖 |
|---|---|
| backend-ci | ruff+mypy strict；SQLite 全测；PG16 测试+coverage≥70%；**alembic round-trip**（:68）；静态检查（content/claims/dynamic code/safety eval，:98）；**OpenAPI drift**（:120）；**contract drift + 故障注入矩阵**（:133） |
| android-ci | 单测+assemble+lint；同流水线 release metadata/manifest/SBOM/provenance；**instrumentation API matrix**（:93-115） |
| release-closure | 源完整性归档门；backend 验证；Android 测试+lint+detekt+release build；签名（secrets 存在时）；provenance+package+终态门禁 |
| security-ci | CodeQL（Kotlin/Java build tracing）+ SBOM/安全报告 |
| source-integrity | workflow pin 门禁、SOURCE_MANIFEST 校验、契约锚点、source reality/依赖图 drift gate、确定性源归档 |

### 5.3 断层（本报告最重要的发现之一）

**门禁"存在" ≠ 门禁"跑在交付上"**。`DELIVERY_MANIFEST.json:19-24`（generated 2026-08-24）如实标注 `alembic_roundtrip: "not_run"`、`contract_drift_check: "not_run"`、`android_instrumentation: "not_run"`、`postgresql_docker_integration: "external_gate_not_run"`；`BUILD_PROVENANCE.json` 显示该交付由 local-dev 脏树（git_dirty: true）+ Python 3.9.6 构建。也就是说：最近一次 v0.11.0 交付验证 = 后端 pytest + Android 单测两项，其余结构性门禁（迁移回路/契约漂移/真机插桩）既未在本地跑、该产物也非 CI release-closure 产物。诚实标注值得表扬，但"pilot-candidate"的验证面实际比 CI 配置看起来的窄（P0-4）。

### 5.4 供应链

- 后端：`uv.lock` + CI `--frozen` 安装（漂移门禁合一，backend-ci.yml:21-25）——锁文件纪律好；但 pyproject 区间 + dependabot pip/uv 双源 PR 并存导致 8 个后端 dependabot 分支互相踩（P1-3）。
- Android：`gradle/libs.versions.toml` 集中管理，AGP 9.3.1 / Kotlin 2.3.20 / Compose BOM 2026.08.00 / Room 2.8.4 / sqlcipher 4.17.0，10 个 gradle dependabot 分支积压。
- SBOM：`sbom.spdx.json` 受控 + CI 生成（security-ci）。
- 已知风险：仓库当前在 `agent/organism-visual-breakthrough`（领先 origin/main 559 提交），CI 对 PR/push main 触发（backend-ci.yml:8-13）——若团队习惯在 agent 分支直推，则**五套 CI 大概率没有跑在当前 HEAD 上**（"待核实"：无法从本地确认 GitHub Actions 实际运行历史）。

---

## 6. 安全与隐私合规评估（对照产品承诺逐条核实）

| 承诺 | 结论 | 证据 |
|---|---|---|
| 本地优先 | **属实** | `SyncWorker.kt:46-48` localMode 直接 return、同步静默；本地模式端侧画像自足（LocalPortraitEngine）；STATUS §5 字段加密豁免声明"这些字段从未离开设备" |
| 端侧数据加密 | **属实（附已登记豁免）** | SQLCipher 全库 + Keystore 包装秘密 + HKDF 域分离（core/security 8 文件）；字段级加密覆盖 outbox/摘要/token（AppPreferences.kt:41-61）；豁免面（answersJson/memory.content/portrait JSON）在 STATUS.md:80-86 有评审记录且理由成立 |
| 审计不可篡改 | **属实** | 哈希链 + 事务级 advisory lock + ORM/触发器双守卫 + `/v1/audit/verify`（audit.py、immutability.py、models.py:353-373） |
| fail-closed 密钥 | **属实** | config.py:43-57 任何环境拒默认秘密；docker-compose :12-17 `:?` 必填；crypto.py:22-26 拒明文；Android Keystore fail-closed（AndroidKeystoreFieldCipher.kt:13-17） |
| 不做心理推断 | **属实（机制化）** | 模型层：DailyBehaviorAggregate 无情绪字段（models.py:484-490）；算法层：维度中性词表（dimensions.py:16）；LLM 层：SYSTEM_INSTRUCTION 禁词表（EchoContextCompiler.kt:54-55）；产物层：tool_forge 无情绪模板（tool_forge.py:6-7）；另有 claim_scan/safety_eval CI 门禁 |
| 数据最小化 | **属实** | PORTRAIT_CONTRACT §6 审计表逐环节结论；通知只留计数；App 身份不上云；telemetry 最小化（main.py:79-89） |
| Safety 与画像链隔离 | **属实** | `safety.py` 规则包仅服务 evaluate_passive（sandbox 校验用）；画像链无 safety 调用点；"人工支持与画像链相互独立"（main.py:38-39 API 描述） |
| 同意门禁 | **属实** | psychological_data/passive_sensing/voice_features 三类 consent 校验（deps.py:123-139）；画像重建/反馈均挂 passive_sensing 门禁（portraits.py:205-246） |

**残余风险（非虚假承诺，但应入册）**：① `escalations.py:83-102` 触发豁免伪造（P0-3）；② 后端 `DerivedFeature.summary` 明文（P2-5）；③ activation pepper 与 bootstrap key 复用（P2-3）；④ 无法本地核验生产部署是否真的启用 PG 触发器与磁盘加密（"待核实"，属部署侧）。

---

## 7. 优化任务清单（按 影响范围×收益 降序）

### P0（不做会出事 / 阻塞 pilot 发布）

**P0-1 主干归位：把"当前可交付真源"还给 main，并清算 dependabot**
- 现状：产品真源在 `agent/organism-visual-breakthrough`（领先 origin/main 559 提交，`git rev-list --count origin/main..HEAD`=559）；本地 main 另有 391 个分叉提交（`HEAD..main`=391，成因待核实——疑似历史改写）；28 个 dependabot 分支积压；近 15 次提交全是 docs 轮次。docs/STATUS.md:2 自称"当前可交付 main（git 受控状态）的唯一状态锚点"，与事实冲突。
- 优化目标：agent 分支合回 origin/main（或显式宣布其为主干并改名）；关闭/合并全部 dependabot 分支；删除本地失序 main；此后主干只接受短生命周期 PR，使五套 CI 真正跑在交付 HEAD 上。
- 预期收益：恢复"CI 门禁=交付验证"的等式；消除 559 提交不可回滚的单点；依赖更新解冻。
- 建议负责人：DevOps/Release + 团队主理人。

**P0-2 仓库二进制瘦身**
- 现状：`.git` 1.0GB（`git count-objects -vH` size-pack 1.03GiB）；`qa/visual-review/` 167MB 图片/视频受控入库（`git ls-files qa`=397）；根目录 20+ APK/idsig 约 250MB 散落（虽已 gitignore，`ECHO_Mind_v*.apk*` .gitignore:44-47，但占工作区且易被误打包进归档）。
- 优化目标：qa/visual-review 大资产迁 Git LFS 或外部对象存储（仓库内只留 index.html + 指针）；评估 `git filter-repo` 清历史大对象（需团队协调，收益是 clone 从 GB 级回到百 MB 级）；APK 归档至 `releases/` 或工件库。
- 预期收益：clone/CI 时间数量级下降；降低"发布归档夹带垃圾"风险。
- 建议负责人：DevOps。

**P0-3 兑现 escalation 触发豁免安全债**
- 现状：`backend/app/api/escalations.py:83-102`——`payload.trigger` 为客户端自由字符串，命中 `ESCALATION_CREATE_EXEMPT_TRIGGERS`（如 help_requested）即绕过 20/h 频控；代码注释（:84-86）自认"传豁免词即可绕过…须改为服务端可验证的信号源…删除前须安全评审"。攻击面：刷 L3 队列制造值班疲劳 / 掩盖真实红色信号。
- 优化目标：豁免判定改为服务端可验证来源（L0 screening 结果、passive 评估链标记、服务端 red 规则命中记录），trigger 仅作展示标签；补"伪造豁免"负向测试。
- 预期收益：封住已知 DoS-值班疲劳攻击面；危机链路可信度恢复。
- 建议负责人：后端负责人 + 安全负责人。

**P0-4 修复"交付=CI 产物"断链**
- 现状：DELIVERY_MANIFEST.json:19-24 四项 not_run；BUILD_PROVENANCE.json `git_dirty:true`、`python 3.9.6`（违背 pyproject.toml:12 `>=3.12`）、`ci_run_id:"unknown"`；release-closure.yml:143 自我要求"§15 禁 dirty release"。
- 优化目标：pilot 候选包必须由 release-closure workflow 产出（provenance 绑定 ci_run_id、dirty 即拒绝）；本地脚本产出的 provenance/manifest 强制标 `release_type: development` 且 UI/文档不得引用为交付证据；补跑一次全套（alembic round-trip + contract drift + instrumentation matrix）作为 v0.11.x 重锚。
- 预期收益：门禁声明与验证事实重新对齐；pilot 审查者可信任 manifest。
- 建议负责人：DevOps/Release。

### P1（显著提升质量与效率）

**P1-1 画像引擎三镜像契约化**
- 现状：`backend/app/services/portrait/dimensions.py` ↔ `android/feature/observation/.../LocalPortraitEngine.kt` ↔ `android/feature/qa/.../QaPortraitMirror.kt` 三份语义镜像（镜像声明分别见 LocalPortraitEngine.kt:9-13、QaPortraitMirror.kt:13-20），golden 用例分散于 test_portrait_golden.py / LocalPortraitGoldenTest.kt / QaPortraitMirrorGoldenTest.kt 三处；另有一处**有意漂移**（LocalPortraitEngine.kt:155-160 本地 WARMING_UP 附加事实句）。
- 目标：建立单一 golden 向量文件（如 `qa/golden/portrait_vectors.json`，后端导出、两端测试共同消费）；漂移分支显式登记进 golden 元数据；顺带产品裁定 SCREEN_TIMING 量-时混义（LocalPortraitEngine.kt:318-323）。
- 收益：改一处阈值不再需要人工同步三份实现；镜像漂移从"祈祷"变"红灯"。
- 负责人：算法/后端 + Android。

**P1-2 后端结构减债**
- 现状：`escalations.py` 656 行 God-router；legacy 三表退役逾期（models.py:99-101 "v0.8 removal target"，现 v0.11）。
- 目标：escalations 按读写/工作台/复核拆为 2-3 个子模块（对齐 portraits.py 拆分范式）；制定 legacy 表真退役迁移（读路径已收敛至 case-review/DSR/build_trend）。
- 收益：维护面收敛；模型/表与文档声明一致。
- 负责人：后端。

**P1-3 依赖策略三选一**
- 现状：pyproject 区间 + uv.lock --frozen + dependabot（pip/uv/gradle/actions 28 分支）三机制并存、PR 互踩。
- 目标：定一个策略（推荐：区间+lock 保持，dependabot 改月度分组 PR / 或改 Renovate group）；先一次性清算 28 分支。
- 收益：依赖更新恢复流动，安全补丁不被积压。
- 负责人：DevOps。

**P1-4 Android `AppPreferences` 拆分 + DataStore 迁移**
- 现状：562 行混装 token/身份/Skill 缓存/presence/sensing（AppPreferences.kt:10-70+）。
- 目标：按域拆为 AuthPreferences/SkillCache/SensingPrefs（部分已存在 PassiveSensingPrefs）；SharedPreferences→DataStore；token 存储保持 FieldCipher 加密不变。
- 收益：账户切换/订阅翻转的副作用面收敛（当前 accessToken setter 里埋着 localMode 联动，AppPreferences.kt:48-49）。
- 负责人：Android。

**P1-5 静态检查加深**
- 现状：ruff 仅 E4/E7/E9/F（pyproject.toml:33-38 注释自认收窄原因）；detekt 27 规则（STATUS §3）。
- 目标：分批启用 ruff B/SIM/C4/UP 与 detekt complexity 系列；以"新代码严格、存量豁免清单"方式推进。
- 收益：把目前靠人肉审计（CHANGELOG 里大量 audit 轮次）发现的问题前移到 CI。
- 负责人：后端 + Android 各自。

**P1-6 文档与状态锚点收敛**
- 现状：docs 339 文件、CHANGELOG 277 轮、git 近期提交几乎全是 docs；STATUS/RELEASE_BASELINE/MASTER_ROADMAP/PRD 多锚点并存。
- 目标：CHANGELOG 按纪元归档压缩（冷数据移 docs/archive）；STATUS 保持唯一活锚（已有雏形）；每轮"docs: refresh"提交合并降频。
- 收益：降低上下文检索成本与提交噪声，让 git log 恢复代码信号。
- 负责人：主理人/PM。

### P2（锦上添花）

**P2-1 app 模块减重**：data/sensing/runtime 逐步下沉（`core/data` 或 feature 模块），目标 app main < 60 文件。证据：app 246/456 kt（§2.2）。负责人：Android。
**P2-2 `OrganismFrameComputer.compute` 拆分**：约 350 行单函数（OrganismFrameComputer.kt:100-449）按 pass 拆私有纯函数并补渲染黄金快照。负责人：Android 视觉。
**P2-3 activation pepper 独立**：`activation.py:56-60` pepper 复用 BOOTSTRAP_KEY，改独立 `ACTIVATION_PEPPER` 秘密并纳入 fail-closed 校验。负责人：后端/安全。
**P2-4 .gitignore 补全**：显式加 `.mypy_cache/`、`.ruff_cache/`（当前靠缓存自嵌套 .gitignore，脆弱）；处理 .trae/ 已跟踪文件与忽略规则的矛盾（§1.2-4）；删除 `backend/echo_mind.db` 本地残留。负责人：DevOps。
**P2-5 `DerivedFeature.summary` 明文落库登记**：在 PORTRAIT_CONTRACT §6 或隐私文档显式登记后端侧明文存储决策与理由（models.py:308）。负责人：安全/隐私。
**P2-6 PersonalAnswerEngine 复杂度观察**：STATUS 已入册触发条件，维持"单 object 不拆"决策，监控回答族数量。负责人：Android。
**P2-7 覆盖率门禁对齐**：Android 补 JaCoCo/Kover 基线；后端 SQLite 路径补覆盖率可见性（现仅 PG job 有 70% 门，backend-ci.yml:64-66）。负责人：各端。

---

## 8. 附录

### 8.1 评审方法

1. 全仓结构扫描（git ls-files 分类计数、模块 kt/py 计数、体积分析、git 分支/历史/ignore 验证）。
2. 后端 `backend/app` 69 文件：核心 25 文件全文精读（main/config/database/auth/deps/models/schemas 概览/全部 portrait/baseline/aggregates 链、audit/crypto/immutability/activation/safety/sandbox 六件、routes/portraits/escalations[前 200 行]/deps），其余按签名+注释扫描。
3. Android 14 模块边界全览 + 12 个关键文件精读（EchoDatabase、LocalPortraitEngine、QaPortraitMirror、EchoContextCompiler、PersonalAnswerEngine[结构]、AppPreferences[部分]、AndroidKeystoreFieldCipher、DatabasePassphraseDerivation、EchoRuntimeCoordinator[头部]、SyncWorker[头部]、PassiveSensingService[头部]、libs.versions.toml）。
4. 全仓异味扫描：TODO/FIXME/HACK、GlobalScope/runBlocking、`!!`、硬编码 URL/IP、超长文件/函数。
5. 契约核对：PORTRAIT_CONTRACT.md ↔ models/dimensions 逐条；docs/openapi.json 61 路径 ↔ api 路由抽查一致。
6. CI/门禁：5 个 workflow 逐 job 核对 ↔ DELIVERY_MANIFEST not_run 项 ↔ BUILD_PROVENANCE。

### 8.2 未覆盖项与理由

- `backend/tests` 53 文件未逐行读（按文件名/规模/结构抽查，覆盖率结论引自 DELIVERY_MANIFEST 与 STATUS 自动生成段）。
- `qa/visual-review/` 167MB 二进制内容未审（仅计入卫生问题）。
- `wearable/xiaomi-vela` JS、`integrations/answatch`、`content-packs` 校验细节、`safety-eval` 语料：未深读（不影响主结论）。
- docs 368 文件仅精读 STATUS.md、PORTRAIT_CONTRACT.md，其余按目录/标题扫描。
- GitHub Actions 实际运行历史、生产部署形态（PG 触发器/磁盘加密/KMS）无法从本地仓库核验，均标注待核实。

### 8.3 数字快照

| 项 | 值 | 来源 |
|---|---|---|
| git 受控文件 | 1,612 | git ls-files |
| backend/app | 69 py / 9,100 行 | wc -l |
| backend tests | 53 文件 / 1120 passed + 1 skipped | DELIVERY_MANIFEST/STATUS |
| Android | 456 kt / 67,962 行 / 14 模块 | find+wc |
| Android 单测 | 1,387 全绿 | STATUS §3 |
| OpenAPI 路径 | 61 | docs/openapi.json |
| docs | 339 受控（CHANGELOG 277） | git ls-files |
| .git 体积 | 1.0GiB | git count-objects |
| 本地分支 | 35（28 个 dependabot） | git branch |
| 分支偏差 | HEAD 领先 origin/main 559 / 本地 main 分叉 391 | git rev-list |
| 版本 | v0.11.0 (versionCode 8) pilot-candidate | config.py:6 / STATUS |
