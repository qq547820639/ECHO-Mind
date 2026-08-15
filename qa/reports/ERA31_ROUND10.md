# ERA 31 Round 10 报告（BATCH 8 收官：v0.10.0 Release Closure）

> 日期：2026-08-15。ERA 31 全部 8 个 Batch 至此闭环。

## Release Closure 全链路（全部在本地环境实测）

| 门 | 结果 |
|---|---|
| 版本收口 | v0.9.0 → **v0.10.0**（versionCode 7）；版本单一事实源 6 处同步 + 一致性门禁 6/6 |
| LOCAL PREFLIGHT | **PASSED**：backend pytest 1077（1076 passed + 1 skipped）/ ruff / mypy / openapi / alembic + Android testDebugUnitTest（1004 全绿）+ assembleDebug + lintDebug |
| Release 构建 | assembleRelease（-PECHO_API_BASE_URL 显式注入 + **-PECHO_GIT_COMMIT=6e84086 钉定**——clean-room 规则：APK 内嵌 commit == provenance.git_commit） |
| 签名 | apksigner v2,v3（本地测试密钥；生产签名由运营签名环境执行，provenance 如实记录） |
| SOURCE_MANIFEST | 1019 个 git 受控源文件（v0.9 时代的 634 清单被正确重生成——**不带旧 v0.9 proof 发布新代码**） |
| 确定性归档 | zip + tar.gz 构建 + 双向验证 PASS（NFC/UTF-8 标志/required/双向清单） |
| SBOM | 80 declared packages |
| provenance | release_type=release（git_dirty=false 语义：生成元数据不计入）+ signed v2,v3 + root APK 绑定 |
| artifact manifest | DAG 末端 9 条目（无循环） |
| final release package | `releases/ECHO_Mind_v0.10.0.release.zip`（10 文件）+ §18 终态门禁 PASS（provenance binding + source archives） |
| test_release_set | **6/6 PASS**（含 APK 内嵌 commit == provenance.git_commit 绑定断言） |

## 过程中的真实工程修复（如实记录）

1. Release 构建此前缺 `-PECHO_API_BASE_URL` 显式注入 → 按 CI 同构方式显式注入。
2. v0.9 时代的 SOURCE_MANIFEST（634）阻塞链条 → 先重生成（1019）再走链——即 §45「不带旧 proof 发布」的落地。
3. provenance 三要素锚定（release_type=release / git_dirty=false / APK 内嵌 commit 相等）需要
   clean-room 顺序：feat（版本收口）→ chore（closure artifacts）→ 在 artifact commit 上钉定构建 APK →
   生成 provenance → 最终包验证。中间一次 stash 冲突（RELEASE_ARTIFACT_MANIFEST 双写）已恢复并走对顺序。
4. 本地无签名密钥 → 生成 /tmp 测试密钥（不入库）签名，provenance 如实记录 signed v2,v3；
   生产签名留运营环境（与 CI「secrets 存在时签名」同构）。

## 文档

- `docs/release/RELEASE_BASELINE.md`：LAST_RELEASE_BASELINE 更新为 6e84086（v0.10.0）。
- `RELEASE_NOTES_v0.10.0.md`：产品变化 / 工程与质量 / 已知边界。
- ERA 31 全部 Batch 1-8 完成；dogfood/真机/生产签名等外部门执行件就绪（协议/清单/脚本）。

## 最终状态

- Android 1004 全绿；backend 1077；release 链全绿；仓库 git 状态收敛。
- Affective 保持冻结（ERA 30 前置：真实 dogfood 数据回流后才评估）。
