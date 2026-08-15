# ECHO Mind — STATUS（当前状态唯一锚点）

> 本文件是**当前可交付 main**（git 受控状态）的唯一状态锚点，每轮自主演进结束更新；禁止虚假完成状态。
> - 历史轮次细节 → `docs/CHANGELOG/`
> - 发布锚点 → `docs/RELEASE_BASELINE.md`
> - Build Status 段由 `scripts/refresh_status_numbers.py` 自动生成，禁止手写数字。

## 1. Current Development HEAD

| 字段 | 值 |
|---|---|
| Product Era | ERA 32+ — Felt Product Reality / Real Product Validation（ERA 31 全部 Batch 1–8 完成，v0.10.0 后打磨轮 R11–R49 已合入） |
| 版本线 | v0.11.0（versionCode 8；ERA 32 Personal Intelligence 升级——closure 进行中） |
| 相对 Release Baseline | +41 commits（v0.10.0 Baseline 之后 R11–R49 + ERA 32 治理轮） |
| 状态 | `pilot-candidate`（外部发布门未完成前不得标记生产上线） |

## 2. Last Verified Release

| 字段 | 值 |
|---|---|
| 已发布版本 | v0.10.0（见 `docs/RELEASE_BASELINE.md` 完整锚点） |
| APK | `ECHO_Mind_v0.10.0.apk`（本地测试密钥签名 v2,v3；生产签名由运营环境执行） |
| Release 包 | `releases/ECHO_Mind_v0.10.0.release.zip` |

## 3. Build Status（自动生成）

<!-- AUTO:BUILD_STATUS:BEGIN -->

> 自动生成（`scripts/refresh_status_numbers.py`，git HEAD `f07fa88`，2026-08-15 20:56 UTC）；缺失实测产物处如实标注，禁止手写数字。

| 面 | 实测结果 |
|---|---|
| Android 单测（testDebugUnitTest） | **1038 全绿**（app 869 / feature:intelligence 32 / feature:presence 25 / feature:qa 112） |
| backend pytest | **1077 passed + 1 skipped**（全绿） |
| Production Kotlin | 166 |
| Test Kotlin | 123 |
| QA Kotlin（:feature:qa，非 Production Runtime） | 36 |
| Python | 68 |

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

## 5. Known Engineering Risks

- **Development HEAD 未做新一轮 Release Closure**：R11–R49 打磨轮在 v0.10.0 Baseline 之后，尚未全量重跑发布链
  （clean checkout / 全测试 / SBOM / provenance / final package）。发起条件与纪律见 `docs/RELEASE_BASELINE.md`。
- **外部发布门未执行**：真实设备回归 / 责任矩阵 / 临床签署 / 法务定稿 / 外部渗透 / 值班演练 / 生产域 /
  伦理审查——见 `docs/CHANGELOG/RELEASE_READINESS_v0.9.0.md`（移交包），不由代码生成替代。
- **PersonalAnswerEngine 复杂度**：626 行 / 16 回答族单 object，已审计——三层拆分暂不必要（触发条件入册），
  ERA 32 R02 答案复核轮修复 3 个真实缺陷（q032 答非所问 / z 距离泄漏 / 无变化结论不可验证）；
  见 `qa/reports/PERSONAL_ANSWER_ENGINE_AUDIT.md`。
- **QA mirror 漂移面**：QaPortraitMirror 是必要镜像（跨语言黄金门已锁）；QaHeadlineEngine 文案重复已消除
  （learningPhaseHeadline 单点）；结论见 `qa/reports/QA_MIRROR_AUDIT.md`。

## 6. Next Product Slice

1. **治理减法**（本轮）：架构/QA 冻结 + Source Reality 多模块修复 + 状态文档收敛 + 状态数字自动生成。✅
2. **真机验证**（Batch B）：fresh install / Time-to-ECHO / 授权自动推进 / Wallpaper lifecycle / 24h 运行 /
   电池采样——本环境无真机，保持协议与清单，可用设备立即执行。
3. **真实 Dogfood**（Batch D）：30 天真实使用 → 六类缺陷回流 → 修产品 → fixture 化 regression。
4. **Personal Reasoning / Correction Loop**（Batch C）✅ 完成——26 条 Core Set 四层复核（R02：q032 答非所问、
   z 距离泄漏、无变化结论不可验证）+ Context/Correction 深度走查（R03：上下文永不过期 P1 缺陷 +
   AI 路径内部格式泄漏，均已修）；PersonalAnswerEngine 保持单 object 不拆分（触发条件入册）。
5. **Journey / Memory**（Batch E/F）：以真实长期数据评估 Visual Memory River、Landmarks、consolidation。
6. **Delete Review**（Batch G）✅ 预检完成——删除 v0.7 遗留零消费函数（进度条/覆盖率文案），
   复查 Skills/Subscription/QA mirror/reports 均保留（判定见 `docs/CHANGELOG/ERA32_ROUND04_DELETE_REVIEW.md`）。
7. **Release Candidate**（Batch H）：真实体验明显升级后，全量重跑发布链并切版本（0.10.1 / 0.11.0 自决）。

## 7. Governance（冻结纪律）

- **架构冻结**：11 个 Gradle module 体系冻结（`:app` / `:core:model|ports|security` /
  `:feature:observation|presence|intelligence|memory|journey|actions|qa`）。不新增 module、大框架、抽象层、
  Contract 类型；只有真实 Dependency Violation 或直接阻碍用户体验/reasoning/性能/电池/安全/发布/可维护性时才调整。
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








