# ECHO Mind 端到端交付收口报告（2026-08-28）

> 执行角色：DevOps 交付专家（全授权自主裁决）
> 基线：`docs/architecture/2026-08-28-repo-deep-review-and-optimization-backlog.md`（深度评审 P0×4 / P1×6 / P2×7）
> 交付主干：`main`（本轮提交区间 `03ddeb5b..main`，终态 SHA 以 `git rev-parse main` 为准）
> 交付线起止：归位提交 `e81c52f1`（主干归位）→ 语义提交 `f30bdda5` / `1c7abffc` → 终态元数据提交
> 工作树状态：clean（`git status --porcelain` 为空），`release_type=release`
> 发布状态：`pilot-candidate`（`production_claim: false`，外部发布门未完成）

---

## 0. 执行摘要

把「代码好、治理乱」的倒挂一次性扳回来：**P0 四项全部核销，P1 中 P1-1/P1-3 收口、P1-2/P1-4/P1-5/P1-6 显式裁决为不执行并登记理由**；新增 3 个机器门禁，把"以后不再退化"变成 CI 红灯，而不是靠人记着。

| 维度 | 交付前 | 交付后 |
|---|---|---|
| 主干 | 真源在 `agent/*` 分支，`main` 落后 560 提交；本地 `main` 另分叉 391 提交 | **`main` = 当前可交付真源**，五套 CI 跑在交付 HEAD 上 |
| 依赖队列 | 31 个 dependabot 分支互踩 | 0 个积压分支；配置改**分组月度 PR**，清理后 dependabot 已按新配置重开分组分支（实测生效） |
| 安全债 | `trigger` 客户端自由字符串可伪造豁免，绕过 20/h 频控 | 豁免只认**服务端写入的证据**（L0 准入 / 红色 RiskSignal，30min 新鲜窗口）；伪造留痕 |
| 交付证据 | `DELIVERY_MANIFEST` 四项 `not_run`；provenance `git_dirty:true` + python 3.9.6（违反 ≥3.12） | 四项中两项实测注入并附**可复现证据**；`git_dirty:false`、`python_compliant:true`、`release_type:release` |
| 仓库体积 | `.git` 1059MB；34 个 APK 散落根目录 | 新增体积门禁（预防）；APK 归档 213MB；历史瘦身改为带回滚的 runbook（不不可逆硬来） |
| 画像 golden | 三份镜像、用例散落三处 | **单一数据源** `qa/golden/portrait_vectors.json` + 生成物投影 + 消费方登记门 |
| backend 测试 | 1120 passed + 1 skipped | **1131 passed + 1 skipped**（+11 例，含 6 例伪造豁免回归） |

**未做的（有意为之，附理由）**：`git filter-repo` 历史瘦身、Android Kotlin 侧重构（P1-4）、`escalations.py` 拆模块（P1-2）、ruff 规则集扩张（P1-5）——见 §5。

---

## 1. 核销清单（逐条）

### P0-1 主干归位与 dependabot 清算 —— ✅ PASS

**做法（按顺序，可回滚）**
1. 备份：33 个待清理分支（31 dependabot + `main` + `visual-runtime`）各打归档标签 `archive/20260828/<branch>` 并**推送到远端**（异地、零磁盘成本）。
2. 推送在途工作：`agent/organism-visual-breakthrough` → `45baef29..cc6d5974`。
3. 归位：远端 `main`（`1c559ea6`）含 3 个 PR merge（#46/#47/#51），其二号父节点 `45baef29` 与 HEAD 合并基数一致 → 与 HEAD 合并为**纯拓扑合并**（实测 0 冲突、0 文件变化），**快进**推送 `main`：`1c559ea6..cc6d5974`。**全程无 force-push、无历史重写。**
4. 本地失序 `main`（391 个 rebase 重复提交，内容与 HEAD 已重合）用 `git branch -f main HEAD` 重建。
5. 删除 31 个 dependabot 分支（本地 + 远端）。

**收益**：`backend-ci` / `android-ci` / `source-integrity` 的 push 触发器只监听 `main` / `release/**`，`release-closure` 监听 tag `v*`，`security-ci` 监听 PR + 每周定时——**五套 CI 从此真正跑在交付 HEAD 上**，此前它们大概率从未跑在真源上（评审 §5.4 的"待核实"项，现已消除）。

