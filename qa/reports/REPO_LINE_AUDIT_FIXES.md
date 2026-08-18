# REPO_LINE_AUDIT_FIXES — P0/P1 修复证据

> 对应审计报告：`REPO_LINE_AUDIT.md`（P0×2 / P1×22）
> 修复完成时间：2026-08-18 · 全部最小化修复 + 回归测试锁定（P2×61 / P3×105 记录待办，不在本轮）

## P0 修复（2/2）

| # | 修复 | 测试 |
|---|---|---|
| P0-1 | `backend/app/api/data_rights.py`：DSR_DELETE_MODELS 补入 6 张 v0.7 派生表（aggregates/baselines/portraits → materialization_state/feedback；skill_completions 按 FK 序前置） | `test_p0_1_dsr_delete_removes_portrait_core_derived_tables`（6 表行数=0 + per_category 计数） |
| P0-2 | `backend/app/api/onboarding.py:181-198`：拒绝路径（403/404）raise 前 `db.commit()` 持久化 attempt 行与计数（services 层「不 commit」契约保持） | `test_p0_2_rejected_redemptions_persist_attempts_across_requests`（TestClient 真实请求逐次断言 failure 递增 + max_attempts 生效）、`test_p0_2_unknown_code_404_persisted_as_attempt` |

## P1 修复（22/22：21 代码修复 + 1 项见「元数据再生」）

### 视觉链（T2）
- **P1-1** `EchoRendererFacade.kt`：computeFrame 返回 `SessionFrame(frame, spec)`，AGSL dispatch 的 exposure/halo 改用同一份 crop 后 spec（与 Compose 路径同源；Wallpaper 0.7/Dream 0.55/Lock 0.5 生效）。测试 `agslSessionExposureIsSurfaceCropped`。
- **P1-2** 时钟 Long 化：`MotionEvaluator` 新增 Long-nanos 入口（各周期先 `% periodNanos` 再 Float；速度折进有效周期——数学等价）；`EchoVisualSpec.clockNanos` + `SurfacePolicy.cropNanos`；`correctionPulseAgeNanos` Long 差值；EchoOrganismRenderer/Facade/EchoVisualClock 全链改造。测试：400 天 uptime 相邻 16ms 帧相位连续 + 大 t ≡ mod 小 t + 450ms 脉冲年龄 Long 精确（附 Float 反例锁定）。
- **P1-3** 呼吸量纲：保留 8.2–10.2s 呈现窗口与 3.6–6.0 输入域；`VisualLabFixtures` base() pulseRate 8.2→4.6、withKnobs 滑杆映射 3.6–5.88（Lab 呼吸维度真实生效）；KDoc 三处统一（EchoMotionSpec/EchoVisualParameters/EchoVisualGenome）。测试 `breathInputDomainMapsOntoPresentationWindow`（3.6→8.2 / 6.0→10.2 / 饱和 / 中点 4.6→9.033）。organism-quality PNG+metrics 已再生（意图=fixture 量纲修复；门 PASS 未调阈值）。

### 感知身份链（T3）
- **P1-4** `EchoIdentity.kt` buildDailyComposition 增加 `maturity` 参数（无默认值强制显式）：`coreOpenness = lerp(coreTopology, maturityOpenness(maturity), 0.3f)`；PresenceRepository/QaTimeline 传真实日历 maturity。测试 `dailyCompositionCoreOpennessFollowsMaturity`（SEED<MATURE + hasDaily 传导双断言）。
  - **黄金再生（意图声明）**：42 哈希更新——P1-4 开放度改真实 maturity（day≥3 帧变化）+ P1-2 时基 Long 化（canonical 12s 锚 Float 舍入路径变化）；PROFILE_F day0 哈希不变佐证漂移边界与意图吻合。KDoc 已注明。

### app（T4）
- **P1-5** `PortraitRepository.kt:359`：rebuildTodayPortrait 网络段包 `withContext(Dispatchers.IO)`。测试：`rebuildTodayPortraitLocalModeStaysOnLocalEngineWithoutThrowing` + 源码结构断言 `rebuildTodayPortraitNetworkSectionRunsOnIoDispatcher`。
- **P1-6** `PassiveSensingService.kt:102-111`：ACTION_START_MIC 未启动分支先 `startForegroundWithTypes` 再 stopForeground+stopSelf（FGS 5 秒契约）。测试 `startMicOnNotRunningServiceEntersForegroundBeforeStopSelf`（Robolectric，shadow 断言 stopSelf 前已 startForeground）。
- **P1-7** `EveningReminderWorker.kt:31-58`：scheduleNext(REPLACE) 从 doWork 首行移入 `finally`（先发提醒再排明晚，不再自取消）。测试：排程顺序源码断言 `eveningReminderSchedulesNextAfterReminderNotBefore`。

