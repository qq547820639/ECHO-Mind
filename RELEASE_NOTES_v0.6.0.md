# ECHO Mind Path A v0.6.0 Release Notes

状态：`pilot-candidate`，不适用于无人值守生产服务。
基线：`main@bf9f778` 之后的本迭代收口；对外版本从 0.2.0 统一到 **0.6.0**（分支 `main`）。

## 本迭代目标

把"半落地的被动感知范式"收口为真实可用链路，并修复走读发现的 P0/P1 工程与安全缺口：
GET 读副作用、feature flag fail-open、sandbox timeout 无效、audit 并发分叉、Skill 非 signed-only、
DSR 删除矩阵不完整、tenant portrait other 桶泄漏、request-id 未进审计、CORS 缺 PUT/PATCH、版本号文档漂移。

## P0 修复清单

1. **消除 GET 读副作用**：`GET /v1/profile/{user_id}` 改为只读缓存（不再 version+1/commit，audit action=`profile.read`）；新增 `POST /v1/profile/{user_id}/rebuild` 显式重建（traits + version+1）。`GET /v1/narratives` 不再读时生成写库，只读已有 `DailyNarrative`；支持 `from`/`to` 批量查询（ordered narratives + data coverage + missing dates），`date` 单日保持兼容。派生特征入库时（写路径）才构建叙事。
2. **查询范围化**：`build_daily_narrative` / `rebuild_profile` / `audit_day` / `gap_finder._day_features` 改为 `window_start >= start AND window_start < end` 时间范围查询，不再全量拉取后 Python 过滤；新增 `(tenant_id, user_id, window_start)` 复合索引。
3. **Skill signed-only 下发**：`DELIVERABLE_SKILL_STATUSES=("signed",)`，`reviewed` 不再下发普通用户（含单条详情）。
4. **Skill 完成上报**：新增 `POST /v1/skills/completions`（body `{event_id,user_id,skill_id,status,duration_seconds,client_time}`），tenant+event_id 幂等，校验 skill 归属与 signed 状态，audit `skill.completion`，返回 `{id, idempotent_replay}`。
5. **Feature flag fail-closed**：`passive_sensing_enabled`/`sandbox_enabled` 在未知 key、租户不存在、字段缺失时一律 **false**；`skills_delivery_enabled` 可默认 true；`require_feature_flag` 对不存在租户放行交由下游 404（不泄露 flag 状态）。
6. **DSR 分类删除矩阵**：`POST /v1/data-subject-requests/{id}/complete` 的 delete 分支按矩阵删除 derived_features / daily_narratives / user_profiles / risk_signals / consents / checkins / journal_entries / questionnaire_results / practice_completions / emergency_contacts / skills / tools / sandbox_runs；保留 audit_events / escalations（危机处置记录法定留存，escalation.user_id 去标识为 hash）；返回 `per_category` 删除计数摘要。删除幂等（重复 complete 不报错）。
7. **沙箱真超时终止**：`SandboxRunner.execute` 改用独立子进程执行造工具回路（`app/services/sandbox/worker.py`），父进程 `join(timeout)` 超时后 `terminate`/`kill` 确保真终止；worker 内用独立 DB session，并用 `resource.setrlimit` 设置 CPU/内存配额（墙钟 + 地址空间上限）。in-memory SQLite（测试环境）回退线程执行。
8. **沙箱执行期并发配额**：路由并发检查从内存计数器改为基于 `SandboxRun` 表查询（`status=="running"` 且属于该租户计数 ≥ 上限 → 429），跨实例生效；每小时速率限制基于 `created_at` 计数。
9. **审计 tenant 串行化 + request-id 自动注入**：`append_audit` 的 read-head→hash→insert 在 PostgreSQL 下经 `pg_advisory_xact_lock(hashtext(tenant_id))` 串行化，SQLite 回退 per-tenant `threading.Lock`；`occurred_at` 保证 per-tenant 严格递增（并发下不产生 previous_hash 分叉）。`request_id` 参数可选，缺省从 contextvar 读取（HTTP 中间件写入），保证响应头与审计列一致。
10. **CORS / request-id 基础设施**：`allow_methods=["GET","POST","PUT","PATCH","DELETE","OPTIONS"]`；`request.state.request_id` + contextvar 注入。

## P1 修复清单