### P0-2 仓库二进制瘦身 —— ✅ 预防生效 / ⚠️ 历史瘦身需协调窗口

| 动作 | 状态 |
|---|---|
| `scripts/check_repo_bloat.py` 新增门禁（单文件 >2MB 失败、受控总量 >250MB 失败、`.apk/.aab/.rpk/.zip/.mp4/...` 入库失败、`.git` >1GiB 告警） | ✅ 已生效，本地实测 PASS |
| `source-integrity` 增加 repo bloat 作业 | ✅ |
| 34 个历史本地 APK（213MB）→ `releases/local-apk-archive/`（已 gitignore），根只留 provenance 绑定的 `ECHO_Mind_v0.11.0.apk` / `.idsig` | ✅ |
| `.gitignore` 补 `.mypy_cache/` `.ruff_cache/`；`.trae/` 与受控 `LEDGER.md` 的规则矛盾用逐层 re-include 修好 | ✅ |
| `git filter-repo` 清历史大对象 | ⚠️ **不执行**（见 §5 决策 D-1），runbook 已交付 |

实测：受控 1,616 文件 / 180.2MB，其中 `qa/visual-review` 166.1MB（历史债）；非视觉资产仅 14.1MB。最大单文件 1.21MB——**没有超大文件，是"反复重写的中等二进制"问题**。

### P0-3 escalation 豁免伪造 —— ✅ PASS（安全债兑现）

**缺陷**：`backend/app/api/escalations.py` 用 `payload.trigger in ESCALATION_CREATE_EXEMPT_TRIGGERS` 判定豁免；`trigger` 是客户端自由字符串 → 任意客户端传 `l0_current_danger` 即无限创建 L3 支持请求（刷值班队列制造疲劳 / 掩盖真实红色信号）。审计 T6-P2-4 挂了三个轮次。

**修复**
- 删除客户端自证豁免；新增 `services/escalation.resolve_rate_limit_exemption(db, tenant_id, user_id)`，豁免只授予**服务端自己写入**的证据：
  - `OnboardingScreening.current_danger=True`（L0 准入筛查），或
  - `RiskSignal.severity == "red"`（服务端红色风险信号），
  - 且必须落在 `ESCALATION_EXEMPTION_LOOKBACK = 30 分钟`新鲜窗口内（防止陈年筛查变成永久免限流通行证）。
- `trigger` 降级为**展示标签**；`help_requested`（Me 页自助按钮）恒计入频控——它表达"我想要人工支持"，不是危机证据。
- 新增两个审计动作：`escalation.exemption_denied`（自称危机但无证据 → 值班可识别伪造/误报）、`escalation.rate_limit_exempted`（放行留痕，回答"这条为什么跳过了频控"）。
- `backend/tests/test_support_rate_limit.py`：4 例 → **10 例**，新增：伪造危机触发不再豁免（负向）、自助按钮受限、L0 证据放行、红色 RiskSignal 放行、陈旧证据不豁免、跨用户证据不豁免、伪造留痕断言。

**兼容性**：`EscalationCreate` schema 未变（trigger 仍是自由字符串，`test_tenant_portrait` 等用 `"t"`/`"test"` 的既有用例不受影响）；OpenAPI 无新增路径，drift 门仅出现 3 处历史 docstring 差异（已重导出）。

### P0-4 交付 = CI 产物 —— ✅ PASS

| 缺陷 | 修复 |
|---|---|
| `validation` 只给结论，无法回答"passed 是谁跑的" | 新增 `validation_evidence`：每个门禁写清**命令 + 环境 + 时间 + 结果**；未执行的门禁**不出现**（禁止伪造） |
| 构建环境不透明 | 新增 `validation_environment`：builder / python_version / git_commit / git_dirty / blocked_by |
| provenance 记录 python 3.9.6（违反 `requires-python >=3.12`） | `generate_provenance.py` 新增 requires-python 合规校验 + `--strict-python`；**dirty 树或解释器不合规 → `release_type` 恒为 `development`** |
| 本地产物自称 `builder_environment: local-dev` 无法区分 | 支持 `BUILDER_ENVIRONMENT` 显式自报；`ci` 只能由 `CI`/`GITHUB_ACTIONS` 判定（禁止冒充） |
| 证据变量口头传递 | 新增 `scripts/release_evidence.env.sh`（带纪律注释，每次发布前须更新计数与日期） |

