# Organism Quality Pass — 自动指标总表

| Shot | 后端（请求→实际） | nearBlack | highLum | glint | warm | negSpace | mass@.9R | bbox W×H | edge | cavity | 门 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 01_KNOWN_DAY28_APP | ADVANCED→CANVAS（AGSL raster requires hardware canvas — offscreen export uses production CANVAS backend） | 88.6% | 0.0% | 0.0% | 0.1% | 100.0% | 97.8% | 72.2%×34.1% | 0.150| 0.061| PASS |
| 02_SEED_APP | ADVANCED→CANVAS（AGSL raster requires hardware canvas — offscreen export uses production CANVAS backend） | 94.8% | 0.0% | 0.0% | 0.1% | 100.0% | 99.5% | 68.1%×28.5% | 0.074| 0.059| FAIL |
| 03_QUIET_APP | ADVANCED→CANVAS（AGSL raster requires hardware canvas — offscreen export uses production CANVAS backend） | 91.9% | 0.0% | 0.0% | 0.1% | 100.0% | 99.0% | 71.1%×33.7% | 0.147| 0.061| FAIL |
| 04_KNOWN_DAY28_WALLPAPER | LEGACY→CANVAS | 92.6% | 0.0% | 0.0% | 0.1% | 100.0% | 97.2% | 72.6%×33.1% | 0.123| 0.060| PASS |
| APPENDIX_01_CANVAS | LEGACY→CANVAS | 88.6% | 0.0% | 0.0% | 0.1% | 100.0% | 97.8% | 72.2%×34.1% | 0.150| 0.061| PASS |

阈值：nearBlack≥58% · highLum≤4% · glint≤2.5% · warm target≤10%（hard≤15%）· negSpace≥40% · mass@.9R≥82% · bbox 宽 72–82% viewport · cavity<0.45

- 01/02/03 请求 ADVANCED：API≥36 真机 resolution=AGSL_ADVANCED，API 33–35（含本 JVM）resolution=AGSL；离屏 raster 恒 CANVAS（软件位图无法执行 RuntimeShader——Android 真实约束，reason 落盘）。
- AGSL raster 视觉证据 = APPENDIX_ADVANCED_MASK.png（生产 mask 输入）+ 设备 instrumented 门（BLOCKED_EXTERNAL_DEVICE：本环境无真机/模拟器）。
- 04 Wallpaper 按生产请求 LEGACY/CANVAS + CONSERVE（电池敏感面不默认 AGSL）。
- 05 为 production facade 同链渲染的双 identity 对比（结构差异 ≠ 换色）。
- 人眼审美结论 = PENDING_PRINCIPAL_VISUAL_REVIEW；本表只报告自动 guardrail 指标。
