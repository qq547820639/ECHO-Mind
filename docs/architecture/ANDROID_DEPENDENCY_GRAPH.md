# Android Dependency Graph —— 真实依赖图（ERA 13.2 §48，脚本生成）

> 状态：CURRENT · 本文件由 `scripts/generate_dependency_graph.py` 生成，禁止手写。

> 每轮结构变更后重新生成；CI source-integrity drift gate 强制同步。

## 1. 领域包图（com.yunjue.echo.mind.*，顶层领域聚合）

```text
data ──► AppPreferences, BuildConfig, EchoMindApplication, PassiveSensingPrefs, memory, observation, presence, security
di ──► AppPreferences, PassiveSensingPrefs, data, intelligence, presence, security
intelligence ──► memory, observation, ports, security
journey ──► AppPreferences, data, intelligence, observation, presence
me ──► data, memory, observation
memory ──► ports
observation ──► AppPreferences, EchoMindApplication, PassiveSensingPrefs, enqueueSync
ports ──► memory, observation, presence
presence ──► AppPreferences, observation
runtime ──► AppPreferences, PassiveSensingPrefs, data, intelligence, observation, presence
ui ──► AppContainer, AppPreferences, R, actions, data, intelligence, journey, me, memory, observation, presence
```

## 2. 领域文件数（实测）

| 领域 | Kotlin 文件数 |
|---|---|
| actions | 2 |
| data | 25 |
| di | 1 |
| intelligence | 13 |
| journey | 7 |
| me | 3 |
| memory | 2 |
| observation | 21 |
| ports | 3 |
| presence | 7 |
| runtime | 1 |
| security | 2 |
| ui | 34 |

## 3. 跨领域边清单

- data → AppPreferences
- data → BuildConfig
- data → EchoMindApplication
- data → PassiveSensingPrefs
- data → memory
- data → observation
- data → presence
- data → security
- di → AppPreferences
- di → PassiveSensingPrefs
- di → data
- di → intelligence
- di → presence
- di → security
- intelligence → memory
- intelligence → observation
- intelligence → ports
- intelligence → security
- journey → AppPreferences
- journey → data
- journey → intelligence
- journey → observation
- journey → presence
- me → data
- me → memory
- me → observation
- memory → ports
- observation → AppPreferences
- observation → EchoMindApplication
- observation → PassiveSensingPrefs
- observation → enqueueSync
- ports → memory
- ports → observation
- ports → presence
- presence → AppPreferences
- presence → observation
- runtime → AppPreferences
- runtime → PassiveSensingPrefs
- runtime → data
- runtime → intelligence
- runtime → observation
- runtime → presence
- ui → AppContainer
- ui → AppPreferences
- ui → R
- ui → actions
- ui → data
- ui → intelligence
- ui → journey
- ui → me
- ui → memory
- ui → observation
- ui → presence

## 4. 循环

- 无已知包级循环。

## 5. 边界规则（ArchitectureBoundaryTest + CI 强制）

- observation → intelligence 禁止（Ground Truth 独立）
- presence 渲染层 → Room 禁止
- memory → concrete Provider 禁止
- intelligence → app UI / data 实现禁止（ERA 13.2 §37：只依赖 ports）
- journey/me 应用层 → ui 禁止

