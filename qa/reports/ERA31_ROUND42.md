# ERA 31 Round 42 报告（CI 完整性审计：契约锚点修复 + SOURCE_MANIFEST 重生成）

> 日期：2026-08-15。

## 走查发现

CI 完整性审计（下一次 Release Closure 依赖五套 workflow），本地重放 CI 代理发现：

1. **contract_compliance_check 红**（自 R8 起）：`docs/contracts/CONTRACT_COMPLIANCE.md`
   的 §0 锚点仍指向 R8 下沉前的旧路径
   `android/feature/presence/.../EchoPresenceState.kt`——文件已在 R8 移到
   `core/model`，CI source-integrity 的锚点存在性校验会 FAIL；
2. **verify_source_manifest 红**（自 R11 起）：R11–R41 新增源文件不在
   v0.10.0 时代的 SOURCE_MANIFEST（1019）里——CI source-integrity 对新增文件
   硬失败（这是有意的防漂移门，但 dev 合入新文件后必须重生成清单才能保持绿）。

## 修复

1. CONTRACT_COMPLIANCE.md §0 锚点 → `android/core/model/.../EchoPresenceState.kt`
   （注明 ERA 31 R8 下沉）→ `contract_compliance_check` 24/24 锚点 PASS；
2. `update_release_metadata.py` 重生成 SOURCE_MANIFEST（1019 → **1064**，git 受控
   文件集为唯一事实源）→ `verify_source_manifest` PASS；DELIVERY_MANIFEST.json
   同步刷新（backend 1077/0/1 实测注入）；
3. workflow pins 校验（66 uses 全 SHA 锁定）与其余 CI 脚本引用审计全部通过；
   release 元数据（version/provenance/release_type）未触碰——closure 时仍会
   全量重生成。

## 实测

- contract_compliance_check PASS（24 锚点）；verify_source_manifest PASS（1064）；
- verify_workflow_pins PASS（5 workflow / 66 uses SHA）。