**实测结果**（`./scripts/package_release.sh --skip-preflight`，9/9 `DISTRIBUTION CLOSURE PASS`）：

```
== 1/9 SOURCE_MANIFEST verify ==          1597 文件一致
== 3/9 deterministic source archive ==    zip + tar.gz，prefix echo-mind-portrait-core
== 4/9 verify extracted archive ==        1597/1597 hash verified，0 unexpected，NFC PASS
== 5/9 SBOM ==                            80 declared packages
== 6/9 SOURCE_MANIFEST + DELIVERY ==      1131 passed + 1 skipped
== 7/9 BUILD_PROVENANCE ==                release_type=release, git_dirty=false, python 3.14.3 ≥ 3.12
== 8/9 final package ==                   10 entries
== 9/9 verify final package ==            provenance_binding PASS / source_archives_verified PASS
```

### P1-1 画像引擎三镜像收敛 —— ✅ backend 收口 / ⚠️ Android 一侧登记未接线

**模型**（关键设计：Android 侧**零改动**即可享受单一源）

```
qa/golden/portrait_vectors.json          ← 唯一数据源（输入 today/baseline_metrics + 期望 dimensions 同源）
   │  backend/scripts/generate_portrait_golden.py [--update|--check]
   ├─► qa/reports/mirror_goldens/fixture.json   （生成物，Android QaPortraitMirrorGoldenTest 消费）
   └─► qa/reports/mirror_goldens/golden.json    （生成物，同上）
```

- `backend/tests/test_mirror_golden.py`（重写）：① 规范源 `expected_dimensions` == `compute_dimensions`；② 生成物与规范源**逐字节一致**（禁止手改生成物绕开单一源）；③ 有意漂移必须显式登记；④ `qa/` 下不得存在未登记的维度 golden 文件。
- `backend/tests/test_golden_single_source.py`（新增）：消费方登记门——`wired*` 必须真引用（防"声明已接线实际没有"）；`not_wired` 必须填 `blocked_by`（未收口不得静默），CI 打印提醒但不阻塞。
- `declared_divergences` 显式登记两处已知有意漂移：`local_warming_up_extra_fact`（端侧第 1 天附加事实句）、`screen_timing_quantity_vs_time_wording`（量-时混义，待产品裁定）。
- 已删除 `backend/scripts/export_mirror_golden.py`（被新生成器取代）。
- **未接线**：`LocalPortraitGoldenTest.kt`（LocalPortraitEngine 端侧镜像）用例仍硬编码在 Kotlin 内，登记为 `not_wired` + `BLOCKED_ENV_ANDROID_SDK`。接线补丁见 §5 决策 D-2。

### P1-3 依赖策略 —— ✅ PASS

选「区间 + `uv.lock` 钉死 + dependabot 分组月度 PR」（评审给出的三选一推荐项）：

- 每 ecosystem `open-pull-requests-limit: 10 → 3`；
- minor/patch **合批**成一个 PR，major **单独**成 PR（mypy/pytest/AGP 大版本会改变门禁判定，必须人工评审）；
- 周 → **月**（周一 09:00 Asia/Shanghai）；
- 不再单独配置 `pip` ecosystem（此前 pip + uv 双源互相踩）。

实测生效：清理后 dependabot 立即按新配置重开 `backend-python-minor-patch-*` / `backend-python-major-*` **分组**分支（验证新配置已被采纳；这两个分支也在本次清理范围内，按需在下一个调度周期重现）。

---

## 2. 端到端门禁实测（全部本机真实执行，2026-08-28）