### 领域特性（T5）
- **P1-8** `JourneyCanonical.kt`：v1 真解码——以 git 历史 b9f3634 原始布局为证（v1|date|seed|maturity|params(12)@4..15|identity(8)@16..23|evidence@24|createdAt@25），`identityOffset = if (v2) 22 else 16`，v2 六增量字段 v1 默认 0f；KDoc 同步。新测试文件 `JourneyCanonicalCodecTest`（v1 共享字段/默认值/空 evidence/畸形 fail-closed/v2 roundtrip，5 用例）+ 既有 7 用例回归。
- **P1-9** `ProviderConfigValidator.kt:52-73`：isPrivateLanUrl 改 `java.net.URI` host 精确判定（localhost/127.0.0.1/::1 或严格字面 IPv4 ∈ RFC1918+link-local）；废弃 startsWith。新 `ProviderConfigValidatorTest`（8 正例 + 9 攻击负例含 10.evil.com/localhost.attacker.com + 端到端 3 用例）。

### backend（T6）
- **P1-10** 5 写端点补 `require_write_role`（portraits/profiles/skills 的 rebuild/feedback/completions；顺序对齐 write_role→ensure_user→subscription→consent）。测试：3 只读角色 × 5 端点参数化 403 + user 200。
- **P1-11** rebuild/feedback/profile-rebuild 补 passive_sensing consent 门（撤回→412）+ 订阅 402 门；skills/completions 补 402。测试：撤同意 412 / 订阅到期 402。
- **P1-12** `escalations.py:138-152`：cursor 时间部 fromisoformat 解析回 DateTime 同型比较（非法 cursor 422）。测试：7 条全量翻页不重不漏 + 非法 422。
- **P1-13** `/v1/me/messages` 挂 require_active_subscription。测试：到期 402 / NULL 到期 200。
- **P1-14** narrative 日期统一用户本地日（`build_daily_narrative(tz_name)` 用 local_day_window；GET 默认日本地日）。测试：UTC 20:00→上海次日 04:00 narrative.date==aggregate.local_date。
- **P1-15** DSR export 真实导出（删除矩阵同范围全列 JSON 安全形态，经既有 result_summary 存储/幂等重放；审计仅记类别计数）。测试：export 含 portraits/aggregates/baselines/feedback/completions/narratives + 幂等。

### 边界面（T7）
- **P1-19** `accel_summary.js`：WINDOW_MS(10s，合同 5–15s 区间) 前台定时 flush——onSample 检查窗口满即 finishWindow+开新窗，stop 仍 flush 余窗；start(opts) 支持传感器注入；并修复 echo/index.ux 调用模块级 start/stop 的断链（原未导出，consent 开启即 TypeError）。Node 测试 28/28（新增窗口 flush 时机 + 模块导出 2 用例）。
- **P1-16/17/18（发布元数据三件套）**：见下节「元数据再生」。

### 测试质量（T8）
- **P1-20** `E2EFlowTest` 5 个自证测试全部重写触达真实生产映射（saveDerivedFeature→outbox 密文解密 / SkillRepository.recordSkillCompletion / 真实 parseSkillResponse 解析产物 / 端到端 FeatureExtractor→Skill 展示隐私保持）；保留 10 个真实测试，转 Robolectric 共 15 用例。
- **P1-21** `VisualRegressionGoldenTest`：`expected==null` 计入 mismatch（缺黄金值=失败）。
- **P1-22** `HardeningV061Test` 假迁移测试改真迁移（v5 库 Callback(5)→直调 MIGRATION_5_6.migrate→PRAGMA 列名/列序全表断言+幂等重跑）。

## 元数据再生（P1-16/17/18）

在本轮全部代码/报告/STATUS 落定后的最终树上原子再生：SOURCE_MANIFEST.sha256（重算=committed，source-integrity 门恢复绿）、BUILD_PROVENANCE.json、sbom.spdx.json、RELEASE_ARTIFACT_MANIFEST；`docs/RELEASE_BASELINE.md` 假宣称改为真值（provenance 指向本轮再生 HEAD、SBOM/清单实际计数），并注明「审计轮再生」。再生证据（verify_source_manifest / verify_final_package 实跑输出）由主代理在提交前执行并记录于 STATUS。

## 验证汇总（修复后）

| 门 | 结果 |
|---|---|
| backend `uv run pytest -q` | **1099 passed + 1 skipped**（新增 15 项回归） |
| `:app:testDebugUnitTest` | **976 全绿** |
| `:core:visual` / `:feature:presence` | 56 / 25 全绿 |
| `:feature:presencevisual` / `:feature:qa` | 24 / 115 全绿 |
| `:feature:journey` / `:feature:intelligence` | 46 全绿（含 2 个新测试文件） |
| Vela `node tests/run.js` | **28/28** |
| 全量复验 | 见 STATUS「审计轮」记录（Task 11 统一执行） |
