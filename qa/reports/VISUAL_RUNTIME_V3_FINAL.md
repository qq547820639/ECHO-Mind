# VISUAL_RUNTIME_V3 — FINAL 报告

> IMPLEMENTATION_HEAD=`c4ff17be9a116884b4a6e46ca5c07b6e0c615401`
> 实施分支：`visual-runtime-v3`（20 个 vertical slice commit，逐个可构建）。
> 姊妹报告：SOURCE_REALITY / RENDERER / UI / PERFORMANCE（同目录）。

## 0. 总结论

- **SOFTWARE COMPLETE（软件侧 DoD 全项达成，见 §2 逐项状态）**
- **REAL DEVICE VALIDATION BLOCKED**（无 adb/emulator → BLOCKED_EXTERNAL_ANDROID_DEVICE）
- **BAND10 VALIDATION BLOCKED**（沿用 `BLOCKED_EXTERNAL_BAND10_DEVICE` / `BLOCKED_EXTERNAL_XIAOMI_SDK`；模拟器此前 ERA33 R4 已 SIMULATOR_PASS，本轮 Vela 静态/预检 26/26 + PREFLIGHT PASS）
- **HUMAN VISUAL REVIEW PENDING**（§83 五问；机器代理全绿但不替代人眼）

## 1. Commit 链（§92 顺序）

1. `8c37fbd` build: toolchain（AGP 9.3.1 / Gradle 9.5.0 / compileSdk 37 / BOM 2026.08.00 / JDK17 / Kotlin 2.3.20 保持；全锁文件重生成；含空格路径 dex bug 实测修复）
2. `69880bf` docs: source reality
3. `88ba050` feat(presence/visual): Scene Compiler / RenderPacket
4. `0af3f4f` feat(visual): IdentityTopology + MotionEvaluator
5. `98cfe1f` feat(debug): Visual Lab + metrics（Reference KNOWN Day28 自动门 PASS）
6. `08d7807` feat(visual): AGSL material backend
7. `66f33c2` feat(visual): API36 advanced compositor + WCG/HDR gating
8. `87ca594` feat(echo): ambient ECHO Scene
9. `8bffeca` feat(echo): WHY / Ask / Action progressive surfaces
10. `ab92482` feat(shell): quiet three-world navigation
11. `22f520e` feat(onboarding): same-ECHO presentation
12. `b425ac2` feat(journey): Memory River / constellation
13. `ed4c888` feat(me): Personal Intelligence Map
14. `a0559a3` perf(presence): Wallpaper / Dream scheduler
15. `19bfbd3` feat(wrist): Second Body visual
16. （ULTRA 决策记录：ULTRA_DISABLED_BY_CAPABILITY，见 RENDERER §3——无 Vulkan scaffold，§97 允许）
17. `a4dbb7e` fix(a11y): Reduced Motion / TalkBack / font scale
18. `38ad36a` test: Visual Runtime V3 regression
19. `54f8d72` refactor: delete superseded implementation（注：与 18 互锁，先落地保持逐 commit 绿）
20. 本报告批次（docs: final closure）

## 2. Software DoD 逐项（§96）

