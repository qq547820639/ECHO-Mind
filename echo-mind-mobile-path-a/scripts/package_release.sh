#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-/mnt/data}"
VERSION="0.2.0"
BASE="ECHO_Mind_Android_PathA_PilotCandidate_v${VERSION}"
cd "$ROOT"
./scripts/release_preflight.sh
sha256sum -c FILE_HASHES.sha256 >/tmp/echo-hash-check.txt
rm -f "$OUT_DIR/$BASE.zip" "$OUT_DIR/$BASE.tar.gz" "$OUT_DIR/$BASE.bundle"
(
  cd "$ROOT/.."
  zip -qr "$OUT_DIR/$BASE.zip" "$(basename "$ROOT")" \
    -x '*/.git/*' '*/.venv/*' '*/__pycache__/*' '*/.pytest_cache/*' '*/.gradle/*' '*/build/*' '*.db' '*.pyc'
  tar --exclude='.git' --exclude='.venv' --exclude='__pycache__' --exclude='.pytest_cache' \
      --exclude='.gradle' --exclude='build' --exclude='*.db' --exclude='*.pyc' \
      -czf "$OUT_DIR/$BASE.tar.gz" "$(basename "$ROOT")"
)
git bundle create "$OUT_DIR/$BASE.bundle" --all
unzip -tq "$OUT_DIR/$BASE.zip" >/tmp/echo-zip-check.txt
tar -tzf "$OUT_DIR/$BASE.tar.gz" >/tmp/echo-tar-check.txt
git bundle verify "$OUT_DIR/$BASE.bundle" >/tmp/echo-bundle-check.txt
sha256sum "$OUT_DIR/$BASE.zip" "$OUT_DIR/$BASE.tar.gz" "$OUT_DIR/$BASE.bundle" > "$OUT_DIR/$BASE.ARTIFACTS.sha256"
printf 'packaged %s\n' "$BASE"
