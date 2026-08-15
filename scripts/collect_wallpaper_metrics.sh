#!/usr/bin/env bash
# ERA 31 §16 — Wallpaper 真机指标采集（部署侧执行）。
#
# 本仓库开发环境无真机；本脚本是 dogfood / 发布前设备矩阵（qa/visual-review/DEVICE_CHECKLIST.md §3）
# 的实测采集工具，由持有真机的人执行，结果归档 qa/reports/dogfood/。
# 禁止用任何 JVM 数字冒充真机数字（§16 Battery Reality）。
#
# 用法：./scripts/collect_wallpaper_metrics.sh [duration_seconds] [out_dir]
# 默认：600 秒采样，输出 qa/reports/dogfood/metrics-<timestamp>/
set -euo pipefail

DURATION="${1:-600}"
OUT="${2:-qa/reports/dogfood/metrics-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
PKG="com.yunjue.echo.mind"

echo "== Wallpaper 指标采集：${DURATION}s → $OUT =="
adb wait-for-device

# 电池基线（重置 batterystats 需 adb root 或开发者选项；失败不阻断其他采集）
adb shell dumpsys batterystats --reset >/dev/null 2>&1 || true
echo "== 电量基线 ==" | tee "$OUT/report.txt"
adb shell dumpsys battery | grep -E "level|status" | tee -a "$OUT/report.txt"

# wallpaper 进程定位（独立进程或主进程回退）
PID=$(adb shell pidof "${PKG}:wallpaper" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)
if [ -z "${PID:-}" ]; then
  PID=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)
fi
echo "wallpaper_pid=${PID:-unknown}" | tee -a "$OUT/report.txt"

# 周期采样（CPU/mem；top 在部分 OEM 不可用时不阻断）
: > "$OUT/cpu_samples.txt"
: > "$OUT/mem_samples.txt"
STEPS=$((DURATION / 10))
for _ in $(seq 1 "$STEPS"); do
  adb shell top -b -n 1 -p "${PID:-0}" 2>/dev/null | tail -3 >> "$OUT/cpu_samples.txt" || true
  adb shell dumpsys meminfo "${PID:-0}" 2>/dev/null | grep -E "TOTAL PSS|TOTAL:" >> "$OUT/mem_samples.txt" || true
  sleep 10
done

# 终态快照
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt" 2>/dev/null || true
adb shell dumpsys batterystats "$PKG" > "$OUT/batterystats.txt" 2>/dev/null || true
echo "== 电量终态 ==" | tee -a "$OUT/report.txt"
adb shell dumpsys battery | grep -E "level" | tee -a "$OUT/report.txt"

echo "== 完成：$OUT（cpu_samples / mem_samples / gfxinfo / batterystats / report）=="
echo "== 下一步：按 DEVICE_CHECKLIST.md §4 模板把数字归档（不可见期/可见期/静态期三段都要有）=="
