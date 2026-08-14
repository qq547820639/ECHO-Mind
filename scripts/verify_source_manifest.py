#!/usr/bin/env python3
"""校验已提交的 SOURCE_MANIFEST.sha256 与 git 受控源文件集一致（ERA 12.8 修订）。

在 CI（source-integrity）与 release_preflight 中运行：
1. 重新计算 **git 受控** 源文件清单（update_release_metadata._collect_source_files），
   与仓库内已提交的 SOURCE_MANIFEST.sha256 逐行比较；
   任何不一致（新增/删除/内容变化/路径 NFC 漂移）→ 非零退出。
2. 路径合法性与 NFC 检查：拒绝 build/、.gradle/、缓存、APK 等非源码条目。

退出码：0 = 一致；1 = 漂移或非法条目。
"""
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import update_release_metadata as um  # noqa: E402

MANIFEST_PATH = ROOT / "SOURCE_MANIFEST.sha256"


def recompute() -> str:
    lines = []
    for path in um._collect_source_files():
        digest = um.hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {um._nfc(path)}")
    return "\n".join(lines) + "\n"


def parse_manifest(text: str) -> list[tuple[str, str]]:
    entries = []
    for line_no, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            digest, rel = line.split("  ", 1)
        except ValueError:
            print(f"非法清单行（{line_no}）：{line!r}")
            raise
        entries.append((digest, rel))
    return entries


def validate_paths(entries: list[tuple[str, str]]) -> list[str]:
    problems = []
    for digest, rel in entries:
        if rel != unicodedata.normalize("NFC", rel):
            problems.append(f"路径非 NFC：{rel}")
        if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            problems.append(f"非法 hash：{rel}")
        parts = Path(rel).parts
        if any(part in um.EXCLUDED_PARTS for part in parts):
            problems.append(f"清单包含排除目录条目：{rel}")
        name = parts[-1]
        if name in um.EXCLUDED_FILES or Path(name).suffix in um.EXCLUDED_SUFFIXES:
            problems.append(f"清单包含生成物条目：{rel}")
    return problems


def main() -> int:
    committed = MANIFEST_PATH.read_text(encoding="utf-8")
    current = recompute()
    problems = validate_paths(parse_manifest(committed))
    if problems:
        print("SOURCE_MANIFEST 包含非法条目：")
        for p in problems:
            print(f"  ✗ {p}")
        return 1
    if committed == current:
        print(f"SOURCE_MANIFEST OK：{len(current.splitlines())} 个 git 受控源文件与清单一致")
        return 0
    committed_lines = set(committed.splitlines())
    current_lines = set(current.splitlines())
    missing = [l for l in committed_lines if l not in current_lines]
    extra = [l for l in current_lines if l not in committed_lines]
    print(f"SOURCE_MANIFEST 漂移：committed={len(committed_lines)} current={len(current_lines)}")
    for l in sorted(missing)[:5]:
        print(f"  已提交但当前缺失/变化：{l.split('  ', 1)[1] if '  ' in l else l}")
    for l in sorted(extra)[:5]:
        print(f"  当前存在但未入清单：{l.split('  ', 1)[1] if '  ' in l else l}")
    print("请运行：python3 scripts/update_release_metadata.py && python3 scripts/generate_provenance.py")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
