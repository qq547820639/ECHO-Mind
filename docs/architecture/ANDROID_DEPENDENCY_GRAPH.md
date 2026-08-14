# Android Dependency Graph —— 实际依赖图（ERA 12 收口）

> 状态：CURRENT · 每轮结构变更后更新。此图描述**包级实际依赖**（由源码 import 推导 + ArchitectureBoundaryTest 断言），是 ERA 13 物理模块化的输入。

## 1. 领域包图（com.yunjue.echo.mind.*）

```text
ui（ui.app 概念上）
 ├── ui.echo ──────────────┐
 ├── ui.journey ───────────┤
 ├── ui.me ────────────────┼──► presence（EchoSceneRenderers/EchoLifeField）
 └── ui（Shell/Onboarding）┘

presence ──► sensing（六态枚举）、model、localportrait（PresenceRepository 在 data 包）
intelligence ──► model、memory（MemoryType）、sensing（无：仅枚举经 presence）？
memory ──► data.MemoryRepository（领域层薄，仓储在 data）
actions ──► 纯（无 Android 依赖）
journey ──► model（Portrait DTO 装配）、presence（EchoVisualParameters）
runtime ──► sensing、presence、data.PresenceRepository、intelligence.AiProviderManager
data ──► 全部领域（仓储实现层，Android/Room/网络）
security ──► 纯 Android（Keystore）
```

## 2. 依赖方向断言（ArchitectureBoundaryTest，CI 强制）

| 规则 | 断言 | 状态 |
|---|---|---|
| presence 渲染层不依赖 Room/数据库 | presence/*.kt 不含 androidx.room / EchoDatabase / MemoryDao | ✅ |
| presence 服务不依赖 ui | presence/*.kt 不含 com.yunjue.echo.mind.ui | ✅（渲染器已归位 presence） |
| observation 不依赖 intelligence/affective | sensing/*、localportrait/* 不含 intelligence/affective | ✅ |
| memory 不依赖 Provider/intelligence | memory/* 不含 intelligence | ✅ |
| intelligence 不依赖 ui | intelligence/* 不含 com.yunjue.echo.mind.ui | ✅ |

## 3. 循环

- 无已知包级循环（architecture tests + source scan 验证）。

## 4. 无效依赖（已修复）

- ~~presence → ui（EchoSceneRenderers 原在 ui 包）~~ → 渲染器归位 presence（ERA 12 批次 1）
- ~~ui.me → ui.SupportScreen 私有组件~~ → SupportScreen 删除，子领域独立（ERA 12 批次 2）

## 5. ERA 13 模块化候选（按依赖图）

1. `:feature:presence`：依赖 model + sensing 枚举；无 Android 反向依赖；被 ui/presence 服务使用——**第一个拆分候选**；
2. `:feature:intelligence`：依赖 model + memory 枚举；provider 实现依赖 security；
3. `:feature:memory`（领域）+ data 实现暂留 app 模块；
4. 拆之前：data 包仓储实现与领域接口分离（Repository abstraction，v3.1 §3）。

## 6. 边界说明

- `data/` 目前是**实现层**（Room/网络/存储），领域模型分布在 model/presence/intelligence/memory/journey/actions；
- 模块化时按「领域接口 → core → 实现」顺序切分，不暴力搬包。
