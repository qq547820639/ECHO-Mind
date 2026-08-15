# RELEASE READINESS —— 内部证据 ↔ 外部发布门 移交包

> ERA 88（ADR-072）产出。本包汇总 ECHO Mind v0.9.0 的全部**内部证据**，
> 并把剩余**外部发布门**如实列出——外部门不能由代码生成替代，移交运营/法务/临床/安全主体执行。
> 数据来源：本文件提交当轮全门禁实测 + clean-clone 全 Gate。

## 1. 内部证据（全部实测 PASS）

| 证据 | 数值/状态 | 校验方式 |
|---|---|---|
| Android 单元测试 | **844 全绿** | `./gradlew testDebugUnitTest`（测试结果 XML 计数） |
| Android 静态/构建 | lintDebug（4 安全规则）/ detekt 27 规则 / assembleRelease | Gradle 门禁 |
| Backend 测试 | **1076 passed + 1 skipped** | `pytest -q`（junit 报告） |
| Backend 静态 | ruff 0 / mypy strict 0 / uv.lock 冻结 | 门禁实测 |
| 发布链 | SOURCE_MANIFEST（633 文件）→ 确定性归档 → SBOM → provenance → delivery → artifact manifest → release.zip | package_release.sh 全链 + clean-clone 复核 |
| APK↔源码绑定 | signed v2,v3；dex 内嵌 commit == provenance.git_commit | test_release_set（16/16 含绑定断言） |
| 分发完整性 | release-set + archive 16/16；SourceIntegrity 7/7；ArchitectureBoundary 9/9 | clean-clone 全 Gate |
| 契约一致性 | CONTRACT_COMPLIANCE 24 锚点机器校验（CI source-integrity） | contract_compliance_check.py |
| 工作流 pinning | 5 条 workflow、66 个 uses 全部 immutable SHA | verify_workflow_pins.py |
| 生成文档零漂移 | SOURCE_REALITY_REPORT / ANDROID_DEPENDENCY_GRAPH / openapi 重生成后与提交一致 | 本轮重生成 + git diff 清零后提交 |
| 性能防退化 | PERFORMANCE_BASELINES 13 行预算（JVM 段） | PerformanceBaselineTest |
| 设备段性能 | CI connected-test（API 34/36 emulator）承接；本环境不伪造 | android-ci |

## 2. 外部发布门（不可代码替代，移交责任主体）

| 门 | 责任主体 | 状态 |
|---|---|---|
| 真实设备构建/安装/回归（≥8 台目标设备） | 运营/设备负责人 | 未执行（外部门） |
| 责任矩阵签署 + 真实值班人员 | 试点机构 | 未执行（外部门） |
| 临床负责人签署 L0/危机规则/量表与内容 | 临床负责人 | 未执行（外部门） |
| 隐私政策/单独同意/PIPIA/数据保留/合同审查 | 法务/隐私负责人 | 草案在 pilot-pack（02/03）；未定稿（外部门） |
| 外部渗透测试（关闭严重/高危） | 安全测试主体 | 未执行（外部门）；内部已完成 KDF/密钥分离/TLS 私网/审计链审计 |
| 值班缺岗/宕机/断网/电话不可达演练 | 机构运营 | 演练脚本在 pilot-pack（05）；未执行（外部门） |
| 生产密钥/数据库/备份/日志/监控入批准域 | 机构运维 | 未执行（外部门） |
| 伦理审查与招募说明（如构成研究） | 伦理委员会 | 未执行（外部门） |

## 3. 冻结与阻塞项（保持不绕过）

- Affective Intelligence：AFFECTIVE_CONTRACT §8/§9/§10 人工评审门（affectiveState 恒 null，测试强制）。
- feature_vectors 留存裁剪：阻塞条件 1（不可逆删除真实用户数据），待人工确认。
- osv-scanner 本地首跑：GitHub release CDN 不可达；CI security-ci 已强制（外环境执行）。
- 状态声明：`pilot-candidate`——外部门未完成前不得标记生产上线（README 已声明）。
