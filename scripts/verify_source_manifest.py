#!/usr/bin/env python3
"""校验已提交的 SOURCE_MANIFEST.sha256 与当前工作树一致（v3.3 §93：防 stale hash）。

在 CI（source-integrity）与 release_preflight 中运行；
重新计算源文件清单并与仓库内已提交的 SOURCE_MANIFEST.sha256 逐行比较，
任何不一致（新增/删除/内容变化）→ 非零退出（提示重新运行 update_release_metadata.py）。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import update_release_metadata as um  # noqa: E402


def recompute() -> str:
    lines = []
    for path in um._collect_source_files():
        digest = um.hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {um._nfc(path)}")
    return "\n".join(lines) + "\n"


def main() -> int:
    committed = (ROOT / "SOURCE_MANIFEST.sha256").read_text(encoding="utf-8")
    current = recompute()
    if committed == current:
        print(f"SOURCE_MANIFEST OK：{len(current.splitlines())} 个源文件与当前工作树一致")
        return 0
    committed_lines = committed.splitlines()
    current_lines = current.splitlines()
    missing = [l for l in committed_lines if l not in set(current_lines)]
    extra = [l for l in current_lines if l not in set(committed_lines)]
    print(f"SOURCE_MANIFEST 漂移：committed={len(committed_lines)} current={len(current_lines)}")
    for l in missing[:5]:
        print(f"  已提交但当前缺失/变化：{l.split('  ', 1)[1] if '  ' in l else l}")
    for l in extra[:5]:
        print(f"  当前存在但未入清单：{l.split('  ', 1)[1] if '  ' in l else l}")
    print("请运行：REUSE_REPORT=1 backend/.venv/bin/python scripts/update_release_metadata.py")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
