# 机构工作台（/console）可用性自测与修复报告（2026-08-29）

> 验收标准：**可用、好用**——核心业务流程完整跑通、数据流转正确、无控制台报错与死链接、
> 关键交互响应及时且结果可预期、补齐加载/空/错误/成功四态、移动端适配、键盘可达与基本无障碍。
> 方式：真实起服务（uvicorn + SQLite + 种子 42 条事件 + 关联数据），真实 Chromium 逐流程操作埋点观测，
> 并用 puppeteer-core 驱动系统 Chrome 复测移动端视口与全流程。
> 结果：**发现并修复 6 项缺陷（E1–E6）**；backend pytest **1137 passed + 1 skipped**；ruff 0；
> 全程浏览器 **0 控制台报错、0 HTTP 500、0 死链接（含 favicon 404）**。

## 1. 已验证通过的业务闭环（本轮全部复测通过）

| 流程 | 验证点 | 结果 |
|---|---|---|
| 初始空态 | 未连接引导 + 分页按钮默认禁用 | ✅ |
| 连接 | 粘贴 token → 连接 → 会话状态（Bearer / sessionStorage）；无效 token → 友好 401 文案 | ✅ |
| 指标 | professional 可读 P50/P95 与状态分布；on_call 403 → 明确"当前角色无权限" | ✅ |
| 队列 | 42 条加载 + 读屏播报"当前 42 条事件" | ✅ |
| 过滤 | 状态（open=13）/每页条数（25→17 分页往返，页号正确） | ✅ |
| 搜索 | 命中 9 条 / 无命中空态"无匹配：当前页没有命中搜索/过滤条件" | ✅ |
| 确认（ack） | 确认弹窗 → 提交 → 成功横幅 → 列表刷新 → 服务端 ack_at 落库 | ✅ |
| 接管（takeover） | 同 ack 闭环 → takeover_at / assigned_to 落库 | ✅ |
| 关闭（close） | 空必填校验（摘要置顶 + 聚焦首字段）/ 1 字最小长度校验 / 完整提交 → 全字段逐项落库 | ✅ |
| 复盘（review） | 空校验 / 最小长度校验 / 提交 → review_notes 落库 | ✅ |
| 个案复核 | 无 step-up → 明确权限提示；step-up → 9 证据分块完整呈现 | ✅ |
| 数据流转 | UI → API → DB → UI 全链路字段逐项核对（esc_041 单事件完成 接管→关闭→复盘 全链，审计链有序） | ✅ |
| 无死链接 | 页面 0 `<a>`、0 `<img>`；favicon 改内联 SVG data-URI，无 `/favicon.ico` 404 | ✅ |

## 2. 发现并修复的缺陷

| # | 级别 | 缺陷（实测证据） | 修复 |
|---|---|---|---|
| E1 | P1 | 首次连接即失败（401/网络错误）时队列**永久卡在「加载中…」**：`loadQueue` 失败分支先 `renderQueue()` 再 return，此时 `state.loading` 仍为 true 且 rows 为空 → 渲染"加载中"；`finally` 置 false 后无重渲染。上轮 D9 修复未真正闭环 | 失败分支先 `state.loading=false` 再 `renderQueue()`，恢复空态/旧列表，绝不卡加载态 |
| E6 | P1 | 服务端过滤**正常返回空结果**（如负责人过滤到不存在用户）时队列同样卡「加载中…」：成功路径 `renderQueue()` 也在 loading 未清时执行 → 空结果被 loading 态覆盖 | 成功路径先清 `loading` 再渲染；空结果显示"当前无事件" + 分页"共 0 条" |
| E2 | P2 | 状态机拒绝类 409 的 detail 是英文原文（"takeover required before close" / "already closed" / "close required before review"），值班人员无法理解且无下一步指引 | `errorDetail` 增 409 中文映射（含下一步动作指引）；`rawDetail` 增 pydantic 校验数组形式的可读摘要（字段级中文定位） |
| E2b | P2 | close/review 表单仅查非空，1 字输入落到服务端 pydantic 422（英文） | 前端补最小长度校验（`CLOSE_MIN_LEN`=2、复盘 2 字），中文提示"「最终处置」至少 2 个字" |
| E3 | P3 | 页面未声明 favicon → 浏览器请求 `/favicon.ico` → **404 控制台报错**（死请求） | `<head>` 加内联 SVG data-URI favicon（ECHO 呼吸圆点），零外部请求 |
| E4 | P2 | 所有卡片恒显示 确认/接管/关闭/复盘，对 closed/reviewed 等必然失败的状态点击后只能收到 409，"结果不可预期" | 按事件状态禁用必然失败操作（与后端状态机逐条对齐），`disabled` + `title` 说明原因（open/acknowledged 禁关闭与复盘；closed 禁确认/接管/重复关闭；reviewed 禁全部） |
| E5 | P2 | 打开个案复核后点击「清除」（登出），`#caseReview` 区块**仍残留上一段证据/错误内容在屏幕上** | 新增 `resetCaseReview()`，清除会话与切换会话时清空并隐藏该区 |

