# 仓库历史瘦身 Runbook（P0-2 历史债）

> 状态：**未执行**（有意为之）。本文件记录为什么不做、以及要做时怎么做。
> 登记日期：2026-08-28　｜　负责人角色：DevOps/Release
> 关联：P0-2（`.git` 1.0 GiB / `qa/visual-review` 166MB 受控入库）

---

## 1. 现状与根因

| 项 | 实测（2026-08-28） |
|---|---|
| `.git` 体积 | **1,059 MB** |
| 受控文件 | 1,616 个 / **180.2 MB** |
| 其中 `qa/visual-review` | **166.1 MB**（约 397 个 PNG/HTML，单文件 0.2–1.2 MB，历史反复重写） |
| 其余源码合计 | 14.1 MB（android 8.9 / docs 1.7 / backend 1.2 / releases 1.1 / 其他 1.2） |
| 最大单文件 | 1.21 MB（`qa/visual-review/rendered/sheets/motion_APP_PROFILE_E_PROJECT_CRUNCH.png`） |

结论：体积几乎全部来自视觉评审画廊的历史版本堆积；**没有单个超大文件**，是"反复重写的中等二进制"问题。

## 2. 本次交付做了什么（已生效）

1. **预防控制**：`scripts/check_repo_bloat.py` + `source-integrity` CI 作业。
   - 单文件 > 2 MB 失败；受控总量 > 250 MB 失败；`.apk/.aab/.rpk/.zip/.mp4...` 入库即失败；
   - `.git` > 1 GiB 仅告警（历史债，不阻塞主干）。
   → 仓库**不会再变胖**；新增超限在 PR 阶段即红灯。
2. **工作区瘦身**：34 个历史本地调试 APK（213 MB）从仓库根收拢到
   `releases/local-apk-archive/`（已 gitignore），根只保留 provenance 绑定的
   `ECHO_Mind_v0.11.0.apk` / `.idsig`，消除"发布归档夹带垃圾"的风险面。
3. **`.gitignore` 缺口**：显式纳入 `.mypy_cache/` `.ruff_cache/`；`.trae/`
   规则与"LEDGER.md 实际受控"的矛盾用逐层 re-include 修好（规则与事实一致）。

## 3. 为什么没有执行 `git filter-repo`（决策与依据）

`git filter-repo` / `git filter-branch` 会**重写全部 commit SHA**，直接后果：

| 受影响锚点 | 位置 | 后果 |
|---|---|---|
| Release Baseline 提交锚 | `docs/RELEASE_BASELINE.md`（`88db3b9` / `LAST_RELEASE_BASELINE`） | 失效，发布可追溯性断裂 |
| Build Provenance | `BUILD_PROVENANCE.json.git_commit`（`45baef29…`） | 与真实 commit 脱钩 |
| 源完整性 | `SOURCE_MANIFEST.sha256` + `RELEASE_ARTIFACT_MANIFEST.sha256` | 交付包自校验链断裂 |
| 变更历史 | `docs/CHANGELOG/` 277 轮中大量 SHA 引用 | 大面积"悬空引用" |
| 协作 | 所有 fork / 本地克隆 / 未合 PR | 需 force-push + 全员重新克隆 |

**裁决**：不可逆、影响面跨越交付契约与全员协作，且收益（clone 从 GB 级回到百 MB 级）
不阻塞 pilot 候选评审——**不在无人值守的自主交付中执行**。改为：先立预防闸门 +
完备 runbook，由团队在协调窗口内一次性执行。

## 4. 执行步骤（需要团队协调窗口时按此执行）

前置条件：
- [ ] 全体成员已推送/合入在途工作，且知晓"执行后必须重新克隆"；
- [ ] 已确认 GitHub 上无未合 PR 依赖旧 SHA；
- [ ] 已选定维护窗口并通知（force-push 会影响所有人）。

```bash
# 0) 备份（两份：本地裸镜像 + 远端归档标签）
git clone --mirror git@github.com:qq547820639/ECHO-Mind.git /tmp/ECHO-Mind.mirror.git
git -C /tmp/ECHO-Mind.mirror.git bundle create /tmp/ECHO-Mind-pre-slim.bundle --all
git tag archive/pre-slim-$(date +%Y%m%d) main && git push origin --tags

# 1) 分析（ dry-run，不改动任何东西）
git filter-repo --analyze                     # 产出 .git/filter-repo/analysis/
#    重点看 blob-shas-and-paths.txt / path-all-sizes.txt，确认待清理路径

# 2) 把视觉评审资产剥离出库（保留工作树内容，转为不入库）
#    方案 A（推荐）：Git LFS —— 保留可追溯性，仓库内只留指针
git lfs install
git lfs track "qa/visual-review/**/*.png" "qa/visual-review/**/*.mp4"
git add .gitattributes qa/visual-review && git commit -m "chore: move visual review assets to LFS"
git filter-repo --force \
  --path-glob 'qa/visual-review/**/*.png' \
  --path-glob 'qa/visual-review/**/*.mp4' \
  --blob-callback 'return None'   # 或用 --strip-blobs-bigger-than 1M

#    方案 B：彻底移出历史（资产改由对象存储 + index.html 引用）
git filter-repo --path qa/visual-review --invert-paths --force

# 3) 验证
git count-objects -vH                          # size-pack 应显著下降
python3 scripts/check_repo_bloat.py            # 应无 .git 告警
git log --oneline -3                           # SHA 已变，确认历史语义完整
backend/.venv/bin/python -m pytest -q -C backend   # 全绿

# 4) 重新锚定（必做，否则交付链断裂）
python3 scripts/generate_provenance.py --require-clean
python3 scripts/update_release_metadata.py
#    更新 docs/RELEASE_BASELINE.md 的 commit 锚；
#    在 CHANGELOG 追加一条说明：历史已重写，旧 SHA 仅供归档参考。

# 5) 全员切换
git push origin --force-with-lease --all
git push origin --force --tags
#    通知所有成员：rm -rf <repo> && git clone git@github.com:qq547820639/ECHO-Mind.git
```

## 5. 回滚

- 执行前：`git clone /tmp/ECHO-Mind-pre-slim.bundle` 可完整恢复；
- 执行后已推送：从归档标签 `archive/pre-slim-YYYYMMDD` 或本地镜像
  `/tmp/ECHO-Mind.mirror.git` 重新 force-push 回旧历史。

## 6. 验收标准

- [ ] `git count-objects -vH` 的 `size-pack` < 300 MB；
- [ ] `python3 scripts/check_repo_bloat.py` 无 WARN；
- [ ] `python3 scripts/verify_source_manifest.py` 通过（新 SHA 已重新锚定）；
- [ ] `docs/RELEASE_BASELINE.md` 与 `BUILD_PROVENANCE.json` 指向新 SHA 且相互一致；
- [ ] backend pytest 全绿；五套 CI 全绿。
