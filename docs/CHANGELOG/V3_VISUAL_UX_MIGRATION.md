# V3 Visual/UX 迁移（视觉语义链 + 渲染层统一 + 熟悉化导航）

> 轮次范围：Visual 语义链唯一事实源 → 渲染层（时钟/facade/AGSL/HDR 诚实化）→ 深色主题 →
> ECHO/Journey/Me 熟悉化导航 → Onboarding typed 状态 → Wallpaper/Dream 时序修复 → 发布诊断脱敏。
> 纪律：所有结论以本仓实测为准；视觉行为差异全部列出（§6）；未做真机实拍（见 STATUS §4）。

## 0. 提交清单

| commit | 内容 |
|---|---|
| `54841f4`→`548b1f4` 系 | observation→composition-root 依赖环消除；CI SDK 对齐 compileSdk 37 |
| `2b71bd9` | Visual 语义链统一：VisualGenomeCompiler 机械编译 + mapper 唯一语义层；Surface/MotionPolicy/RenderQuality 正交分离 |
| `b3e7f94` | EchoRendererFacade + EchoVisualClock（boot-global 相位）+ 全 surface 接入 + Wallpaper/Dream 修复 |
| `222ebe4` | EchoMindTheme 深色单强调色 + edge-to-edge + 发布诊断脱敏（init error/crash） |
| `ed3cd64` | Journey 熟悉化时间导航（时间线/周条/真实月历） |
| `add7270` | ECHO Home 简化：Why 1-tap 内联证据 + Ask 单渲染器标准屏 + AI promo 删除 |
| `f59668d` | Me 分组控制中心（IntelligenceMap 删除）+ Crisis 一键 + Onboarding typed 状态 |

## 1. Visual 语义链（唯一 Presence→Visual 事实源）

- 链路：`EchoVisualMapper.map(state, hour, motionLevel, nightMode, reduceMotion)`（feature:presence）
  → `VisualGenomeCompiler.compile(params, identity)`（core:visual，机械编译，无语义）
  → `EchoVisualGenome`。**GenomeDeriver 已删除**，production main 源码任何位置不得复活
  （`VisualRuntimeV3RegressionTest.singleProductionVisualPipelineNotForked` 静态扫描锁定）。
- 正交策略：`EchoSurface`（+JOURNEY_PRIVATE）/ `MotionPolicy`（motionScaleFor/reducedMotionFor）/
  `defaultQualityFor(surface)` 三轴分离；SurfaceMode 枚举删除；crop 只裁 privacy。

## 2. 渲染层（facade/时钟/环境）

- `EchoVisualClock`（boot-global elapsedRealtimeNanos）：同一 ECHO 在 recompose/导航/可见性切换后
  **不再重启 phase 0**。ticker 只请求帧，时钟由 EchoVisualClock 取值。
- `EchoRendererFacade`：`EchoRenderRequest`/`BackendResolution`/`EchoRenderSession`；
  解析规则 LEGACY→CANVAS，STANDARD/ADVANCED→AGSL（能力缓存，降级带 reason），hdr 恒 false（§T 诚实化：
  真实 HdrCapabilities + headroom + 功耗资格 + 真机验证前不声称 HDR）。
- `EchoRenderEnvironmentState`：进程级 5s TTL 快照（thermal/powerSave/runtimeShader/tier/quality），
  `rememberEchoEnvironment` Compose 轮询；**任何 per-frame 系统服务查询已消除**。
- AGSL：`isAvailable()/isAdvancedAvailable()` 进程级一次探测；shader `iGlowRadius` uniform
  分辨率无关（(2.5+3.5·halo)·minDim/1080，夹 2..6px）。
- 绘制顺序：长丝 GLOW 先、crisp core 后（双后端一致）——唯一有意视觉差异（§6）。

## 3. Wallpaper / Dream 修复

| 项 | 修复 |
|---|---|
| 时钟 | startNanos/System.nanoTime → EchoVisualClock + SystemClock.elapsedRealtime |
| launcher offset | (xOffset−0.5)·0.10 夹 ±0.05 + 每帧 lerp 0.15 |
| viewport | onSurfaceChanged 保存 surfaceW/H，触点归一化用真实视口（弃 displayMetrics） |
| env | 每帧至多一次 5s-TTL 快照；request 仅在输入键变化时重建 |
| Dream | Paint/Typeface init 一次分配；单一复用 invalidate Runnable；每帧单次 LocalDateTime；
  AGSL 可用设备 Dream 走真实材质后端 |

