# Document Authority —— 文档权威层级

> 状态：CURRENT · 本文件与 `docs/README_AUTHORITY.md` 同义（后者为详细版），规定后续任何 Agent/协作者的读取优先级。

## 权威顺序（从高到低）

1. **Product Constitution** — `docs/product/ECHO_PRODUCT_CONSTITUTION.md`
2. **Version Source** — `scripts/version_source.json`
3. **Current Contracts** — `PORTRAIT_CONTRACT.md`、`docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md`、`AFFECTIVE_CONTRACT.md`、`docs/providers/AI_PROVIDER_SPEC.md`、`docs/presence/ECHO_PRESENCE_ARCHITECTURE.md`
4. **Architecture Decision Records** — `docs/architecture/ADRS.md`（ADR-001~024+）
5. **Implementation Status** — `docs/IMPLEMENTATION_STATUS.md`（每轮状态锚点）
6. **README** — 当前用户可见产品（与 Version Source 强制一致，CI 断言）
7. **Current technical docs** — `docs/architecture/*`（含 ANDROID_CODE_INVENTORY、ANDROID_DEPENDENCY_GRAPH）、`docs/migrations/*`
8. **Historical specs** — `docs/archive/`、`.trae/specs`、`.workbuddy/`、`.codebuddy/`（仅考古，禁止作为当前要求来源）

## 规则

- 冲突时按上表取高者；代码与文档冲突时**代码是最终事实**；
- README 与 version_source 冲突 → README 错（CI 拦截）；
- `.trae/specs` 等历史目录标注 HISTORICAL，非 Source of Truth；
- 文档生命周期状态：`CURRENT / SUPERSEDED / ARCHIVED / HISTORICAL`。
