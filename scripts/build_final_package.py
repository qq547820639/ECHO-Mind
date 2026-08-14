#!/usr/bin/env python3
"""Final Release Package 构建（ERA 12.8 §17/§18 终态）。

将最终交付物集合打包为 `ECHO_Mind_v{VERSION}.release.zip`：
  - 交付 APK（根签名 APK，或未签名 release APK 作为 dev 交付物）+ idsig
  - SBOM / BUILD_PROVENANCE / DELIVERY_MANIFEST / RELEASE_ARTIFACT_MANIFEST
  - SOURCE_MANIFEST.sha256 / Release Notes / source archive（zip + tar.gz）

规则：只收录 RELEASE_ARTIFACT_MANIFEST 中的条目（外加 idsig）；
确定性写入（NFC + UTF-8 标志 + 固定时间戳）；随后必须跑 verify_final_package.py。
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import distribution as dist  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="releases")
    ap.add_argument("--repo", default=str(dist.ROOT))
    args = ap.parse_args()

    repo = Path(args.repo).resolve()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    version = json.loads(
        (repo / "scripts" / "version_source.json").read_text(encoding="utf-8")
    )["release_version"]

    artifact_manifest_path = repo / "RELEASE_ARTIFACT_MANIFEST.sha256"
    if not artifact_manifest_path.is_file():
        print("FAIL：RELEASE_ARTIFACT_MANIFEST.sha256 不存在（先跑 generate_provenance.py）")
        return 1
    entries = dist.parse_manifest(artifact_manifest_path.read_text(encoding="utf-8"))

    # 最终包 = 清单条目 + idsig（签名衍生）+ 清单自身（DAG 末端容器自带参照物，
    # 由 verify_final_package 双向复核；清单不 hash 自身 → 无循环）
    files: list[tuple[str, bytes]] = []
    seen: set[str] = set()
    files.append(("RELEASE_ARTIFACT_MANIFEST.sha256", artifact_manifest_path.read_bytes()))
    seen.add("RELEASE_ARTIFACT_MANIFEST.sha256")
    for rel in sorted(entries):
        if rel in seen:
            continue
        p = repo / rel
        if not p.is_file():
            print(f"FAIL：artifact manifest 条目在仓库中缺失：{rel}")
            return 1
        files.append((rel, p.read_bytes()))
        seen.add(rel)
        idsig = Path(str(p) + ".idsig")
        if p.suffix == ".apk" and idsig.is_file() and str(idsig.relative_to(repo)) not in seen:
            files.append((str(idsig.relative_to(repo)), idsig.read_bytes()))
            seen.add(str(idsig.relative_to(repo)))

    out = out_dir / f"ECHO_Mind_v{version}.release.zip"
    dist.write_zip(out, f"ECHO_Mind_v{version}", files,
                   mtime=datetime.now(timezone.utc).replace(microsecond=0))
    print(json.dumps({"package": str(out), "entries": len(files)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
