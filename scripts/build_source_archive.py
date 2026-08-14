#!/usr/bin/env python3
"""确定性 Source Archive 构建（ERA 12.8 §7/§8）。

产物：`{out_dir}/ECHO_Mind_PortraitCore_v{VERSION}.zip` 与 `.tar.gz`
- 内容 = SOURCE_MANIFEST 文件集（git 受控源文件，排除生成物）+ 内嵌 SOURCE_MANIFEST.sha256；
- 路径一律 Unicode NFC UTF-8；ZIP 非 ASCII 条目置 UTF-8 标志（EFS 0x800）——修复
  中文路径打包后 #Uxxxx / 乱码 / NFD 变异问题；
- 时间戳 = HEAD commit time；条目排序确定 —— 同 commit 同字节输出；
- 不在既有 ZIP 上覆盖；不采集 dirty working directory 的未受控文件。

用法：
  python3 scripts/build_source_archive.py [--out-dir DIR] [--prefix NAME] [--repo ROOT]
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import distribution as dist  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="releases")
    ap.add_argument("--prefix", default="echo-mind-portrait-core")
    ap.add_argument("--repo", default=str(dist.ROOT))
    args = ap.parse_args()

    repo = Path(args.repo).resolve()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    version_source = json.loads(
        (repo / "scripts" / "version_source.json").read_text(encoding="utf-8")
    )
    version = version_source["release_version"]
    base = f"ECHO_Mind_PortraitCore_v{version}"

    mtime = dist.commit_timestamp_utc(repo)
    manifest_text = dist.build_manifest(repo).encode("utf-8")
    files = [(rel, data) for rel, data in dist._sorted_source_entries(repo)]
    files.append((dist.MANIFEST_NAME, manifest_text))

    zip_path = out_dir / f"{base}.zip"
    tar_path = out_dir / f"{base}.tar.gz"
    dist.write_zip(zip_path, args.prefix, files, mtime=mtime)
    dist.write_tar_gz(tar_path, args.prefix, files, mtime=mtime)

    print(json.dumps({
        "zip": str(zip_path),
        "tar_gz": str(tar_path),
        "source_files": len(files) - 1,
        "prefix": args.prefix,
        "manifest_sha256": dist.sha256_bytes(manifest_text),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
