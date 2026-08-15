# ERA 31 Round 36 报告（验收证据总表：十道门 × 五时点 → 轮次与证据）

> 日期：2026-08-15。

## 内容

新增 `qa/reports/ERA31_FELT_ACCEPTANCE.md`——把 PART 2 十道门与
FINAL ACCEPTANCE 五时点（Day 0/7/30/90/180）逐条映射到 R1–R35 的交付轮次
与可复核证据（机器测试 + 人眼画廊 + 真机清单），回答最终唯一标准：

> 用户用了半年以后，看到手机上的那个生命形态时，自然认为「这是我的 ECHO」。

三类证据链：
1. **机器**：Android 1031 全绿 + detekt + lint；backend 1077+1 + ruff + mypy；
   LOCAL PREFLIGHT PASSED（R33）；
2. **人眼**：`qa/visual-review/index.html`（不同用户/连续性/36s 运动/真实帧率拼图）；
3. **真机**：DEVICE_CHECKLIST.md（R35 对齐当前 UX）。

## 实测

- 文档轮（无代码变更）；门禁保持全绿。