#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-/mnt/data}"
VERSION="0.2.0"
BASE="ECHO_Mind_Android_PathA_PilotCandidate_v${VERSION}"
# 源码已位于仓库根，打包时固定顶层目录名以保证产物可复现
PKG_NAME="echo-mind-mobile-path-a"
cd "$ROOT"
./scripts/release_preflight.sh
sha256sum -c FILE_HASHES.sha256 >/tmp/echo-hash-check.txt
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