1. **tenant portrait other 桶保护**：cohort 总人数 <5 → 敏感维度（mood_distribution/observation_stats）suppressed（返回 `suppression` 标记）；individual bucket <5 合并；merged other <5 不输出；min/max 在总人数 <5 时隐藏。
2. **sources_present 契约**：`DerivedFeatureIn` 新增可选 `sources_present: list[Literal[...]]`（保留 `extra="forbid"`），落库 `derived_features.sources_present`，供 gap_finder 端侧覆盖度判断。
3. **治理字段**：`Skill.signed_by / signed_at`、`DailyNarrative.last_rebuilt_at`、`UserProfile.rebuilt_at` 可空列（迁移 0003）。
4. **版本统一**：`main.py` / `/health` / `/console` / `pyproject.toml` / `README` / `DELIVERY_MANIFEST` / `docs/openapi.json` 统一 0.6.0；README 完成度按 implemented/integrated/tested/externally validated/production-ready 五档区分。

## 测试计数

- 后端 pytest：**874 passed**（基线 825 → 874，净增 49；含新增 6 个后端测试文件 + QA 独立验收 `test_qa_v06_spotcheck.py` 12 项 + 既有文件契约更新）。

## PRD 契约点收口（QA 第 1 轮修复）

- **契约点 1**：`POST /v1/features/ingest` 完全移除被动 RED 危机链路（evaluate_passive 不再被生产路由调用；不创建 RiskSignal/Escalation，escalation_id 恒为 None）。
- **契约点 2**：移除后端 mood_hint/recent_mood_hint 情绪语义（叙事/画像不再输出情绪标签；DailyNarrative.mood_hint 列置空兼容；tenant_portrait mood_distribution 恒空 + suppressed；gap_finder 移除"持续低落"情绪推断规则）。
- **契约点 4**：SkillOut 增加执行契约字段 action_type（白名单）/estimated_duration/completion_schema/safety_constraints；skill_induct 填充安全默认值；白名单外不下发。
- **契约点 5**：Skill 治理字段 policy_version/review_evidence/revision/supersedes_skill_id；转入 signed 强制校验（缺 policy_version/review_evidence → 422）；内容变化经新版本 draft 结构保证重签。
- **契约点 6**：DSR delete 按矩阵保留 consents（同意证据链）与 risk_signals（危机处置 append-only），per_category 返回 retain + retained_reason。
- 新增测试文件：`test_dsr_matrix.py`、`test_skill_signed_only.py`、`test_feature_flags_fail_closed.py`、`test_audit_concurrency.py`、`test_sandbox_isolation.py`、`test_tenant_portrait_suppression.py`。
- 修改测试：`test_passive_sensing.py`（GET 无副作用 / POST rebuild / bulk narratives / sources_present）、`test_skill_delivery.py`（signed-only）、`test_e2e_sandbox.py`、`test_sandbox_budget.py`、`test_immutability_v03.py`（head=0003）、`test_tenant_portrait.py`（suppression 字段 / other<5 丢弃）、`test_e2e_privacy.py`（schema 字段集合含 sources_present）。

## 兼容性和迁移

- 新增 Alembic revision `20260731_0003_v06_contract_hardening`（down_revision=`20260731_0002`）：建 `(tenant_id,user_id,window_start)` 索引；新增 `daily_narratives.last_rebuilt_at`、`user_profiles.rebuilt_at`、`skills.signed_by/signed_at`、`derived_features.sources_present` 可空列；新增 `skill_completions` 表。`downgrade` 反向。全部为增量/可空，旧代码可继续运行。
- Android Room 迁移（v3→v4 移除 sensor_samples）见 T02/T05 迭代产物，不在本后端清单内。

## 遗留外部门禁

- Android SDK 联网全量构建 / APK/AAB 签名 / 真机矩阵（本机无 JDK，无法本地执行）。
- 机构 IAM/SSO/MFA、值班排班、真实通知与第二升级联系人。
- 法务、临床、隐私、伦理、网络安全正式审批。
- KMS/HSM、生产 PostgreSQL、备份恢复与不可篡改日志存储。
- 独立渗透测试、外部红队、值班演练、真实用户招募与机构试点。

## 已知限制与待明确事项

- in-memory SQLite 测试环境的沙箱回退为线程执行（无法真终止）；生产 PostgreSQL 走子进程隔离 + 真超时终止。
- 审计事件（audit_events）依法永久保留且不修改 actor_id（修改会破坏哈希链）；escalation 已去标识。
- `recent_mood_hint` 情绪语义字段的彻底移除属契约点 2（UI/趋势）范围，本迭代后端保留字段以兼容既有工作台/沙箱逻辑，另行迭代处理。
- 沙箱 worker 的 network deny-by-default 通过"不发起任何网络调用"实现（无网络代码）；容器化/独立文件系统隔离留待后续。
