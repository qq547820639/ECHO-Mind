#!/usr/bin/env python3
"""analyze_organism.py — ECHO Organism 机器视觉代理指标（无多模态 Agent 的眼睛）。

用法:
    python scripts/visual/analyze_organism.py android/feature/qa/visual-review/organism/known_day.png [...]
    python scripts/visual/analyze_organism.py --json out.json png1 png2 ...

指标定义（Organism Visual Breakthrough §7/§31/§32/§33）:
  hero ROI          视口中央方形（边 = 0.76 × 视口宽；与 hero bbox 目标 0.68–0.84 同族）
  mean_luminance    hero ROI 平均 luma（目标 0.12–0.18）
  p95_luminance     hero ROI luma 95 分位
  bright_ratio      hero ROI luma > 0.25 像素占比（目标 12%–22%）
  very_bright_ratio hero ROI luma > 0.80 像素占比（目标 ≤ 8%；全局口径 ≤4% 由 VisualLabMetrics 承担）
  chromatic_cov     hero ROI「彩色发光」占比：sat > 0.18 且 luma > 0.05（目标 ≥ 0.30）
  chromatic_sat     彩色发光像素的平均饱和度（目标 ≥ 0.55）
  warm_ratio        hero ROI 暖色像素占比（hue≈10–50°、r>b、sat>0.25、luma>0.08；宪法 <5%）
  hero_bbox_w       全图发光像素（luma>0.06）包围盒宽 / 视口宽（目标 0.68–0.84）
  central_volume    发光 bbox 中央 60%×60% 内彩色发光占比（反「空心线框」）
  wireframe_dom     发光像素中「细线像素」占比：5×5 腐蚀后消失的发光像素比例（越低越有体积）
  dark_neg_space    全图近黑（luma<0.045）占比（宪法：大面积黑暗，≥50%）

只做 composition/density/tone 代理比较，不替代人眼，不 pixel-perfect。
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

NEAR_BLACK = 0.045
LUMINOUS = 0.06
BRIGHT = 0.25
VERY_BRIGHT = 0.80
CHROM_SAT_MIN = 0.18
CHROM_LUMA_MIN = 0.05
WARM_LUMA_MIN = 0.08


def load(path: Path) -> np.ndarray:
    img = Image.open(path).convert("RGB")
    return np.asarray(img, dtype=np.float32) / 255.0


def luminance(rgb: np.ndarray) -> np.ndarray:
    return 0.2126 * rgb[..., 0] + 0.5872 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def saturation(rgb: np.ndarray) -> np.ndarray:
    mx = rgb.max(axis=-1)
    mn = rgb.min(axis=-1)
    return np.where(mx > 1e-6, (mx - mn) / np.maximum(mx, 1e-6), 0.0)


def is_warm(rgb: np.ndarray, luma: np.ndarray) -> np.ndarray:
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx = rgb.max(axis=-1)
    mn = rgb.min(axis=-1)
    sat = np.where(mx > 1e-6, (mx - mn) / np.maximum(mx, 1e-6), 0.0)
    return (r > b * 1.35) & (r >= g) & (sat > 0.25) & (luma > WARM_LUMA_MIN)


def erode5(mask: np.ndarray) -> np.ndarray:
    """5×5 方形腐蚀（numpy 滚动最小值；边界视为 False）。"""
    h, w = mask.shape
    padded = np.zeros((h + 4, w + 4), dtype=bool)
    padded[2:-2, 2:-2] = mask
    out = np.ones_like(mask)
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            out &= padded[2 + dy : 2 + dy + h, 2 + dx : 2 + dx + w]
    return out


def analyze(path: Path) -> dict:
    rgb = load(path)
    h, w = rgb.shape[:2]
    luma = luminance(rgb)
    sat = saturation(rgb)

    # hero ROI：视口中央方形（边 = 0.76 × 宽；短边保护）
    side = int(0.76 * min(w, h) if w < h else 0.76 * w)
    side = min(side, w, h)
    x0, x1 = (w - side) // 2, (w + side) // 2
    y0, y1 = (h - side) // 2, (h + side) // 2
    hero_luma = luma[y0:y1, x0:x1]
    hero_sat = sat[y0:y1, x0:x1]
    hero_rgb = rgb[y0:y1, x0:x1]

    chromatic = (hero_sat > CHROM_SAT_MIN) & (hero_luma > CHROM_LUMA_MIN)
    chromatic_strong = (hero_sat > 0.25) & (hero_luma > 0.10)
    bright = hero_luma > BRIGHT
    very_bright = hero_luma > VERY_BRIGHT
    bright50 = hero_luma > 0.50
    warm = is_warm(hero_rgb, hero_luma)

    # 发光 bbox（全图；与 VisualLabMetrics 口径一致 luma > 0.06）
    lum_mask = luma > LUMINOUS
    ys, xs = np.nonzero(lum_mask)
    if len(xs) > 0:
        bw = (xs.max() - xs.min()) / w
        bh = (ys.max() - ys.min()) / h
    else:
        bw = bh = 0.0

    # 中央体积：发光 bbox 中央 60%×60% 内彩色发光占比
    if len(xs) > 0:
        bx0, bx1 = xs.min(), xs.max()
        by0, by1 = ys.min(), ys.max()
        cw = (bx1 - bx0) * 0.6
        ch = (by1 - by0) * 0.6
        cx0, cx1 = int((bx0 + bx1 - cw) / 2), int((bx0 + bx1 + cw) / 2)
        cy0, cy1 = int((by0 + by1 - ch) / 2), int((by0 + by1 + ch) / 2)
        cx0, cx1 = max(0, cx0), min(w, cx1)
        cy0, cy1 = max(0, cy0), min(h, cy1)
        sub_chrom = (sat[cy0:cy1, cx0:cx1] > CHROM_SAT_MIN) & (luma[cy0:cy1, cx0:cx1] > CHROM_LUMA_MIN)
        central_volume = float(sub_chrom.mean()) if sub_chrom.size else 0.0
    else:
        central_volume = 0.0

    # wireframe dominance：发光像素经 5×5 腐蚀后消失的比例（细线≈1.0，实心体积→低）
    hero_lum = lum_mask[y0:y1, x0:x1]
    lum_count = int(hero_lum.sum())
    if lum_count > 0:
        survived = int(erode5(hero_lum).sum())
        wireframe_dom = 1.0 - survived / lum_count
    else:
        wireframe_dom = 1.0

    return {
        "file": str(path),
        "viewport": f"{w}x{h}",
        "mean_luminance": round(float(hero_luma.mean()), 5),
        "p95_luminance": round(float(np.percentile(hero_luma, 95)), 5),
        "bright_ratio": round(float(bright.mean()), 5),
        "very_bright_ratio": round(float(very_bright.mean()), 5),
        "bright50_ratio": round(float(bright50.mean()), 5),
        "chromatic_cov": round(float(chromatic.mean()), 5),
        "chromatic_cov_strong": round(float(chromatic_strong.mean()), 5),
        "chromatic_sat": round(float(hero_sat[chromatic].mean()) if chromatic.any() else 0.0, 5),
        "chromatic_sat_strong": round(
            float(hero_sat[chromatic_strong].mean()) if chromatic_strong.any() else 0.0, 5
        ),
        "warm_ratio": round(float(warm.mean()), 5),
        "hero_bbox_w": round(float(bw), 5),
        "hero_bbox_h": round(float(bh), 5),
        "central_volume": round(central_volume, 5),
        "wireframe_dom": round(wireframe_dom, 5),
        "dark_neg_space": round(float((luma < NEAR_BLACK).mean()), 5),
        "near_black_hero": round(float((hero_luma < NEAR_BLACK).mean()), 5),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="ECHO organism art metrics")
    ap.add_argument("pngs", nargs="+", help="PNG 路径（可多个）")
    ap.add_argument("--json", help="聚合 JSON 输出路径")
    ap.add_argument("--csv", help="聚合 CSV 输出路径")
    ap.add_argument("--md", help="聚合 Markdown 表格输出路径")
    args = ap.parse_args()

    results = []
    for p in args.pngs:
        path = Path(p)
        if not path.exists():
            print(f"skip (missing): {path}", file=sys.stderr)
            continue
        results.append(analyze(path))

    if not results:
        print("no png analyzed", file=sys.stderr)
        return 1

    fields = [k for k in results[0] if k not in ("file", "viewport")]
    lines = []
    header = ["file", "viewport"] + fields
    lines.append("| " + " | ".join(header) + " |")
    lines.append("|" + "---|" * len(header))
    for r in results:
        lines.append(
            "| " + " | ".join([Path(r["file"]).name, r["viewport"]] + [str(r[f]) for f in fields]) + " |"
        )
    table = "\n".join(lines)
    print(table)

    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as f:
            wr = csv.DictWriter(f, fieldnames=header)
            wr.writeheader()
            wr.writerows(results)
    if args.md:
        Path(args.md).parent.mkdir(parents=True, exist_ok=True)
        Path(args.md).write_text(table + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
