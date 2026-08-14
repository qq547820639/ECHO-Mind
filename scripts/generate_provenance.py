#!/usr/bin/env python3
"""Build provenance 生成（v3.2 §8）：当前源码快照的可追溯元数据。

输出 BUILD_PROVENANCE.json（提交到仓库，随 release 一起分发）：
- git commit / 分支
- version_source 全字段
- Android / backend 版本
- toolchain（读取本机 JDK/SDK 时为空——CI 环境由 CI 注入）
- 源码树根哈希（与 FILE_HASHES 一致的规则：排除构建产物/工具目录）
- FILE_HASHES.sha256 的自哈希（防哈希文件漂移）
- 生成时间戳

规则：manifest/hashes/provenance 必须与同一 source commit 一起提交，
任何人可以验证「这个 APK 确实来自这一份源码」。
"""
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

EXCLUDED_PARTS = {
    ".git", ".gradle", ".venv", "__pycache__", ".pytest_cache", "build",
    ".DS_Store", ".trae", ".codebuddy", ".workbuddy", ".idea", ".vscode",
    "runtime", "exports", "releases",
}
EXCLUDED_FILES = {"FILE_HASHES.sha256", "BUILD_PROVENANCE.json"}
EXCLUDED_SUFFIXES = {".pyc", ".db", ".apk"}


def git(*args: str) -> str:
    try:
        return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL).decode().strip()
    except Exception:
        return "unknown"


def tree_digest() -> str:
    h = hashlib.sha256()
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(ROOT)
        if any(part in EXCLUDED_PARTS for part in rel.parts):
            continue
        if path.name in EXCLUDED_FILES or path.suffix in EXCLUDED_SUFFIXES:
            continue
        h.update(str(rel).encode())
        h.update(b"\x00")
        h.update(hashlib.sha256(path.read_bytes()).digest())
    return h.hexdigest()


def main() -> None:
    version_source = json.loads((ROOT / "scripts" / "version_source.json").read_text(encoding="utf-8"))
    file_hashes_path = ROOT / "FILE_HASHES.sha256"
    hashes_digest = hashlib.sha256(file_hashes_path.read_bytes()).hexdigest() if file_hashes_path.exists() else "missing"

    provenance = {
        "schema": "echo-build-provenance-v1",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "git_commit": git("rev-parse", "HEAD"),
        "git_branch": git("rev-parse", "--abbrev-ref", "HEAD"),
        "version": version_source,
        "source_tree_sha256": tree_digest(),
        "file_hashes_sha256_of_sha256_file": hashes_digest,
        "toolchain": {
            "note": "在本机生成时为空；CI 环境由 source-integrity workflow 注入 JDK/SDK 版本。",
            "jdk": "",
            "android_sdk": "",
        },
    }
    out = ROOT / "BUILD_PROVENANCE.json"
    out.write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out.relative_to(ROOT)} (commit={provenance['git_commit'][:12]}, tree={provenance['source_tree_sha256'][:16]})")


if __name__ == "__main__":
    main()
