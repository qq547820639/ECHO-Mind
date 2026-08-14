# Android Dependency Graph —— 真实依赖图（ERA 13.2 §48，脚本生成）

> 状态：CURRENT · 本文件由 `scripts/generate_dependency_graph.py` 生成，禁止手写。

> 每轮结构变更后重新生成；CI source-integrity drift gate 强制同步。

## 1. 领域包图（com.yunjue.echo.mind.*，顶层领域聚合）

```text
data ──► memory, observation, presence, root, security
di ──► data, intelligence, presence, root, security
intelligence ──► memory, observation, ports, security
journey ──► data, intelligence, observation, presence, root
me ──► data, memory, observation
memory ──► ports
observation ──► root
ports ──► memory, observation, presence
presence ──► observation, root
runtime ──► data, intelligence, observation, presence, root
ui ──► actions, data, intelligence, journey, me, memory, observation, presence, root
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
| observation | 23 |
| ports | 3 |
| presence | 10 |
| runtime | 1 |
| security | 2 |
| ui | 34 |

## 3. 跨领域边清单

- data → memory
- data → observation
- data → presence
- data → root
- data → security
- di → data
- di → intelligence
- di → presence
- di → root
- di → security
- intelligence → memory
- intelligence → observation
- intelligence → ports
- intelligence → security
- journey → data
- journey → intelligence
- journey → observation
- journey → presence
- journey → root
- me → data
- me → memory
- me → observation
- memory → ports
- observation → root
- ports → memory
- ports → observation
- ports → presence
- presence → observation
- presence → root
- runtime → data
- runtime → intelligence
- runtime → observation
- runtime → presence
- runtime → root
- ui → actions
- ui → data
- ui → intelligence
- ui → journey
- ui → me
- ui → memory
- ui → observation
- ui → presence
- ui → root

## 4. 循环

- 无已知包级循环。

## 5. 边界规则（ArchitectureBoundaryTest + CI 强制）

- observation → intelligence 禁止（Ground Truth 独立）
- presence 渲染层 → Room 禁止
- memory → concrete Provider 禁止
- intelligence → app UI / data 实现禁止（ERA 13.2 §37：只依赖 ports）
- journey/me 应用层 → ui 禁止

