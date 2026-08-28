#!/usr/bin/env python3
"""仓库体积门禁（P0-2 预防控制，2026-08-28）。

背景：`.git` 已达 1.0 GiB，主因是 `qa/visual-review/` 的 166MB 渲染 PNG 受控入库且
历史反复变更。历史瘦身（git filter-repo）属**不可逆**操作，会重写全部 commit SHA，
从而使 docs/RELEASE_BASELINE.md、BUILD_PROVENANCE.json、SOURCE_MANIFEST 绑定与
CHANGELOG 里大量 SHA 引用失效——不在无人值守时执行，见
`docs/operations/REPO_HISTORY_SLIM_RUNBOOK.md`。

本脚本是**可立即生效的那半**：把"不再变胖"变成 CI 红灯。

检查项：
1. 单个受控文件大小 > --max-file-mb（默认 2MB）→ 失败；
2. 受控文件总体积 > --max-total-mb（默认 250MB）→ 失败；
3. 受控文件命中"生成物二进制"模式（apk/aab/rpk/png 截图目录外/视频/归档）→ 失败
   （qa/visual-review 为历史存量白名单，只允许减少不允许增加）；
4. `.git` 体积 > --max-git-mb（默认 1024MB）→ **告警不失败**（历史债，走 runbook）。

退出码：0 = 通过（可含告警）；1 = 门禁失败。
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# 历史存量白名单：这些目录下的受控二进制是既有资产，只做体积上限约束，不做新增禁止。
# 一旦执行 history slim + LFS 迁移，应把 qa/visual-review 移出白名单。
LEGACY_BINARY_DIRS = ("qa/visual-review",)

# 不该出现在版本控制里的生成物二进制（命中即失败，白名单除外）
FORBIDDEN_SUFFIXES = (".apk", ".aab", ".rpk", ".jar", ".aar", ".so", ".dylib",
                      ".zip", ".tar", ".gz", ".mp4", ".mov", ".webm", ".pdf")

# 历史交付物白名单：v0.2 试点候选包与实施手册是**有意受控**的交付证据（合计 <1MB），
# 与 .gitignore 中"历史 v0.2 releases/* 保持受控"的约定一致；新发布包一律不入库。
LEGACY_ALLOWED_PATHS = frozenset({
    "releases/ECHO_Mind_Android_PathA_PilotCandidate_v0.2.0.bundle",
    "releases/ECHO_Mind_Android_PathA_PilotCandidate_v0.2.0.tar",
    "releases/ECHO_Mind_手机App与小米手环实施手册_v1.0.docx",
})


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout
    return [f for f in out.split("\0") if f]


def dir_size(path: Path) -> int:
    total = 0
    for p in path.rglob("*"):
        if p.is_file() and not p.is_symlink():
            try:
                total += p.stat().st_size
            except OSError:
                continue
    return total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--max-file-mb", type=float, default=2.0)
    parser.add_argument("--max-total-mb", type=float, default=250.0)
    parser.add_argument("--max-git-mb", type=float, default=1024.0, help="仅告警")
    parser.add_argument("--top", type=int, default=10)
    args = parser.parse_args()

    files = tracked_files()
    rows: list[tuple[int, str]] = []
    missing: list[str] = []
    for rel in files:
        p = ROOT / rel
        if p.is_file():
            rows.append((p.stat().st_size, rel))
        else:
            missing.append(rel)
    rows.sort(reverse=True)
    total = sum(size for size, _ in rows)

    failures: list[str] = []
    warnings: list[str] = []

    for size, rel in rows:
        mb = size / 1048576
        if mb > args.max_file_mb and not rel.startswith(LEGACY_BINARY_DIRS):
            failures.append(f"单文件超限：{mb:.2f}MB > {args.max_file_mb}MB —— {rel}")
        if rel in LEGACY_ALLOWED_PATHS or rel.startswith(LEGACY_BINARY_DIRS):
            continue
        if rel.lower().endswith(FORBIDDEN_SUFFIXES):
            failures.append(f"受控文件包含生成物二进制：{rel}")
    if missing:
        failures.append(f"{len(missing)} 个受控文件在工作区缺失（如 {missing[:3]}）")

    total_mb = total / 1048576
    if total_mb > args.max_total_mb:
        failures.append(f"受控文件总体积超限：{total_mb:.1f}MB > {args.max_total_mb}MB")

    git_dir = ROOT / ".git"
    git_mb = dir_size(git_dir) / 1048576 if git_dir.exists() else 0.0
    if git_mb > args.max_git_mb:
        warnings.append(
            f".git 体积 {git_mb:.0f}MB 超过告警线 {args.max_git_mb:.0f}MB —— 历史债，"
            f"按 docs/operations/REPO_HISTORY_SLIM_RUNBOOK.md 排期（不可逆，需团队协调）"
        )

    legacy_mb = sum(s for s, r in rows if r.startswith(LEGACY_BINARY_DIRS)) / 1048576
    print(f"受控文件：{len(rows)} 个 / {total_mb:.1f}MB（其中历史视觉资产 {legacy_mb:.1f}MB）")
    print(f".git：{git_mb:.0f}MB")
    print(f"最大 {args.top} 个受控文件：")
    for size, rel in rows[: args.top]:
        print(f"  {size / 1048576:7.2f} MB  {rel}")

    for w in warnings:
        print(f"[WARN] {w}")

    if failures:
        print("\n仓库体积门禁失败：")
        for f in failures:
            print(f"  ✗ {f}")
        return 1
    print("REPO BLOAT OK：无新增超限文件，生成物二进制未入库")
    return 0


if __name__ == "__main__":
    sys.exit(main())