| 门禁 | 命令 | 结果 |
|---|---|---|
| backend 全量测试 | `backend/.venv/bin/python -m pytest -q`（Python 3.12.13 / SQLite in-memory） | **1131 passed + 1 skipped / 0 failed** |
| ruff | `ruff check app tests` | **All checks passed** |
| mypy strict | `mypy app` | **0 issues / 69 source files** |
| 契约漂移 | `scripts/contract_drift_check.py` | **61 路径 OK** |
| 故障注入矩阵 | `scripts/fault_injection_check.py` | **18/18 PASS** |
| workflow SHA pin | `scripts/verify_workflow_pins.py` | **5 workflow / 66 uses 全 SHA** |
| 契约锚点 | `scripts/contract_compliance_check.py` | **24 个锚点全部存在** |
| 内容包 / claim / 动态代码 / safety | 四个静态脚本 | **4 包 / PASS / 无动态代码 / 650 语料** |
| Alembic 迁移回路（SQLite） | `upgrade head → downgrade base → upgrade head` | **0 error** |
| OpenAPI 漂移 | `export_openapi.py` + `git diff` | 3 处历史 docstring 差异已重导出并提交 |
| 画像 golden | `generate_portrait_golden.py --check` | **5 cases 零漂移** |
| 仓库体积 | `scripts/check_repo_bloat.py` | **PASS**（`.git` 1059MB 触发历史债 WARN） |
| 源归档双向校验 | `verify_source_archive.py` ×2 | **1597/1597 hash verified，0 unexpected** |
| 最终发布包 §18 | `verify_final_package.py` | **PASS**（provenance_binding + archives_verified） |

**整体**：`DISTRIBUTION CLOSURE PASS —— 0.11.0`；`git_dirty=false`；`release_type=release`。

---

## 3. 变更清单（28 文件 / +2479 −363，相对评审基线 `03ddeb5b`）

**安全**　`backend/app/api/escalations.py`（+50/−9）　`backend/app/services/escalation.py`（+69/−7）
**测试**　`backend/tests/test_support_rate_limit.py`（4→10 例）　`test_mirror_golden.py`（重写）　`test_golden_single_source.py`（新增）
**工具**　`backend/scripts/generate_portrait_golden.py`（新增，删 `export_mirror_golden.py`）　`scripts/check_repo_bloat.py`（新增）　`scripts/release_evidence.env.sh`（新增）　`scripts/update_release_metadata.py`　`scripts/generate_provenance.py`
**CI/治理**　`.github/dependabot.yml`（重写）　`.github/workflows/backend-ci.yml`　`source-integrity.yml`　`.gitignore`
**数据/文档**　`qa/golden/portrait_vectors.json`（新增）　`qa/reports/mirror_goldens/{fixture,golden}.json`（转生成物）　`docs/operations/REPO_HISTORY_SLIM_RUNBOOK.md`（新增）　`docs/STATUS.md`　`docs/openapi.json`　`SOURCE_REALITY_REPORT.md`　`ANDROID_DEPENDENCY_GRAPH.md`　`LEDGER.md`（T6-P2-4 清偿）
**发布元数据**　`SOURCE_MANIFEST.sha256`（1597 文件）　`DELIVERY_MANIFEST.json`　`BUILD_PROVENANCE.json`　`RELEASE_ARTIFACT_MANIFEST.sha256`

**Android 侧唯一改动**：`QaPortraitMirrorGoldenTest.kt` 的 KDoc 注释（说明 fixture/golden 已转为生成物）——注释级，无编译风险。

---

## 4. 交付物

| 产物 | 路径 | 校验 |
|---|---|---|
| 最终发布包 | `releases/ECHO_Mind_v0.11.0.release.zip`（353MB，10 entries） | §18 门禁 PASS |
| 确定性源归档 | `releases/ECHO_Mind_PortraitCore_v0.11.0.zip` / `.tar.gz` | 1597 文件双向校验 PASS |
| 交付清单（含证据） | `DELIVERY_MANIFEST.json` | 机器生成 |
| 构建溯源 | `BUILD_PROVENANCE.json`（`git_dirty:false`，`release_type:release`） | 机器生成 |
| 源完整性清单 | `SOURCE_MANIFEST.sha256` + `RELEASE_ARTIFACT_MANIFEST.sha256` | verify PASS |
| SBOM | `sbom.spdx.json`（80 packages） | 机器生成 |
| 交付 APK | `ECHO_Mind_v0.11.0.apk`（sha256 `e6468e9f…`，与既有 provenance 一致） | 见 §6 阻塞 B-3 |

---

## 5. 自主裁决记录（模糊/冲突处的决策与依据）

