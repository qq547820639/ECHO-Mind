# Round 257 — 端到端交付收口（End-to-End Delivery Closure）

日期：2026-08-28　｜　主干：`main`　｜　本轮提交区间：`03ddeb5b..main`（归位提交 `e81c52f1`）
｜　状态：`pilot-candidate`（clean tree，`release_type=release`）

## 起因

按 `docs/architecture/2026-08-28-repo-deep-review-and-optimization-backlog.md` 的评审结论逐项核销。
该评审的判定是「代码 9 / 测试 8.5 / 安全 9，工程卫生 4 / 发布治理 5」，本轮只针对后两项。

## P0 四项

1. **P0-1 主干归位** ✅
   - 远端 `main`（`1c559ea6`）含 PR #46/#47/#51 三个 merge，其二号父节点 `45baef29` 与 HEAD 合并基数一致
     → 纯拓扑合并（实测 0 冲突 / 0 文件变化）→ 快进 `main`（归位提交 `e81c52f1`；其后仅追加发布元数据提交）。
     无 force-push、无历史重写。
   - 33 个待清理分支先打归档标签 `archive/20260828/*` 并推送远端，再删除 31 个 dependabot 分支。
   - 本地失序 `main`（391 个 rebase 重复提交）以 `git branch -f main HEAD` 重建。
   - 收益：五套 CI（backend-ci / android-ci / release-closure / security-ci / source-integrity）从此跑在交付 HEAD 上。

2. **P0-2 仓库体积** ✅（预防）/ ⚠️（历史瘦身排 runbook）
   - 新增 `scripts/check_repo_bloat.py` + `source-integrity` 作业：单文件 >2MB / 总量 >250MB / 生成物二进制入库 → 红灯；`.git` >1GiB 仅告警。
   - 34 个历史本地 APK（213MB）→ `releases/local-apk-archive/`；`.gitignore` 补 `.mypy_cache/` `.ruff_cache/` 与 `.trae` 逐层 re-include。
   - `git filter-repo` 不执行（会重写全部 SHA 使发布锚点失效），改为交付 runbook：`docs/operations/REPO_HISTORY_SLIM_RUNBOOK.md`。

3. **P0-3 escalation 豁免伪造** ✅
   - 删除客户端自证豁免；新增 `resolve_rate_limit_exemption()`，豁免只认服务端写入的 L0 准入筛查 / 红色 RiskSignal，30 分钟新鲜窗口。
   - 新增审计 `escalation.exemption_denied` / `escalation.rate_limit_exempted`。
   - `test_support_rate_limit.py` 4 → 10 例（含伪造豁免负向、跨用户、陈旧证据不豁免）。

4. **P0-4 交付 = 门禁** ✅
   - `DELIVERY_MANIFEST` 新增 `validation_evidence`（命令/环境/时间/结果）与 `validation_environment`；未执行门禁一律不出现在证据表。
   - `generate_provenance.py` 新增 requires-python 合规校验 + `--strict-python`；dirty 或解释器不合规 → `release_type=development`。
   - `package_release.sh` 9/9 `DISTRIBUTION CLOSURE PASS`（`git_dirty=false`，python 3.14.3 ≥ 3.12）。

## P1

- **P1-1 画像 golden 单一源** ✅（backend）：`qa/golden/portrait_vectors.json` 为唯一源，`mirror_goldens/*` 降为生成物；
  新增 `test_golden_single_source.py` 消费方登记门；`LocalPortraitEngine` 登记为 `not_wired + BLOCKED_ENV_ANDROID_SDK`。
- **P1-3 依赖策略** ✅：dependabot 改分组月度 PR（minor/patch 合批、major 单独，上限 3）；清理后已实测按新配置重开分组分支。
- P1-2 / P1-4 / P1-5 / P1-6：**本轮不执行**（可维护性重构、且依赖 Android SDK 验证），理由见交付报告 §5 D-6。

## 实测门禁

backend **1131 passed + 1 skipped**；ruff 0；mypy strict 0（69 文件）；contract drift 61 路径 OK；
故障注入 18/18；workflow pins 66/66；契约锚点 24/24；alembic 迁移回路（SQLite）OK；
画像 golden 5 cases 零漂移；repo bloat PASS；源归档 1597 文件双向校验 PASS；final package §18 PASS。

## 环境阻塞（非代码缺陷）

`BLOCKED_ENV_ANDROID_SDK`（无 SDK，Android 1387 单测/lint/detekt/assemble 无法本地复跑，权威为 CI android-ci）、
`BLOCKED_ENV_DOCKER_POSTGRES`（colima 未启动，PG 覆盖率与 PG 迁移以 CI 为准）、
`BLOCKED_ENV_APKSIGNER`（`signing_stage=unknown`；APK sha256 与既有 provenance 一致 `e6468e9f…`，早前实测 v2,v3）。

## 变更

28 文件 / +2479 −363（相对 `03ddeb5b`）。完整报告与决策依据：
`docs/architecture/2026-08-28-end-to-end-delivery.md`。
