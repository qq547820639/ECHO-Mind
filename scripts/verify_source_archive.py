#!/usr/bin/env python3
"""Final Archive Verification Gate（ERA 12.8 §5/§6/§7）。

输入：source archive（zip 或 tar.gz）路径
执行：
  1. 解包到临时目录（安全解包：拒绝路径穿越/符号链接/非 UTF-8/非 NFC 条目名）；
  2. 用归档内嵌 SOURCE_MANIFEST.sha256 重新校验（或 --manifest 指定外部清单）；
  3. 路径正规化校验：git path = manifest path = zip entry = unpacked path（全 NFC）；
  4. required-source 校验（EchoRuntimeCoordinator.kt 等关键文件必须存在）；
  5. 反向校验：解包树中的每个文件都必须在清单中（unexpected file → FAIL）；
  6. 非源码条目检查（build/、.gradle/、缓存、*.apk 等 → FAIL）。

任何一步失败 → 非零退出。CI release 必须调用；本地 package_release.sh 同样调用。
"""
import argparse
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import distribution as dist  # noqa: E402


def verify_extracted(extract_root: Path, manifest: dict, required: list) -> dict:
    problems: list = []
    prefix = dist.find_top_prefix(
        [p for p in sorted(str(x.relative_to(extract_root)) for x in extract_root.rglob("*"))]
    )
    top = extract_root / prefix
    manifest_rel = f"{prefix}/{dist.MANIFEST_NAME}"
    manifest_file = extract_root / manifest_rel

    if not manifest_file.is_file():
        return {"problems": [f"归档缺少内嵌清单：{dist.MANIFEST_NAME}"], "files_ok": 0, "files": 0}

    embedded = manifest_file.read_text(encoding="utf-8")
    if dist.parse_manifest(embedded) != manifest:
        problems.append("内嵌 SOURCE_MANIFEST 与校验清单不一致")

    # 1) 清单 → 解包树：每个清单条目必须存在且 hash 一致
    files_ok = 0
    for rel, digest in sorted(manifest.items()):
        f = top / rel
        if not f.is_file():
            problems.append(f"清单文件在归档中缺失：{rel}")
            continue
        if dist.sha256_file(f) != digest:
            problems.append(f"hash 不匹配：{rel}")
            continue
        files_ok += 1

    # 2) 解包树 → 清单：unexpected file（内嵌清单自身除外）
    for f in sorted(top.rglob("*")):
        if not f.is_file():
            continue
        rel = str(f.relative_to(top)).replace("\\", "/")
        if rel == dist.MANIFEST_NAME:
            continue
        if rel not in manifest:
            problems.append(f"归档包含清单外文件：{rel}")

    # 3) 路径正规化（解包后的磁盘路径与清单路径逐字节一致，全 NFC）
    for rel in sorted(manifest):
        if dist.nfc(rel) != rel:
            problems.append(f"清单路径非 NFC：{rel}")
        if any(part in dist.FORBIDDEN_PARTS for part in Path(rel).parts):
            problems.append(f"清单包含禁止目录：{rel}")
        if Path(rel).suffix in dist.FORBIDDEN_SUFFIXES:
            problems.append(f"清单包含禁止后缀：{rel}")

    # 4) required sources
    for rel in required:
        if rel not in manifest:
            problems.append(f"必需源码缺失：{rel}")

    return {"problems": problems, "files_ok": files_ok, "files": len(manifest)}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("archive", help="source archive（.zip 或 .tar.gz）")
    ap.add_argument("--manifest", default=None,
                    help="外部 SOURCE_MANIFEST.sha256（默认按 git checkout 重新计算）")
    ap.add_argument("--repo", default=str(dist.ROOT),
                    help="git 仓库根（默认脚本所在仓库）")
    ap.add_argument("--standalone", action="store_true",
                    help="离线模式：仅以内嵌清单为基准（不要求 git checkout）")
    ap.add_argument("--required", default=None,
                    help="额外 required 文件清单（每行一个路径）")
    args = ap.parse_args()

    archive = Path(args.archive).resolve()
    if not archive.is_file():
        print(f"FAIL：归档不存在：{archive}")
        return 1

    if args.required:
        required = [l.strip() for l in Path(args.required).read_text(encoding="utf-8").splitlines()
                    if l.strip() and not l.startswith("#")]
    else:
        required = list(dist.REQUIRED_SOURCES)

    tmp = dist.safe_extract_to_temp(archive)
    try:
        entries = dist.extract_archive(archive, tmp)
        if args.manifest:
            manifest_text = Path(args.manifest).read_text(encoding="utf-8")
        elif args.standalone:
            prefix = dist.find_top_prefix(
                [p for p in sorted(str(x.relative_to(tmp)) for x in tmp.rglob("*"))]
            )
            embedded = tmp / prefix / dist.MANIFEST_NAME
            if not embedded.is_file():
                print(f"FAIL：归档缺少内嵌清单 {dist.MANIFEST_NAME}")
                return 1
            manifest_text = embedded.read_text(encoding="utf-8")
        else:
            # 默认：与 git checkout 的当前源文件集逐项绑定（§5 同一道 Gate）
            manifest_text = dist.build_manifest(Path(args.repo))
        manifest = dist.parse_manifest(manifest_text)
        result = verify_extracted(tmp, manifest, required)

        problems = result["problems"]
        if problems:
            print(f"FAIL：{archive.name} 验证未通过（{len(problems)} 项）")
            for p in problems:
                print(f"  ✗ {p}")
            return 1
        print(json.dumps({
            "archive": str(archive),
            "entries": len(entries),
            "manifest_files": result["files"],
            "hash_verified": result["files_ok"],
            "unexpected_files": 0,
            "unicode_nfc": "PASS",
            "required_sources": "PASS",
        }, ensure_ascii=False))
        print("SOURCE ARCHIVE VERIFICATION PASS")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