| # | 决策点 | 裁决 | 依据 |
|---|---|---|---|
| D-1 | `.git` 1GB 是否立即 `filter-repo` 瘦身 | **否**，改为预防闸门 + runbook | 重写全部 SHA 会使 `RELEASE_BASELINE`（`88db3b9`）、`BUILD_PROVENANCE.git_commit`、`SOURCE_MANIFEST` 绑定、277 轮 CHANGELOG 引用**全部失效**，且需 force-push + 全员重新克隆。不可逆、影响面跨越交付契约，收益（clone 提速）不阻塞 pilot 评审。**先确保"不再变胖"（门禁），再排协调窗口做不可逆操作。** |
| D-2 | 是否修改 Android Kotlin（P1-1 接线 / P1-4 AppPreferences 拆分） | **不改**，登记 `not_wired` + 补丁级说明 | 本工作区**无 Android SDK**（`~/Library/Android/sdk` 与 `local.properties` 指向的 `/tmp/echo-build/tools/sdk` 均不存在），Kotlin 改动**无法编译验证**。盲目提交可能让 1387 个单测与 Android CI 变红——比留一个机器可见、有门禁的未收口项更糟。接线步骤：在 `LocalPortraitGoldenTest` 中用既有 `mirrorGoldenDir()` 同款向上查找 `.git` 的方式读 `qa/golden/portrait_vectors.json`，按 `cases[].expected_dimensions` 对拍 `LocalPortraitEngine` 维度输出，并把 `portrait_vectors.json` 里该消费方状态从 `not_wired` 改为 `wired`（改后门立即生效）。 |
| D-3 | 是否合并 31 个 dependabot 分支 | **否**，先改策略再清队列 | 依赖升级（尤其 gradle major：AGP/Kotlin/Robolectric）需要 Android 单测 + assemble 验证，本环境无法执行，盲合风险高于收益。先配置分组月度 PR 消除"每依赖一个 PR"的根因，再清理；dependabot 会按新配置重开可评审的分组 PR。 |
| D-4 | exemption 是否保留"用户自助按钮"豁免 | **不保留** | `help_requested` 是客户端自助动作，属"我想要人工支持"，不是服务端可验证的危机证据；20 次/小时的支持请求已远超合理区间，且此时机构侧已有未结案件在工作。真实危机信号（L0/红色信号）仍 100% 放行。 |
| D-5 | 是否新增 `Escalation.origin` 列区分来源 | **不新增** | 会引入迁移 + 三端同步成本；改用"只认服务端写入的两类证据行"即可达到同等强度且无 forgeable 路径。 |
| D-6 | P1-2（escalations.py 656 行拆模块）/ P1-4（AppPreferences 拆分）/ P1-5（ruff 规则扩张）/ P1-6（文档压缩） | **本轮不执行** | 均属**可维护性重构**，无功能/安全/交付阻塞；且 P1-2/P1-4 的验证依赖 Android SDK（不可本地验证）。按"先闭环 P0、再动无阻塞重构"排序，登记为下一轮候选。ruff 扩张需"新代码严格 + 存量豁免清单"的配套机制，贸然开会一次性产生大量报错，属独立工作项。 |
| D-7 | 主干命名 | 保留 `main`，不改名 | 改名会破坏远端 URL、PR 与本地克隆；`main` 已是五套 CI 的监听分支，归位即可，改名无收益。 |
| D-8 | `visual-runtime` 分支 | 保留（已打归档标签） | 非 dependabot、非主干，内容未被 HEAD 包含，删除会丢失潜在在途工作。 |

---

## 6. 外部阻塞与替代方案（无法自主解决）

