# ECHO Mind v0.3.0 机构试点就绪 Spec

## Why

v0.2.0 代码候选版已完成软件基线，但距 30–50 人机构试点（v0.3.0）仍有关键缺口：SLA 自动升级链不完整、工作台个案证据展示不充分、角色权限模型未覆盖全部试点角色、危机 12 场景演练缺少自动化工具、PostgreSQL 备份恢复与发布门检查缺少脚本化验证。本变更在不扩大功能范围的前提下，消除可在代码仓库内完成的阻断项。

## What Changes

- **SLA 自动升级状态机**：事件创建 → 60 秒未确认通知第二值班人 → 再超时通知机构负责人 → 持续显示用户主动拨号入口并记录机构链路失效；明确"通知已发送 ≠ 人工已接管"。
- **工作台完善**：红色队列补齐风险类型/等待时间/送达确认/城市/紧急联系人状态/责任人/第二升级联系人字段；新增个案复核视图（直接表达、规则依据、分类器结果、量表原始答案、签到趋势、数据质量、历史事件、处置记录），禁止只显示单一"AI 风险分数"；接管记录强制完整字段（确认/接管时间、联系方式、是否成功、安全状况、紧急联系人、12356 转介、110/120、最终处置、随访计划、人工签名）。
- **角色权限扩展**：补齐 `quality_reviewer`、`security_auditor`、`vendor_support` 角色；高危数据访问二次认证；机构管理员默认不可读心理对话正文；厂商支持默认无身份/心理内容权限；审计员只读；风险事件禁止删除（追加式）。
- **危机演练自动化**：针对 12 类场景的可重复演练脚本，自动记录测试账号、场景编号、触发输入、规则版本、App 版本、各时间戳、降级动作与误导性检查；10 项阻断条件自动判定。
- **备份恢复与发布门工具**：PostgreSQL 备份/恢复演练脚本与 RPO/RTO 校验、审计表不可更新删除的数据库级强制、`pilot_preflight.py` 扩展覆盖 v0.3.0 发布门中可自动检查项。
- **Android Release 加固**：Release 禁止 HTTP 的网络安全配置、Room schema 导出确认、ProGuard/R8 规则验证脚本。
- **外部依赖（不在本仓库实现，仅跟踪）**：真实 Android SDK 构建与签名、KMS/HSM 接入、四套隔离环境部署、渗透测试、机构签约培训、内容临床/法务签署。

## Impact

