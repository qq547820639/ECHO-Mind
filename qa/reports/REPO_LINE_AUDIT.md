# REPO_LINE_AUDIT — 全仓逐行审计报告

> 审计起点 HEAD：`d3c87fa`（main）· 审计时间：2026-08-18
> 状态：**审计与修复完成**——P0×2 / P1×22 全部修复 + 33 个新回归测试锁定，随 `c598143` 推送 origin/main（修复证据见同目录 `REPO_LINE_AUDIT_FIXES.md`）；P2×61 / P3×105 为记录待办（明细见 findings/T2–T8），真机视觉/功耗门仍属 BLOCKED_EXTERNAL_DEVICE
> 纪律：每条结论可追溯到 `文件:行` 或工具输出；详细证据在各任务发现文件（`.trae/specs/audit-repo-line-by-line/findings/T2–T8`）。

## 1. 审计框架

（七维度 D1–D7 检查单与 P0–P3 分级定义见本报告 git 历史首版；摘要：D1 正确性 / D2 语义一致性 / D3 安全隐私 / D4 死代码冗余 / D5 契约符合 / D6 性能陷阱 / D7 测试缺口；P0=崩溃·数据丢失·隐私安全，P1=正确性·契约违背，P2=漂移·死代码·重复事实源，P3=打磨。）

## 2. 覆盖核对表（实测）

| 任务 | 范围 | 文件数 | 已审计 | 未覆盖 |
|---|---|---|---|---|
| T2 视觉链 | core:visual(20) + presencevisual(7) | 27 | 27 | **0** |
| T3 感知身份链 | presence(9)+observation(17)+core:model(7)/ports(3)/security(8) | 44 | 44 | **0** |
| T4 app | android/app/src/main | 110 | 110 | **0** |
| T5 领域特性 | journey(15)+intelligence(18)+memory(6)+actions(2)+wearable(19)+qa(14) | 74 | 74 | **0** |
| T6 backend | backend/app(70) + alembic(20) | 90 | 90 | **0** |
| T7 边界面 | Vela JS(10)+scripts(32)+workflows(5)+content-packs(5)+answatch(5)+docs(6) | 63 | 63 | **0** |
| T8 测试质量 | Android 188 + backend 49 测试文件 | 237 | 237（断言质量） | — |
| **合计** | 生产 396 文件 / 53,821 LOC + 测试 237 文件 | | | **未覆盖生产文件 = 0** |

## 3. 发现汇总

**总计：P0 × 2 · P1 × 22 · P2 × 61 · P3 × 105**（P2/P3 明细见各任务发现文件）

### 3.1 P0（隐私合规 / 安全——本轮必修）

| # | 位置 | 描述 | 处置 |
|---|---|---|---|
| P0-1 | `backend/app/api/data_rights.py:51-63` | **DSR delete 矩阵缺失 v0.7 Portrait Core 全部派生表**（daily_behavior_aggregates / personal_baselines / daily_portraits / materialization_state / portrait_feedback / skill_completions）：用户行使删除权后行为画像数据残留且可继续读取 | 修复：补全删除矩阵 + 回归测试 |
| P0-2 | `backend/app/services/activation.py` + `api/onboarding.py:168-207` | **激活码防爆破在拒绝路径完全失效**：`_reject`/`_record_attempt` 只 flush 不 commit，路由 403/404 直接 raise → `db.close()` 回滚；attempt 计数/15min 窗口永不落库，rate limit 与 max_attempts 永不触发 | 修复：拒绝路径先持久化 attempt（commit）再返回 + API 级爆破回归测试 |

### 3.2 P1（22 项——本轮必修或 BLOCKED）

**T2 视觉链（3）**
| # | 位置 | 描述 |
|---|---|---|
| P1-1 | `EchoRendererFacade.kt:246` | AGSL 会话路径 exposure 用未裁剪 `request.genome.luminance`（Compose 路径用 crop 后 spec）→ Wallpaper/Dream AGSL 违反 surface 亮度上限契约、双后端不对齐 |
| P1-2 | `EchoVisualClock.kt` + `MotionEvaluator.kt:42-53` + `EchoOrganismRenderer.kt:99` | boot-global 秒数全程 Float 且相位不取模：uptime ≈24 天起呼吸相位可见阶跃、1–2 年帧间乱跳；Correction 0.9s 脉冲被大 Float 相减量化失效 |
| P1-3 | `EchoSceneCompiler.kt:76` | 呼吸周期映射量纲断裂：输入语义 3.6–6.0 vs fixture 填 6.8–10.8 → Lab/fixture 链恒饱和 10.2s，呼吸维度失效；EchoMotionSpec KDoc 与实现矛盾 |

**T3 感知身份链（1）**
| P1-4 | `EchoIdentity.kt:353` | buildDailyComposition 硬编码 `maturityOpenness(echoMaturity(0))`（≡SEED 常数）→ 日构图链启用后 maturity 成长开放度被钳制，Day-0 与 Day-180 相同 |

**T4 app（3）**
| P1-5 | `PortraitRepository.rebuildTodayPortrait` | 唯一未包 IO 的网络方法：NetworkOnMainThreadException 被吞 → 订阅模式「重新生成画像」永久静默失效 |
| P1-6 | `PassiveSensingService` startMic 分支 | 服务未运行时 startForegroundService 后直接 stopSelf 且从未 startForeground → ForegroundServiceDidNotStartInTimeException 崩溃 |
| P1-7 | `EveningReminderWorker.doWork` | 首行 scheduleNext(REPLACE) 自取消竞态：REPLACE 取消正在运行的自身 → 每晚提醒可能静默丢失 |