## 4. UI 熟悉化（ONLY ECHO MAY BE UNFAMILIAR）

- **ECHO Home**（EchoSceneScreen 769→7 文件装配/内容分离）：Why=1 tap 内联证据（今日/基线/变化/覆盖
  +「这不符合实际？」纠正入口）；Correction ≤2 taps；Ask=标准全屏（TopAppBar+BackHandler+唯一 56dp
  mini ECHO），Ask 打开时 home organism 不组合（单高成本会话）；AI provider promo 全删除；
  actions 降级为 Ask 下安静入口（空则不渲染）；纠正 chips FlowRow（360dp/fontScale1.5 不溢出）。
- **Journey**：Day=时间线列表（80dp 行，昨天一屏内）；Week=7 天水平条；Month=真实月历
  （YearMonth，28/29/30/31，月前月后惰性空位）；Season/Year=按自然月分组；每尺度单一纵向滚动容器；
  anchor 日期来自 state（composable 内无 LocalDate.now 窗口计算）。
- **Me**：分组控制中心（我的 ECHO/了解我的方式/ECHO 出现在哪里/我的数据/其他，全部 ≤1 tap）；
  MeIntelligenceMap **删除**（`VisualRuntimeV3RegressionTest.meIsGroupedControlCenterWithoutIntelligenceMap`
  锁定不回归）；Crisis FAB 一键直达 SafetyScreen 全屏（12356/110/120 冻结不变）。
- **Onboarding**：`SensingCapabilityStatus{READY,NOT_GRANTED,UNAVAILABLE}` 类型化（字符串嗅探
  `contains("可用")` 删除）；Awakening 帧循环 2200ms 有界退出。
- **主题**：EchoMindTheme 深色单强调色（0xFF0E0F12 背景 / 0xFF8F7CF0 唯一发光留给 ECHO）；
  edge-to-edge + 暗色 system bars；styles.xml 弃 Theme.Material.Light。

## 5. 发布诊断脱敏

- ContainerInitFailedScreen / CrashReportScreen：`isDebugBuild` 参数化；release 仅异常类+稳定错误码+
  API/版本，导出已清洗诊断（无 message/路径/堆栈）；debug 保留原文。`CrashReportSanitizer` 纯函数可测。

## 6. 有意视觉行为差异（全部实测通过视觉门）

1. 长丝绘制顺序（§2）：核心更锐；`qa/visual-review/` PNG 工件随渲染更新重新生成，六项指标门全过。
2. 相位连续（§2）：壁纸/屏保重入不再「重新开始呼吸」。
3. Journey minis 低预算（MINIMAL+JOURNEY_PRIVATE）：缩略图更轻量。
4. 感知关闭降级改为 genome 字段缩放（driftRate×0.30、密度×0.55），语义等价。
5. AGSL glow 分辨率无关；AGSL 设备上 Dream/Lab 导出产出真实 AGSL 渲染（此前恒 Canvas）。
6. 深色主题全局（此前浅色）。

## 7. 门禁（本轮实测）

- Android：testDebugUnitTest 全模块全绿（app 966 含 12 个新 Journey outcome + 23 个 EchoScene
  outcome + 14 个 Me outcome）；lint warningsAsErrors；detekt maxIssues=0；
  assembleDebug（本工作区实测通过）；:app:compileDebugAndroidTestKotlin。
- 新增仪器化门：`AdvancedBackendVisualGate`（API≥33 设备上 AGSL ADVANCED 六项指标门）。
- backend pytest 1084 passed + 1 skipped；wearable node 26 passed + PREFLIGHT PASS。
- `generate_dependency_graph.py`：module_cycle=False，domain_cycle=False，violations=0
  （迁移期间曾出现 root→ui→root 环，已修：MainActivity 对 ui 全限定引用）。
- `generate_source_reality.py`：kt=232, test=160, qa=36, py=69, missing=0, unresolved=0。

## 8. 已知残留（如实）

- 本机 SDK 位于 /tmp/echo-build（易失）；缺失时按本文档 §7 的门禁以 CI android-ci 兜底
  （CI 装 `platforms;android-37`）。本地重建脚本不在仓库内，属环境而非代码问题。
- `AdvancedBackendVisualGate` 为仪器化测试，需 API≥33 真机/模拟器执行，本环境未跑。
- 人眼评审画廊（`qa/visual-review/index.html`）随 §6.1/§6.6 更新需重新作答。