- Affected specs: 危机协议与人工接管 SOP、工作台、权限与租户隔离、发布与试点清单
- Affected code:
  - [routes.py](file:///workspace/echo-mind-mobile-path-a/backend/app/api/routes.py)
  - [models.py](file:///workspace/echo-mind-mobile-path-a/backend/app/models.py)
  - [auth.py](file:///workspace/echo-mind-mobile-path-a/backend/app/auth.py)
  - [console.html](file:///workspace/echo-mind-mobile-path-a/backend/app/templates/console.html)
  - [pilot_preflight.py](file:///workspace/echo-mind-mobile-path-a/backend/scripts/pilot_preflight.py)
  - [android/app/build.gradle.kts](file:///workspace/echo-mind-mobile-path-a/android/app/build.gradle.kts)
  - `scripts/`（新增演练与备份恢复脚本）

## ADDED Requirements

### Requirement: SLA 自动升级状态机

系统 SHALL 在红色事件创建后按 SLA 自动升级：60 秒未确认通知第二值班人员，继续超时通知机构负责人，并在全程保持用户主动拨号入口可见。系统 MUST NOT 将"通知已发送"视为"人工已接管"；只有值班人员显式确认才进入已接管状态。机构链路完全失效应作为事件记录并可审计。

#### Scenario: 值班人员及时确认

- **WHEN** 红色事件创建后 60 秒内值班人员执行确认
- **THEN** 事件进入已确认状态，不触发第二值班人和机构负责人通知

#### Scenario: 第一值班人超时

- **WHEN** 红色事件创建 60 秒后仍无人确认
- **THEN** 系统通知第二值班人员并记录升级时间戳，事件状态仍为未接管

#### Scenario: 机构链路完全失效

- **WHEN** 第二值班人和机构负责人均超时未确认
- **THEN** 系统记录机构链路失效事件，用户侧持续显示主动拨号入口，且任何界面不得显示"人工已收到"

### Requirement: 工作台个案复核证据视图

系统 SHALL 为专业人员在个案复核页展示完整证据链：用户直接表达、规则触发依据、安全分类器结果、PHQ-9/GAD-7 原始答案、最近签到趋势、数据质量、历史风险事件、人工处置记录。系统 MUST NOT 仅展示单一 AI 风险分数。

#### Scenario: 打开个案复核

- **WHEN** 专业人员打开任一红色事件个案复核页
- **THEN** 页面同时呈现上述全部证据区块，且每区块可追溯到原始数据

### Requirement: 接管记录完整性

系统 SHALL 要求接管记录包含：确认时间、接管时间、联系方式、是否联系成功、当前安全状况、是否联系紧急联系人、是否转介 12356、是否联系 110/120、最终处置、后续随访计划、人工签名。字段缺失时 MUST NOT 允许关闭事件。

#### Scenario: 缺字段关闭被拒绝

- **WHEN** 值班人员尝试关闭一起接管记录缺少必填字段的事件
- **THEN** 系统拒绝关闭并指出缺失字段

### Requirement: 完整角色权限模型

系统 SHALL 支持 `user / duty_staff / professional / clinical_lead / quality_reviewer / tenant_admin / security_auditor / vendor_support` 角色，并强制：用户仅访问自身记录；值班人员仅访问当前高危事件必要信息；机构管理员默认不可读心理对话正文；厂商支持默认无身份和心理内容权限；审计员只读；高危数据访问需二次认证；风险事件追加式存储、任何角色不得删除。

#### Scenario: 越权访问被拒绝

- **WHEN** tenant_admin 尝试读取用户心理对话正文，或 vendor_support 尝试访问任何心理内容
- **THEN** 请求被拒绝并写入审计日志

#### Scenario: 风险事件删除被拒绝

- **WHEN** 任何角色（含 tenant_admin）尝试删除风险事件
- **THEN** 操作被数据库或应用层拒绝并记录审计

### Requirement: 危机 12 场景演练自动化

系统 SHALL 提供可重复执行的演练脚本，覆盖 12 类场景（明确自杀意念、具体计划、已备工具、现实危险发生中、他伤、命令性幻听、妄想、"只是开玩笑"、断网、服务端宕机、值班未登录、求助电话不可达），自动记录规定字段并判定 10 项阻断条件。任一阻断项失败 MUST 输出禁止进入试点的结论。

#### Scenario: 演练报告生成

- **WHEN** 对 staging 环境执行完整演练脚本
- **THEN** 生成含全部场景记录字段与阻断条件判定结果的结构化报告

### Requirement: 备份恢复与发布门自动检查

系统 SHALL 提供 PostgreSQL 备份、备份校验、时间点恢复演练脚本，校验 RPO ≤24 小时、RTO ≤4 小时且风险队列优先恢复；并扩展 pilot_preflight 覆盖 v0.3.0 发布门中可自动检查项（审计链完整性、角色矩阵、SLA 配置、宣称扫描、内容包哈希、迁移回放）。

#### Scenario: 恢复演练

- **WHEN** 在隔离环境执行恢复演练脚本
- **THEN** 输出恢复耗时、数据完整性校验和风险队列可用性结论

## MODIFIED Requirements

### Requirement: 红色队列展示

原演示版队列 SHALL 扩展为展示：风险类型、等待时间、当前状态、是否确认送达、用户所在城市、紧急联系人可用状态、机构责任人、第二升级联系人。无服务端确认送达的事件 MUST 明确标识为未送达，不得显示"人工已收到"。

### Requirement: Android Release 构建配置

Release 构建 SHALL 禁止明文 HTTP（网络安全配置），SHALL 导出 Room schema，SHALL 通过 ProGuard/R8 规则校验脚本验证；Debug 与 Release 网络配置 MUST 隔离。

## REMOVED Requirements

无。范围冻结原则不变：不包含手环、iOS、摄像头、开放式陪聊、自动诊断、自动治疗方案。
