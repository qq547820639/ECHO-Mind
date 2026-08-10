# Checklist — 待明确项收尾

## P1 — 麦克风授权证据闭环
- [x] P1: 后端 ingest 对 source=mic_opt 校验 voice_features consent，撤销则 412
- [x] P1: Android MicCollector 监听权限撤回事件，撤回时停止采集
- [x] P1: Android 麦克风开关切换时写入 voice_features consent（含证据哈希）到后端
- [x] P1: 后端测试：无 consent 412 / 有 consent 201 / 撤销后 412
- [x] P1: Android 测试：权限撤回监听停止采集

## P2 — 沙箱算力预算
- [x] P2: config.py 有 sandbox_timeout_seconds/max_concurrent/rate_limit_per_hour 配置
- [x] P2: runner.execute() 超时转 failed（error_message 含 timeout）
- [x] P2: scheduler 同租户并发上限，超限返回 429
- [x] P2: POST /v1/sandbox/runs 每小时速率限制，超限 429
- [x] P2: 后端测试：超时/并发/速率限制三场景

## P3 — 冷启动兜底文案分阶段
- [x] P3: GET /v1/skills 空列表返回 cold_start_hint 字段（按 observation_days 分阶段）
- [x] P3: strings.xml 有 4 档冷启动文案 + 加载失败文案
- [x] P3: TodayScreen/SkillListScreen 区分加载中/失败/空态三态
- [x] P3: fetchSkills 返回三态结果（skills/coldStartHint/loadFailed）
- [x] P3: 后端测试：空列表 cold_start_hint 阶段对应
- [x] P3: Android 测试：4 档文案映射 + 加载失败重试

## P4 — 机构去标识群体画像
- [x] P4: build_tenant_portrait 聚合 mood/observation/active_users/escalation/skill_count
- [x] P4: 小桶（<5）合并到 other 桶
- [x] P4: GET /v1/tenant/portrait 路由可用（admin/professional/auditor）
- [x] P4: 跨租户隔离（只返回本租户聚合）
- [x] P4: 不返回单个用户 ID/特征
- [x] P4: 后端测试：聚合/小桶/隔离/权限

## P5 — 灰度回滚方案
- [x] P5: Tenant 表有 feature_flags JSON 字段（迁移成功）
- [x] P5: 被动感知路由前置 flag 检查，关闭则 410
- [x] P5: GET /v1/config/flags 用户拉取本租户 flags
- [x] P5: PUT /v1/tenant/flags admin 修改 flags
- [x] P5: POST /v1/skills/batch-retire 批量回滚 Skill
- [x] P5: Android 拉取并缓存 feature flags
- [x] P5: PassiveSensingService 启动前检查 flag，关闭则不启动
- [x] P5: Skill 卡片区在 skills_delivery_enabled=false 时显示"已暂停"
- [x] P5: 后端测试：flag 410 / admin 修改 / 拉取 / 批量 retired
