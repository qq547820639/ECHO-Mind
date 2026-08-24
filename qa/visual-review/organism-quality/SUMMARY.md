# Organism Quality Pass — 自动指标总表

| Shot | 后端（请求→实际） | nearBlack | highLum | glint | warm | negSpace | mass@.9R | bbox W×H | heroLum | bright | chrom | chromSat | cVol | wire | cavity | 门 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 01_KNOWN_DAY28_APP | ADVANCED→CANVAS（AGSL raster requires hardware canvas — offscreen export uses production CANVAS backend） | 71.7% | 0.0% | 0.0% | 0.1% | 99.9% | 86.8% | 75.9%×34.2% | 0.158| 18.9% | 48.1% | 0.595| 50.5% | 0.049| 0.267| PASS |
| 02_SEED_APP | ADVANCED→CANVAS（AGSL raster requires hardware canvas — offscreen export uses production CANVAS backend） | 79.3% | 0.0% | 0.0% | 0.0% | 100.0% | 87.9% | 72.8%×32.1% | 0.112| 8.7% | 19.0% | 0.498| 28.3% | 0.242| 0.228| FAIL |
| 03_QUIET_APP | ADVANCED→CANVAS（AGSL raster requires hardware canvas — offscreen export uses production CANVAS backend） | 76.6% | 0.0% | 0.0% | 0.1% | 100.0% | 87.9% | 74.6%×33.7% | 0.149| 17.6% | 42.8% | 0.578| 48.6% | 0.150| 0.260| PASS |
| 04_KNOWN_DAY28_WALLPAPER | LEGACY→CANVAS | 77.6% | 0.0% | 0.0% | 0.1% | 100.0% | 87.5% | 75.6%×33.2% | 0.142| 15.8% | 38.4% | 0.569| 43.2% | 0.149| 0.255| PASS |
| APPENDIX_01_CANVAS | LEGACY→CANVAS | 71.7% | 0.0% | 0.0% | 0.1% | 99.9% | 86.8% | 75.9%×34.2% | 0.158| 18.9% | 48.1% | 0.595| 50.5% | 0.049| 0.267| PASS |

阈值：nearBlack≥58% · highLum≤4% · glint≤2.5% · warm target≤10%（hard≤15%）· negSpace≥40% · mass@.9R≥82% · bbox 宽 72–82% viewport · cavity<0.45
Breakthrough 艺术门（§31）：heroLum 0.10–0.20 · bright≥10% · chromatic≥30% · chromSat≥0.55 · centralVol≥30% · wireframe≤0.30

- 01/02/03 请求 ADVANCED：API≥36 真机 resolution=AGSL_ADVANCED，API 33–35（含本 JVM）resolution=AGSL；离屏 raster 恒 CANVAS（软件位图无法执行 RuntimeShader——Android 真实约束，reason 落盘）。
- AGSL raster 视觉证据 = APPENDIX_ADVANCED_MASK.png（生产 mask 输入）+ 设备 instrumented 门（BLOCKED_EXTERNAL_DEVICE：本环境无真机/模拟器）。
- 04 Wallpaper 按生产请求 LEGACY/CANVAS + CONSERVE（电池敏感面不默认 AGSL）。
- 05 为 production facade 同链渲染的双 identity 对比（结构差异 ≠ 换色）。
- 人眼审美结论 = PENDING_PRINCIPAL_VISUAL_REVIEW；本表只报告自动 guardrail 指标。
