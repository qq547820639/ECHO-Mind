# ERA 32 R05 — v0.11.0 Release Candidate Closure（Batch H 收官）

> 2026-08-15。§69/§70 执行：v0.10.0 后 ERA 32 的 Personal Intelligence 质量升级
> （4 个 P1/TRUST 级答案缺陷修复 + 治理减法）构成明显体验升级 → 切 **v0.11.0** 并全量 Closure。

## 1. 版本决策（§70）

- v0.11.0（非 0.10.1）：本轮含重要体验与 Personal Intelligence 升级——q032 月间规律度答对、
  z 距离泄漏退役、无变化结论可验证、上下文 14 天活跃窗口（旧上下文不再被当「这几天」）、
  AI 路径记忆人话化。versionCode 7 → 8。
- 同步面 8 处：version_source.json / backend APP_VERSION / backend pyproject /
  Android versionName+versionCode / BuildInfoTest / README / docs/current / RELEASE_NOTES_v0.11.0.md
  （backend test_version_consistency 7 passed + 1 skipped 实测通过）。

## 2. Release Closure 全链路（本地实测）

| 门 | 结果 |
|---|---|
| 全量验证 | backend 1077 passed + 1 skipped + ruff 0 + mypy strict 0；Android testDebugUnitTest 全绿 + lintDebug + detekt + assembleDebug |
| Release 构建 | assembleRelease（-PECHO_API_BASE_URL 显式注入 + **-PECHO_GIT_COMMIT=88db3b9c0d93… 全 40 位钉定**——test_apk_embeds_provenance_commit 要求完整 hash 进 dex） |
| 签名 | 本地测试密钥（/tmp 生成、不入库、验证后删除）apksigner v2,v3 |
| SOURCE_MANIFEST | 1083 文件重生成，verify PASS |
| 确定性归档 | zip + tar.gz 构建 + 双向验证 PASS（NFC / UTF-8 / required / 双向清单） |
| SBOM | 80 declared packages（uv.lock 全闭包；**必须用 backend/.venv Python 3.12 生成——系统 3.9 无 tomllib 会静默回退 pyproject 分支只剩 45 条**，本教训入册） |
| provenance | release_type=release；git_commit=88db3b9 与 APK 内嵌 commit 绑定；signed v2,v3；source_tree_sha256 绑定 |
| artifact manifest | 9 条目（DAG 末端） |
| final package | `releases/ECHO_Mind_v0.11.0.release.zip`（10 文件）+ §18 终态门禁 PASS（provenance binding + source archives verified） |
| release set 测试 | **test_release_set 6/6 + test_source_archive 10/10 = 16/16 PASS** |

## 3. 提交结构（clean-room 顺序）

1. `c69cb9a` feat: v0.11.0 版本收口（8 处同步 + Release Notes + manifest 重生成）；
2. `88db3b9` chore: v0.11.0 release closure artifacts（SOURCE_MANIFEST 1083 / DELIVERY / SBOM 80）；
3. 本提交：BUILD_PROVENANCE + RELEASE_ARTIFACT_MANIFEST（git_commit=88db3b9）+ RELEASE_BASELINE
   锚点更新（LAST_RELEASE_BASELINE=88db3b9）+ STATUS/docs 收口 + SOURCE_MANIFEST 重生成。

## 4. 纪律备注（本轮教训）

1. **SBOM 生成环境**：`generate_sbom.py` 在 Python < 3.11 无 tomllib 时静默回退 pyproject 正则分支
   （80 → 45 条），必须用 backend/.venv（3.12）生成；已在本轮实测并锁定正确产物。
2. **APK commit 钉定**：`-PECHO_GIT_COMMIT` 必须传全 40 位 hash（短 hash 会令
   `test_apk_embeds_provenance_commit` 失败——dex 内只含短串）。
3. **closure 后文档更新**：必须同步重生成 SOURCE_MANIFEST（发布包内清单为 closure 时点快照；
   仓库清单以 git 受控文件集为唯一事实源）——已写入 `docs/RELEASE_BASELINE.md` §3 纪律 5。

## 5. 外部待办（不可代码替代，如实移交）

- 真机 smoke / Wallpaper 电池采集（DEVICE_CHECKLIST + collect_wallpaper_metrics.sh 就绪）；
- 生产签名与设备矩阵（运营签名环境）；
- 30 天 dogfood（DOGFOOD_PROTOCOL 就绪）；
- Affective 契约冻结不变（affectiveState 恒 null）。
