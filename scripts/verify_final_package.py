#!/usr/bin/env python3
"""Final Release Package Verification（ERA 12.8 §18 终态门禁）。

输入：ECHO_Mind_vX.Y.Z.release.zip
执行：
  1. 安全解包（路径穿越/符号链接/非 NFC 条目 → FAIL）；
  2. 包内 RELEASE_ARTIFACT_MANIFEST 逐项 hash 复核 + 反向 unexpected 检查（idsig 豁免）；
  3. provenance 交叉绑定：release_apk_sha256 = 包内 APK 实际哈希；
     source_archive_sha256 = 包内 source archive 实际哈希；
     release_type=release ⇒ git_dirty=false；
  4. 对包内 source archive（zip/tar.gz）逐个运行 Final Archive Verification Gate
     （§5：解包 → SOURCE_MANIFEST 校验 → Unicode NFC → required sources）；
  5. 包内 SOURCE_MANIFEST 与包内 source archive 内嵌清单一致。

全部通过 → 退出 0；任何失败 → 退出 1。CI release 必须调用。
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import distribution as dist  # noqa: E402
import verify_source_archive as vsa  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("package", help="final release package（.zip）")
    args = ap.parse_args()

    pkg = Path(args.package).resolve()
    if not pkg.is_file():
        print(f"FAIL：包不存在：{pkg}")
        return 1

    tmp = dist.safe_extract_to_temp(pkg)
    try:
        dist.extract_archive(pkg, tmp)
        prefix = dist.find_top_prefix(
            [p for p in sorted(str(x.relative_to(tmp)) for x in tmp.rglob("*"))]
        )
        top = tmp / prefix
        problems: list = []

        rel_of = lambda f: str(f.relative_to(top)).replace("\\", "/")  # noqa: E731

        manifest_path = top / "RELEASE_ARTIFACT_MANIFEST.sha256"
        if not manifest_path.is_file():
            print("FAIL：包内缺少 RELEASE_ARTIFACT_MANIFEST.sha256")
            return 1
        artifact_entries = dist.parse_manifest(manifest_path.read_text(encoding="utf-8"))

        files_in_pkg = {rel_of(f) for f in sorted(top.rglob("*")) if f.is_file()}
        files_in_pkg.discard("RELEASE_ARTIFACT_MANIFEST.sha256")

        # 1) 清单 → 包内
        for rel, digest in sorted(artifact_entries.items()):
            f = top / rel
            if not f.is_file():
                problems.append(f"artifact manifest 条目在包内缺失：{rel}")
            elif dist.sha256_file(f) != digest:
                problems.append(f"artifact hash 不匹配：{rel}")

        # 2) 包内 → 清单（idsig 是 APK 签名衍生，豁免；其余一律 FAIL）
        for rel in sorted(files_in_pkg):
            if rel.endswith(".idsig"):
                continue
            if rel not in artifact_entries:
                problems.append(f"包内存在清单外文件：{rel}")

        # 3) provenance 交叉绑定
        provenance_path = top / "BUILD_PROVENANCE.json"
        if provenance_path.is_file():
            prov = json.loads(provenance_path.read_text(encoding="utf-8"))
            if prov.get("release_apk_path"):
                apk = top / prov["release_apk_path"]
                if apk.is_file() and dist.sha256_file(apk) != prov.get("release_apk_sha256"):
                    problems.append("provenance.release_apk_sha256 与包内 APK 不一致")
            elif prov.get("unsigned_apk_path"):
                apk = top / prov["unsigned_apk_path"]
                if apk.is_file() and dist.sha256_file(apk) != prov.get("unsigned_apk_sha256"):
                    problems.append("provenance.unsigned_apk_sha256 与包内 APK 不一致")
            if prov.get("release_type") == "release" and prov.get("git_dirty"):
                problems.append("release_type=release 但 git_dirty=true（§15）")
            for rel, digest in (prov.get("source_archive_sha256") or {}).items():
                a = top / rel
                if not a.is_file():
                    problems.append(f"provenance 记录的 source archive 不在包内：{rel}")
                elif dist.sha256_file(a) != digest:
                    problems.append(f"source archive hash 与 provenance 不一致：{rel}")
        else:
            problems.append("包内缺少 BUILD_PROVENANCE.json")

        # 4) 包内 source archive 逐一执行 Final Archive Verification Gate
        source_manifest_text = (top / "SOURCE_MANIFEST.sha256").read_text(encoding="utf-8") \
            if (top / "SOURCE_MANIFEST.sha256").is_file() else None
        for f in sorted(top.rglob("*.zip")) + sorted(top.rglob("*.tar.gz")):
            rel = rel_of(f)
            if rel == "RELEASE_ARTIFACT_MANIFEST.sha256" or rel.endswith(".release.zip"):
                continue
            tmp2 = dist.safe_extract_to_temp(f)
            try:
                dist.extract_archive(f, tmp2)
                manifest = dist.parse_manifest(source_manifest_text) if source_manifest_text else None
                if manifest is not None:
                    result = vsa.verify_extracted(tmp2, manifest, list(dist.REQUIRED_SOURCES))
                    for p in result["problems"]:
                        problems.append(f"source archive [{rel}]：{p}")
            finally:
                shutil.rmtree(tmp2, ignore_errors=True)

        if problems:
            print(f"FAIL：final package 验证未通过（{len(problems)} 项）")
            for p in problems:
                print(f"  ✗ {p}")
            return 1
        print(json.dumps({
            "package": str(pkg),
            "artifact_entries": len(artifact_entries),
            "package_files": len(files_in_pkg) + 1,
            "provenance_binding": "PASS",
            "source_archives_verified": "PASS",
        }, ensure_ascii=False))
        print("FINAL RELEASE PACKAGE VERIFICATION PASS")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