**T5 领域特性（2）**
| P1-8 | `JourneyCanonical.kt:160-213` | v1 解码按 v2 布局读段 → 必然异常被 runCatching 吞成恒 null；KDoc 宣称的 v1 向后兼容未实现 |
| P1-9 | `intelligence/ProviderConfigValidator.kt:58-65` | isPrivateLanUrl 前缀匹配可被 `http://10.evil.com` / `http://localhost.attacker.com` 绕过 → Bearer key 明文出网，违背「非本机必须 HTTPS」契约 |

**T6 backend（6）**
| P1-10 | `portraits.py` / `profiles.py` / `skills.py` | 4+ 写端点缺 `require_write_role`：只读角色（auditor 等）可执行 rebuild/feedback/completions 写路径 |
| P1-11 | 同上载体 | rebuild/feedback 无 passive_sensing consent 门禁（撤同意后仍可重算画像），缺订阅 402 门 |
| P1-12 | `escalations.py:140-144,179` | cursor 分页 str 与 DateTime 列比较：SQLite 正常、**PostgreSQL 生产第二页起 500** |
| P1-13 | `messages.py:24-30` | `GET /v1/me/messages` 缺 require_active_subscription（docstring 明确云端分析消息应被冻结） |
| P1-14 | `features.py:65-92` | DailyNarrative 用 UTC 日界线，aggregate/portrait 用用户本地日——同一用户两套日界线错位 |
| P1-15 | `data_rights.py:214-227` | DSR export 请求不导出任何数据仅标记完成（访问权形同虚设） |

**T7 边界面（4）**
| P1-16 | `SOURCE_MANIFEST.sha256` | 当前 HEAD 必红：committed 1254 vs 重算 1308（实跑 verify exit 1）→ source-integrity CI 门失效 |
| P1-17 | 根目录发布元数据三件套 | DELIVERY/SBOM/SOURCE_MANIFEST 三代混杂非同 run 生成；artifact manifest 9 条中 3 条哈希与实件不符 → 过不了自家 §18 终态门 |
| P1-18 | `docs/RELEASE_BASELINE.md` | 假宣称：provenance.git_commit≠88db3b9（实际 c52a28d9）、「SBOM 80/清单 1083」vs 现物 45/1254 |
| P1-19 | `wearable/xiaomi-vela` accel_summary | 5–15s 观察窗口机制未实现（WINDOW_MS 死常量，前台期间零 observation 上报）→ ECHO_WRIST_CONTRACT §8 违背 |

**T8 测试质量（3）**
| P1-20 | `E2EFlowTest.kt` | 「契约对齐」测试自证：payload/隐私字段集合全为测试内硬编码，从不触达 `SensingRepository.saveDerivedFeature` 真实映射 |
| P1-21 | `VisualRegressionGoldenTest.kt` | 黄金集缺条目静默通过（`expected != null` 门）：完整性未锁定 |
| P1-22 | `HardeningV061Test.kt` | 假迁移测试：in-memory 新库建最新 schema，`MIGRATION_5_6.migrate` 从未执行 |

### 3.3 P2 摘要（61 项，待办清单——详见 findings/T2–T8）

高价值簇：拓扑缓存 clear-on-overflow 破坏 §32（多 identity 逐帧重建）+ 确定性随机盐空间大范围冲突（T2）；LifeSeasonTracker 候选日不计入 confidence / DailyCompositionGate 不校验 forDate / SCREEN_TIMING 名实不符（T3）；app 拆分残留死代码群 + 壁纸偏好键复制式同源（T4）；journey 旧渲染链 10+ 函数死代码 + earliestDate 传参链 fallback 断 + ContextExceptions roundtrip 断裂（T5）；portraits 0.25 字面量重复 / 两个索引缺口（T6）；SBOM 静默回退 pyproject / 门禁脚本只扫 app 单 module / Band10 模拟器镜像失真（T7）；maturityCalendarDays 与 portraitMaturityProxy 无测试 / AiNarrativeService 零覆盖（T8）。

## 4. 真值结论（可追溯）

- **确定性纪律**：core:visual 逐文件核对无 frame-random（§15）；黄金集更新意图充分（REGEN 机制+生产管线 computeFrame）。
- **上轮修复保持**：Lab→XYZ 分母/chroma 量纲/maturity 单钟全链无回归残留（T2/T3 交叉核验）。
- **无跨租户 IDOR**：backend 所有 db.get 后校验 tenant_id、列表全带租户过滤、密文 AAD 绑定租户（T6 逐路由）。
- **无 SQL 注入面 / secret fail-closed / 审计链 PG 触发器强制**（T6）；65/65 workflow action pin 40 位 SHA、无 pull_request_target（T7）。
- **HKDF/密钥链 RFC 5869 合规**（core:security 逐行，T3）。
- **壁纸/梦境隐私面、DEBUG 双门、三世界 IA、TalkBack 语义**核验通过（T4）。
- **中性词契约**无违背；affectiveState 恒 null 冻结门保持（T3/T5）。