| # | 阻塞 | 影响 | 替代方案 / 权威门禁 |
|---|---|---|---|
| B-1 | `BLOCKED_ENV_ANDROID_SDK`：本工作区无 Android SDK | Android 1387 单测 / lint / detekt / assembleRelease / instrumentation 无法本地复跑 | **CI `android-ci` 为准绳**（`./gradlew testDebugUnitTest lintDebug detekt` + instrumentation API matrix）。本地复跑前置：`sdkmanager` 安装 platforms;android-35/36 + build-tools，或恢复 `/tmp/echo-build/tools/sdk`。另注意 `docs/STATUS.md` §5 已登记：路径含空格时 AGP dexing transform 会失败，本地 assemble 请用无空格路径副本。 |
| B-2 | `BLOCKED_ENV_DOCKER_POSTGRES`：colima 未启动 | PG 覆盖率（≥70%）与 PG 迁移回路无法本地执行 | **CI `backend-ci` 的 `test-postgres` / `alembic-postgres` job**。本地替代已执行：SQLite 全量测试 + SQLite 迁移回路（均 PASS）。 |
| B-3 | `BLOCKED_ENV_APKSIGNER`：无 apksigner | 本轮 provenance 的 `signing_stage`/`signature_scheme` 记 `unknown` | APK 二进制 sha256 与既有 provenance **完全一致**（`e6468e9f…`），早前在具备 SDK 的环境实测为 `signed` / `v2,v3`；`RELEASE_ARTIFACT_MANIFEST` 仍绑定该二进制。**不是签名变化，是验证能力缺失**；CI `release-closure`（有 SDK/secrets）为权威。 |
| B-4 | `BLOCKED_EXTERNAL_*`（Band10 真机 / 小米穿戴 SDK / 生产签名 / 长跑） | Wearable 面仍为 Developer Preview，不得宣称 Production Verified | 沿用 `docs/wearable/XIAOMI_BAND10_CAPABILITY_MATRIX.md` §4 的逐项登记；软件侧（RPK 构建 + 官方模拟器 212×520 安装与渲染）已实测 PASS，等真机资源即可闭环。 |
| B-5 | GitHub 分支保护 / 规则集 | 无法在本机设置（需仓库管理员或 `gh auth login`） | 建议：对 `main` 开启「需 PR + 五套 CI 全绿 + 禁止 force-push」；本轮虽未强制推送，但主干已归位，规则应尽快补上以防回归。命令：`gh api -X PUT repos/qq547820639/ECHO-Mind/branches/main/protection ...`（需先 `gh auth login`）。 |

---

## 7. 交接与运维

**日常命令**
```bash
# 发布收口（干净树上执行；证据变量先 source）
set -a && source scripts/release_evidence.env.sh && set +a
REUSE_REPORT=1 python3 scripts/update_release_metadata.py   # 先刷新清单
./scripts/package_release.sh                                 # 9 步收口

# 门禁自检
backend/.venv/bin/python -m pytest -q
backend/.venv/bin/ruff check app tests && backend/.venv/bin/mypy app
python3 scripts/contract_drift_check.py && python3 scripts/fault_injection_check.py
python3 scripts/check_repo_bloat.py
python3 backend/scripts/generate_portrait_golden.py --check
```

**画像维度语义变更流程**（P1-1 后的唯一正确姿势）
1. 改 `backend/app/services/portrait/dimensions.py`；
2. `python3 backend/scripts/generate_portrait_golden.py --update`（重生成规范源 + 同步生成物）；
3. 同步两端镜像实现（QaPortraitMirror / LocalPortraitEngine），各自测试转绿；
4. **禁止**手改 `qa/reports/mirror_goldens/*`（逐字节门会红）。

**回滚**
- 分支清理：归档标签 `archive/20260828/<branch>` 仍在远端，一键恢复：`git push origin archive/20260828/<b>:refs/heads/<b>`。
- 主干：`main` 归位是纯快进/拓扑合并，`1c559ea6` 仍在历史中；`agent/organism-visual-breakthrough` 保留为历史工作分支。
- 代码改动：7 个提交（见 `git log 03ddeb5b..HEAD`），`f30bdda5`（安全 + golden + 体积门禁）与 `1c7abffc`（交付真值化）为两个可独立 revert 的语义单元。

---

## 8. 剩余待办（已登记，非半成品）

| 项 | 优先级 | 前置 | 说明 |
|---|---|---|---|
| LocalPortraitEngine 接入单一 golden | P1 | Android SDK | §5 D-2 给出接线步骤；门禁已把缺口登记为 `not_wired` |
| SCREEN_TIMING 量-时名实混义产品裁定 | P1 | 产品决策 | 已在 `portrait_vectors.json.declared_divergences` 显式登记；裁定后 `--update` 重生成 |
| 历史瘦身执行 | P2 | 团队协调窗口 | `docs/operations/REPO_HISTORY_SLIM_RUNBOOK.md` 含完整步骤/回滚/验收 |
| P1-2 / P1-4 / P1-5 / P1-6 | P2 | Android SDK / 独立工作项 | §5 D-6 说明为何本轮不做 |
| `main` 分支保护规则 | P1 | 仓库管理员 | §6 B-5 |
