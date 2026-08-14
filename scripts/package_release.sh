#!/usr/bin/env bash
# package_release.sh — ERA 12.8 Final Distribution Closure 编排（§17 原子发布流程）
#
# 同一 run 内执行：
#   source manifest verify → (preflight) → source archive（确定性）
#   → verify extracted source archive（§5/§6 Gate）
#   → SBOM → provenance（root APK 绑定）→ delivery → artifact manifest
#   → final release package → verify final package（§18 终态门禁）
#
# 用法：
#   ./scripts/package_release.sh [--out-dir DIR] [--skip-preflight]
#
# 环境变量：
#   ANDROID_GRADLE_BUILD_RESULT  —— 本 pipeline run 的 Android 构建结果（§16：只接受注入）
#   REUSE_REPORT=1               —— 复用既有 junit 报告
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/releases}"
SKIP_PREFLIGHT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --skip-preflight) SKIP_PREFLIGHT=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
PY="${PYTHON:-$ROOT/backend/.venv/bin/python}"
if [ ! -x "$PY" ]; then PY="$(command -v python3 || echo python3)"; fi
export OUT_DIR
cd "$ROOT"
mkdir -p "$OUT_DIR"

echo "== 1/9 SOURCE_MANIFEST verify（git 受控源文件集）=="
"$PY" scripts/verify_source_manifest.py

if [ "$SKIP_PREFLIGHT" = "0" ]; then
  echo "== 2/9 preflight（backend tests / lint / migration / android 如环境可用）=="
  ./scripts/release_preflight.sh
fi

echo "== 3/9 build deterministic source archive =="
"$PY" scripts/build_source_archive.py --out-dir "$OUT_DIR"

VERSION="$("$PY" -c "import json;print(json.load(open('scripts/version_source.json', encoding='utf-8'))['release_version'])")"
ZIP="$OUT_DIR/ECHO_Mind_PortraitCore_v${VERSION}.zip"
TARGZ="$OUT_DIR/ECHO_Mind_PortraitCore_v${VERSION}.tar.gz"

echo "== 4/9 verify extracted source archive（NFC / UTF-8 标志 / required / 双向清单）=="
"$PY" scripts/verify_source_archive.py "$ZIP"
"$PY" scripts/verify_source_archive.py "$TARGZ"

echo "== 5/9 SBOM =="
"$PY" scripts/generate_sbom.py

echo "== 6/9 SOURCE_MANIFEST + DELIVERY_MANIFEST（build status 绑定本 run）=="
ANDROID_GRADLE_BUILD_RESULT="${ANDROID_GRADLE_BUILD_RESULT:-not_run_in_this_pipeline}" \
REUSE_REPORT="${REUSE_REPORT:-1}" "$PY" scripts/update_release_metadata.py
"$PY" scripts/verify_source_manifest.py

echo "== 7/9 BUILD_PROVENANCE（root APK 绑定 + unsigned/signed 双哈希 + signing stage）=="
"$PY" scripts/generate_provenance.py

echo "== 8/9 final release package =="
"$PY" scripts/build_final_package.py --out-dir "$OUT_DIR"

echo "== 9/9 verify final release package（§18 终态门禁）=="
"$PY" scripts/verify_final_package.py "$OUT_DIR/ECHO_Mind_v${VERSION}.release.zip"

echo
echo "DISTRIBUTION CLOSURE PASS —— $VERSION"
ls -lh "$OUT_DIR"/ECHO_Mind_PortraitCore_v${VERSION}.zip \
       "$OUT_DIR"/ECHO_Mind_PortraitCore_v${VERSION}.tar.gz \
       "$OUT_DIR"/ECHO_Mind_v${VERSION}.release.zip
