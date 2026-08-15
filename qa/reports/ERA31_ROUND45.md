# ERA 31 Round 45 报告（security-ci + backend coverage 门禁复核：五套 CI 全绿收官）

> 日期：2026-08-15。

## 背景

R42–R44 依次修复并重放了 source-integrity；本轮补上 security-ci 与
backend-ci 的 coverage 门禁本地等价检查，完成五套 CI 全绿收官。

## 复核结果（全部 PASS）

1. **security-ci 依赖审计**：`scripts/audit_dependencies.py` —— pip-audit
   「No known vulnerabilities found」（rc=0）；osv-scanner 本机未安装
   （脚本如实标记本地豁免，CI 强制执行）；
2. **backend-ci coverage 门禁**：`pytest -m "not sqlite_only" --cov=app
   --cov-fail-under=70` —— **94.03%**（要求 ≥70），1062 passed；
3. 其余门禁历轮已复核：android-ci（每轮全量）、backend-ci 其余步骤（R33 预检）、
   source-integrity（R42–R44）、release-closure 依赖（R41 assembleRelease）。

## 五套 CI 本地等价状态

| workflow | 本地等价检查 | 状态 |
|---|---|---|
| android-ci | testDebugUnitTest 1032 + assembleDebug + lintDebug + detekt | ✅ |
| backend-ci | pytest 1077+1 + ruff + mypy + coverage 94% + alembic + 静态脚本 + openapi + 契约漂移 + 故障注入 | ✅ |
| security-ci | compileDebugKotlin + pip-audit 0 漏洞 | ✅ |
| source-integrity | pins 66 + manifest 1064 + 契约锚点 24 + reality/graph 漂移门 + 归档双验证 + 10/10 | ✅ |
| release-closure | 脚本在位 + assembleRelease/R8 通过 | ✅ |

## 实测

- 本轮无代码变更（纯复核轮）；全部门禁绿色。