#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-/mnt/data}"
# v0.7.2 修复：版本从单一事实源读取（此前硬编码 "0.7.0" 曾绕过 version_source.json）
VERSION="$(python3 -c "import json;print(json.load(open('$ROOT/scripts/version_source.json', encoding='utf-8'))['release_version'])")"
BASE="ECHO_Mind_PortraitCore_v${VERSION}"
# 源码已位于仓库根，打包时固定顶层目录名以保证产物可复现
PKG_NAME="echo-mind-portrait-core"
cd "$ROOT"
./scripts/release_preflight.sh
sha256sum -c SOURCE_MANIFEST.sha256 >/tmp/echo-source-check.txt
sha256sum -c RELEASE_ARTIFACT_MANIFEST.sha256 >/tmp/echo-artifact-check.txt 2>/dev/null || echo "WARN: artifact manifest 校验未通过（本地构建请先跑 scripts/update_release_metadata.py + scripts/generate_provenance.py）"
rm -f "$OUT_DIR/$BASE.zip" "$OUT_DIR/$BASE.tar.gz" "$OUT_DIR/$BASE.bundle"
(
  cd "$ROOT/.."
  # 在父目录创建固定名的临时软链接指向仓库根，作为打包顶层目录，
  # 使产物顶层名恒为 PKG_NAME，不依赖仓库目录的实际名称
  ln -sfn "$(basename "$ROOT")" "$PKG_NAME"
  trap 'rm -f "$PKG_NAME"' EXIT
  zip -qr "$OUT_DIR/$BASE.zip" "$PKG_NAME" \
    -x '*/.git/*' '*/.venv/*' '*/__pycache__/*' '*/.pytest_cache/*' '*/.gradle/*' '*/build/*' '*.db' '*.pyc'
  tar --exclude='.git' --exclude='.venv' --exclude='__pycache__' --exclude='.pytest_cache' \
      --exclude='.gradle' --exclude='build' --exclude='*.db' --exclude='*.pyc' \
      -czf "$OUT_DIR/$BASE.tar.gz" "$PKG_NAME"
  rm -f "$PKG_NAME"
  trap - EXIT
)
git bundle create "$OUT_DIR/$BASE.bundle" --all
unzip -tq "$OUT_DIR/$BASE.zip" >/tmp/echo-zip-check.txt
tar -tzf "$OUT_DIR/$BASE.tar.gz" >/tmp/echo-tar-check.txt
git bundle verify "$OUT_DIR/$BASE.bundle" >/tmp/echo-bundle-check.txt
sha256sum "$OUT_DIR/$BASE.zip" "$OUT_DIR/$BASE.tar.gz" "$OUT_DIR/$BASE.bundle" > "$OUT_DIR/$BASE.ARTIFACTS.sha256"
printf 'packaged %s\n' "$BASE"
