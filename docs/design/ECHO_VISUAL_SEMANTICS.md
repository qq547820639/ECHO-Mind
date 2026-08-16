# ECHO_VISUAL_SEMANTICS — Feature → Visual 映射语义表

> 版本：1.0 · 唯一事实源。任何 `EchoVisualMapper` / renderer 映射改动必须同步本表与单测。
> 原则：**只允许非评价性视觉语义**。所有映射为纯函数，有 unit tests。

## 一、语义分层

ECHO 视觉由四个时间尺度的层叠加，**Identity 永不随 Moment 改变**：

| 层 | 时间尺度 | 决定什么 | 来源 |
|---|---|---|---|
| Identity Genome | 数月恒定 | 色相族、纹理族、拓扑、对称、轨道几何、运动人格 | installation random seed + 长期基线 + 视觉偏好 |
| Life Season | 数周/月 | 慢漂移（drift 下限）、阶段相位 | 画像时间线两半窗口趋势 |
| Daily Composition | 一天 | 当日 flowSpeed / coherence / turbulence / density / dispersion | Identity + 当日 Ambient 向量 |
| Moment State | 分钟/小时 | 呼吸周期、噪声调制 | 当前 Ambient 向量 + 昼夜时刻 |

## 二、映射表（中性 → 视觉）

| Feature（中性行为量） | 视觉语义 | Genome 字段 | 说明 |
|---|---|---|---|
| activity density（事件密度） | 粒子密度 | `particleDensity` | 越密集粒子越多，**不代表好坏** |
| temporal concentration（时间集中度） | 结构相干性 | `coherence` / filament 网络紧密度 | 集中 = 结构更清晰 |
| behavior fragmentation（碎片化） | 局部轨迹离散度 | `dispersion` / filament 抖动 | 碎片 = 轨迹更弥散，**非警告色** |
| baseline-relative magnitude（相对基线偏差） | 轨道几何偏差 | `orbitalEccentricity` / `turbulence` | 与通常不同 = 轨道偏离，**中性** |
| data coverage（数据覆盖） | 视觉清晰度 | `dataClarity` / `luminance` | 覆盖低 = 更轻更模糊 |
| regularity（规律性） | 空间深度 | `depth` | 越规律纵深越稳定 |
| time of day（昼夜） | 环境亮度 | `luminance`（昼夜曲线） | 深夜自动更暗更慢 |
| maturity（成熟度） | 核心开放度 | `coreIntensity` / `structureComplexity` | SEED 闭合 → MATURE 开放 |

## 三、明确禁止的映射

| 禁止 | 原因 |
|---|---|
| red = bad / green = good | 评价性配色，违反宪法 §3 |
| high movement = happy | 心理诊断式，无 Ground Truth |
| late sleep = unhealthy | 心理/医学推断 |
| low activity = depressed | 心理诊断 |
| 任何「情绪分/能量%/专注%」数字 | 无 Ground Truth，宪法 §3 红线 |

## 四、 purity 与测试

- 每个映射为 `(Feature) -> VisualParam` 纯函数，集中在 `EchoVisualMapper`。
- 同一 fixture（固定 seed / presence / clock / surface / viewport）必须重复生成完全相同结果。
- Genome **不包含** emotion / depression / anxiety / health score 等任何心理语义字段。
- UI Screen **不允许**直接根据 Observation 特征决定视觉；必须经 `EchoPresenceState → EchoVisualMapper → EchoVisualGenome → SurfacePolicy → EchoVisualSpec → Renderer`。
