# ECHO Mind v0.11.0 Release Notes

> 2026-08-15 · ERA 32 Personal Intelligence 质量升级（自 v0.10.0 起的确定性个人问答与治理收口）。
> 状态：`pilot-candidate`。真机 smoke / 生产签名 / 30 天 dogfood 仍为外部发布门（见 `docs/RELEASE_BASELINE.md`）。

## 产品变化

**问 ECHO 回答得更像「我的 ECHO」**（无 Provider / 离线时同样生效）：

1. **「这个月比上个月更规律了吗？」答对问题本身**——此前错误地答成「最近两周算稳定」；
   现在按月 vs 月「接近通常」占比对比回答，并给出两个月具体数字。
2. **「最近哪几天最像今天？」证据说人话**——工程值「z 距离 4.79」退役，
   改为「更接近今天 → 稍远：日期序列」。
3. **「没有明显差别」也给出可验证的数字**——半年 / 月间 / 周间对比在「很接近」分支
   同样展示两端实际中位数（如「活跃起点 08:49 → 08:51 · 屏幕 254 → 251 分钟」）。
4. **旧上下文不再被当成「这几天」**——用户自述的特殊时期（出差/备考等）超过 14 天未更新，
   回答如实转为「已经结束了，节奏正在回到平时」，证据只陈述已知事实、不编造结束日期。
5. **AI 路径记忆内容人话化**——纠正/确认/上下文内部存储格式不再进入任何 AI Provider 上下文
   （与确定性回答路径同源处理）。

**治理减法（用户不可见，维护成本下降）**：状态文档收敛为 `docs/STATUS.md` /
`docs/RELEASE_BASELINE.md` / `docs/CHANGELOG/`；README 不再手写测试计数
（`scripts/refresh_status_numbers.py` 从实测产物自动生成）；Source Reality 报告多模块自动发现
（修复 NotificationCollector 误报 Missing）；删除 v0.7 遗留零消费函数（进度条/覆盖率文案）。

## 工程与质量

- Android 单元测试全绿（自动计数见 `docs/STATUS.md` §3）+ lint + detekt；
- backend pytest 全绿 + ruff + mypy strict；
- Release 链：SOURCE_MANIFEST 重生成 → 确定性归档双格式验证 → SBOM → provenance
  （APK 内嵌 commit 绑定，signed v2/v3 本地测试密钥）→ artifact manifest → final package §18 终态门禁；
- PersonalAnswerEngine 16 回答族（新增 STABILITY_MONTH_COMPARE），26/26 Core Set 覆盖保持。

## 已知边界（如实声明）

- 真机 Wallpaper 电池/帧率/进程死亡实测：外部门执行（`qa/visual-review/DEVICE_CHECKLIST.md` + 采集脚本就绪）；
- 30 天 dogfood：协议就绪，真实数据回流后验证 Correction Reuse Rate / False Interpretation Rate 等主指标；
- 生产签名与设备矩阵：运营签名环境执行；
- Affective Intelligence：维持契约冻结（affectiveState 恒 null，测试强制）。
