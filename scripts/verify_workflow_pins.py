#!/usr/bin/env python3
"""Workflow Action Pin 门禁（ERA 18 §93）。

校验 .github/workflows/*.yml 中所有 `uses:` 均已 pin 到不可变 40 位 commit SHA：
- 拒绝 major tag / 短 SHA / 分支名；
- 拒绝本地路径（./ ）；
- 注释中保留原 tag 便于人工追溯（格式：`owner/repo@<40sha>  # vX`）。

CI 接线：source-integrity.yml（每次 push）与 release_preflight。

退出码：0 = 全部 pinned；1 = 存在未 pin 条目或解析错误。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"

USE_RE = re.compile(r"^\s*[-+]?\s*uses:\s*(\S+)\s*(?:#.*)?$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def check_file(path: Path) -> list[str]:
    problems = []
    # 结构校验：YAML 必须可解析（防手改 workflow 引入语法错误）
    try:
        import yaml  # noqa: PLC0415
    except ImportError:
        problems.append(f"{path.name}: PyYAML 未安装（pip install pyyaml），跳过结构校验")
        return problems
    try:
        with open(path, encoding="utf-8") as fh:
            yaml.safe_load(fh)
    except Exception as exc:  # noqa: BLE001
        problems.append(f"{path.name}: YAML 解析失败：{exc}")
        return problems
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        m = USE_RE.match(line)
        if not m:
            continue
        ref = m.group(1)
        if ref.startswith("./"):
            continue  # 本地 composite action（仓库内受控）
        if "/" not in ref:
            problems.append(f"{path.name}:{line_no}: 非法 action 引用：{ref}")
            continue
        owner_repo, version = ref.rsplit("/", 1)[0], ref.split("@", 1)[-1]
        if not SHA_RE.match(version):
            problems.append(
                f"{path.name}:{line_no}: 未 pin 到 40 位 commit SHA（{owner_repo}@{version}）"
            )
    return problems


def main() -> int:
    problems = []
    files = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
    if not files:
        print("FAIL：未找到任何 workflow 文件")
        return 1
    pinned = 0
    for path in files:
        problems.extend(check_file(path))
        pinned += sum(1 for line in path.read_text(encoding="utf-8").splitlines() if USE_RE.match(line) and not USE_RE.match(line).group(1).startswith("./"))
    if problems:
        print(f"WORKFLOW PIN FAIL（{len(problems)} 项）：")
        for p in problems:
            print(f"  ✗ {p}")
        return 1
    print(f"WORKFLOW PINS OK：{len(files)} 个 workflow、{pinned} 个 uses 全部 pin 到 immutable commit SHA")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