## 3. 无障碍与键盘验证

- Tab 顺序 = DOM 顺序（token → 连接 → 清除 → 过滤器 → 搜索 → 队列操作），无跳序。
- 模态打开即聚焦主操作（`#confirmOk` / 首个必填字段）；`Esc` 关闭后焦点还原到触发按钮（实测还原）。
- 原生 `<dialog>` 自带焦点陷阱与 `Esc` 关闭；`prefers-reduced-motion` 全局降级。
- 错误提示均 `role=alert`；异步结果经 `role=status` + `aria-live` 播报；禁用按钮以 `disabled` + `title` 说明原因（读屏可读）。
- 关闭表单校验失败：摘要置顶（`role=alert` + `aria-live=assertive`）+ 聚焦首个无效字段并 `scrollIntoView` 居中。

## 4. 移动端适配（puppeteer-core 真实视口 390/320 复测）

| 项 | 390×844 | 320×640 |
|---|---|---|
| 页面横向溢出 | **0px** | **0px** |
| 关闭弹窗 | 359px，**水平居中**（centerOK） | 294px，**水平居中** |
| 弹窗滚动 | scrollH 764 > clientH 715，可滚 | scrollH 764 > clientH 542，可滚 |
| 队列卡片 | 308px 无溢出 | 238px 无溢出 |
| 控制台报错 | **0** | **0** |

## 5. 回归与门禁

- `node --check`（内联 JS 语法）：通过；JS 引用 DOM id 与 HTML 定义**零缺失**。
- backend pytest：**1137 passed + 1 skipped**（本轮无 Python 变更，基线持平）；ruff app/ 0。
- 浏览器全流程（连接/指标/过滤/搜索/分页/确认/接管/关闭/复盘/个案复核/清除）：**0 控制台报错、0 HTTP 500**。
- 单事件全链落库核对：esc_041 接管→关闭→复盘，处置字段（处置/联系方式/联系成功/安全状况/紧急联系人/12356/110·120/随访/签名）逐项与 UI 输入一致；审计链 ack→takeover→close→review 有序。

## 6. 遗留说明（非阻塞）

- on_call 角色指标 403 为产品权限设计（`escalation_metrics` 未纳入 on_call），本轮仍按"如实告知"处理；如需第一值班人看指标应放宽端点并补 RBAC 测试，属产品决策未擅动。
- 提示条位于 header 下方 sticky，滚到页面深处时异步反馈可能不在视野；核心操作的反馈均在模态内可见，风险有限。
- pydantic 422 数组摘要中 msg 字段仍为英文（"String should have at least 2 characters"），已定位到具体字段名；因前端已对常见路径做中文前置校验，此兜底可达且罕见，未再逐条翻译。
