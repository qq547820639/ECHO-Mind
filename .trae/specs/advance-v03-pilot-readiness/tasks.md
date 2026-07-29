# Tasks

- [x] Task 1: 角色权限模型扩展：在 backend 补齐 `quality_reviewer`、`security_auditor`、`vendor_support` 角色及权限矩阵强制
  - [x] SubTask 1.1: 扩展 Principal/角色定义，新增三个角色并在路由依赖中注册
  - [x] SubTask 1.2: 强制 tenant_admin 默认不可读心理对话正文、vendor_support 无身份/心理内容权限、security_auditor 只读
  - [x] SubTask 1.3: 高危事件证据访问增加二次认证（step-up token）校验
  - [x] SubTask 1.4: 补充越权访问与二次认证的自动化测试
- [x] Task 2: 风险事件追加式存储与审计表防删改：应用层拒绝删除/更新，并提供数据库级保护
  - [x] SubTask 2.1: 路由层移除/拒绝风险事件删除与更新入口，越权尝试写审计
  - [x] SubTask 2.2: Alembic 迁移中为风险事件与审计表增加触发器/规则禁止 UPDATE/DELETE（PostgreSQL），SQLite 下以应用层兜底
  - [x] SubTask 2.3: 补充删除/篡改被拒绝的测试
- [x] Task 3: SLA 自动升级状态机：60 秒未确认升级第二值班人 → 机构负责人 → 机构链路失效记录
  - [x] SubTask 3.1: 扩展 Escalation 模型：升级层级、各层通知时间戳、机构链路失效标记、送达确认状态
  - [x] SubTask 3.2: 实现升级扫描逻辑（后台任务或可触发端点），区分"已通知"与"已接管"
  - [x] SubTask 3.3: 用户侧状态查询端点：未接管时返回主动拨号入口状态，绝不返回"人工已收到"（未确认时）
  - [x] SubTask 3.4: 升级链路与状态不变量测试（通知≠接管、失效记录可审计）
- [x] Task 4: 工作台完善：红色队列字段、个案复核证据视图、接管记录完整性强制
  - [x] SubTask 4.1: 队列接口与 console.html 补齐：风险类型、等待时间、送达确认、城市、紧急联系人状态、责任人、第二升级联系人
  - [x] SubTask 4.2: 个案复核端点与视图：直接表达、规则依据、分类器结果、量表原始答案、签到趋势、数据质量、历史事件、处置记录
  - [x] SubTask 4.3: 接管/关闭接口强制完整字段（联系方式、是否成功、安全状况、紧急联系人、12356、110/120、处置、随访计划、签名），缺字段拒绝关闭
  - [x] SubTask 4.4: API 级测试覆盖字段完整性与关闭拦截
- [x] Task 5: 危机 12 场景演练自动化脚本：覆盖规定场景、记录规定字段、判定 10 项阻断条件并输出结构化报告
  - [x] SubTask 5.1: 新增 `scripts/crisis_drill.py`，对目标环境执行 12 场景并采集时间戳/版本/降级动作
  - [x] SubTask 5.2: 实现 10 项阻断条件判定（未旁路、生成模型继续发言、事件丢失、虚假送达、无人值班、证据不可见、事件被删、未接管关闭、跨租户、紧急入口崩溃）
  - [x] SubTask 5.3: 输出 JSON/Markdown 演练报告并对本地测试环境跑通一轮
- [x] Task 6: PostgreSQL 备份恢复演练与审计不可变校验工具
  - [x] SubTask 6.1: 新增 `scripts/backup_restore_drill.sh`（或 Python）：备份、校验、隔离恢复、耗时与完整性报告
  - [x] SubTask 6.2: 校验 RPO/RTO 配置与风险队列优先恢复顺序，输出结论
  - [x] SubTask 6.3: docker-compose 增加 pilot 数据库隔离示例配置
- [x] Task 7: 发布门 preflight 扩展：pilot_preflight.py 覆盖 v0.3.0 可自动检查项并生成发布决策记录
  - [x] SubTask 7.1: 增加审计链完整性、角色矩阵、SLA 配置、宣称扫描、内容包哈希、Alembic 回放检查项
  - [x] SubTask 7.2: 生成 v0.3.0 发布门 Go/No-Go 记录（外部依赖项标记为待人工确认）
- [x] Task 8: Android Release 加固配置
  - [x] SubTask 8.1: 增加 Release 网络安全配置（禁止 cleartext HTTP），Debug/Release 清单分离验证
  - [x] SubTask 8.2: 确认 Room schema 导出配置与 ProGuard/R8 规则校验脚本
  - [x] SubTask 8.3: 构建配置静态检查脚本（本环境无 Android SDK，仅做配置级校验）

# Task Dependencies

- [Task 3] depends on [Task 1]（升级链涉及值班角色）
- [Task 4] depends on [Task 1]、[Task 3]（复核视图与队列依赖角色权限和升级状态）
- [Task 5] depends on [Task 3]、[Task 4]（演练需完整危机链路）
- [Task 7] depends on [Task 1]–[Task 6]（汇总检查）
- [Task 2]、[Task 6]、[Task 8] 可并行