| 项 | 状态 |
|---|---|
| 当前 module architecture 保持 / 无不必要新 module | PASS（14 module；`:core:visual`/`:feature:presencevisual` 为当前真实 main 既有） |
| single Current ECHO State 未分叉 | PASS（EchoPresenceState 单一；UI/壁纸/Dream/腕上均只读） |
| single production visual pipeline 未分叉 | PASS（回归锁：唯一 OrganismFrameComputer；旧渲染器文件删除） |
| toolchain migration green | PASS（testDebugUnitTest / assembleDebug / lint / detekt / assembleRelease 实测全绿） |
| deterministic identity tests green | PASS |
| Identity topology 不只靠颜色 | PASS（几何维度锚点测试） |
| 3D depth / occlusion | PASS |
| 三层拓扑 / Fibonacci 粒子 / hollow core | PASS |
| Canvas fallback complete | PASS |
| API33+ AGSL complete | PASS（软件；GPU 实测 BLOCKED_EXTERNAL） |
| API36+ Advanced path complete | PASS（软件；GPU 实测 BLOCKED_EXTERNAL） |
| WCG capability path | PASS（检测+门控） |
| HDR 不作必要条件 | PASS（SDR 默认且 Reference 门全绿；Wallpaper 硬关） |
| Reference KNOWN Day28 自动视觉指标 | PASS（`VisualReferenceGateTest` ALL PASS） |
| Visual Lab + 截图/metrics 导出 | PASS（debug-only 双重门） |
| ECHO READY 非 feed / organism≥52% / Card=0 / chart=0 / KPI=0 | PASS（smoke + 回归锁） |
| Why progressive evidence / Feedback 入 WHY / Ask 保留 live ECHO / Action 入 sheet | PASS |
| 三世界导航保持 / Crisis 可达性不变 | PASS |
| Onboarding 无 DONE / 增强权限未回流 / Awakening same identity / 2200ms / Home 连续 | PASS |
| Journey 无 FilterChip root / Memory River / Month constellation / charts 仅证据层 | PASS |
| Me Intelligence Map / Memory 真实 provenance / Data map 真实 sources | PASS |
| Wallpaper same ECHO / 不可见零绘制 / 自适应调度 | PASS |
| Dream public-safe | PASS（仅时钟/日期/字标白名单 + burn-in offset） |
| Wrist same ECHO / stale→QUIET | PASS（2–4 loops/hollow core/8–12 粒子/chirality/蓝紫族；stale 降级） |
| Reduced Motion / Power·Thermal 降级 / TalkBack / font scale 1.5 | PASS |
| No fake mental metrics / No gamification / No Provider 完整 | PASS（词表/结构断言 + fallback 链不变） |
| unit tests / lint / detekt / assembleDebug 绿 | PASS（全量实测） |
| release build smoke | PASS（assembleRelease 绿，-PECHO_API_BASE_URL=https + 40 位 commit 钉定） |
| visual QA regenerated | PASS（rendered/ 画廊 + organism goldens + 42 帧 FNV 黄金值重生成） |
| wearable software tests green | PASS（node 26/26 + preflight PASS + Android 侧集成测试绿） |
| security gates green | PASS（claim_scan / contract_compliance / contract_drift / dynamic_code / workflow_pins / SOURCE_MANIFEST 1254 一致；§88：renderer 只消费 EchoVisualSpec/Genome——public surface 不触 private memory/narrative/raw 通知/音频/密钥，由类型边界保证） |
| delete review complete | PASS（slice 19；无 V2/V3 并存类残留） |
| final reports generated | PASS（本批四份） |

## 3. 外部门（诚实状态）

| 门 | 状态 |
|---|---|
| Real Android device（DEVICE_CHECKLIST 全清单 / 帧率 / 显存 / 电池 / 热态 / 长跑） | BLOCKED_EXTERNAL_ANDROID_DEVICE |
| Band10 真机 / Xiaomi SDK / 生产签名 / 长跑机时 | BLOCKED_EXTERNAL（沿用 STATUS §4 清单） |
| Human Visual Review（§83 五问） | PENDING_HUMAN_REVIEW |
| ULTRA 启用门（AVP 设备/benchmark/三厂商/电池） | ULTRA_DISABLED_BY_CAPABILITY（成功态） |

## 4. 冻结契约一致性声明

Observation Ground Truth / Personal Intelligence / Affective（恒 null）/ Memory 语义 /
Privacy / Crisis / Wrist 边界 / 三世界 / SAME ECHO：全部未改语义层；
语言禁令词表 CI 门禁绿；无心理 KPI、无 red=bad/green=good、无 AI 生图 runtime visual、
Journey deterministic rebuild（存参数不存图）、无锁屏 hack。
