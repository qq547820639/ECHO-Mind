# ECHO Mind

> 面向成年人的心理健康记录与支持工具 · 由合作机构提供

ECHO Mind 是一款帮助你了解状态并获得支持的小工具。它就像一位安静、耐心的伙伴，通过设备上的被动感知与你的主动授权，帮你看清状态的变化趋势，并在你需要的时候，把你和真正能帮到你的人连接起来。

- 版本：**v0.6.0 工程包**（`pilot-candidate`，未达到生产上线标准）
- 分支：`main`

## 完成度分档说明

以下"已完成"清单按五档区分真实完成度，避免把"类已存在"写成"端到端完成"：

| 档位 | 含义 |
|---|---|
| **implemented** | 代码已实现，具备单元测试覆盖 |
| **integrated** | 已接入真实调用链（生产代码存在调用方），非孤立类定义 |
| **tested** | 通过自动化测试验证（后端 pytest / Android Robolectric 等） |
| **externally validated** | 依赖真实设备/机构/外部系统的验证已完成（如真机矩阵、机构审批） |
| **production-ready** | 达到无人值守生产运行标准 |

## 一、ECHO Mind 是什么？

生活中难免有起伏，但很多状态变化，往往要回过头来才能看清。ECHO Mind 想要做的，就是帮你**看见自己**——用一种简单、不打扰的方式，把每天的状态变化轻轻记录下来，让时间线替你说话。

### v0.6 已完成（按档位）

**Android 主端：**
- **integrated + tested**：被动感知端侧链路（Onboarding consent → 感知采集 → 5 分钟窗口特征提取 → 本地加密落库 → Outbox → 同步上行）。注意：本仓库为基线工程，端侧真实触发依赖前台服务调度，需在真机/模拟器上完成 end-to-end 验证
- **implemented + tested**：统一"数据与感知"中心（被动感知总开关、各权限状态、最近采集/同步时间、离线状态、待同步数量；关闭总开关原子完成本地停止 + 撤回证据上传）
- **implemented + tested**：AI 身份提示、年龄门、机构绑定、版本化同意和 L0 准入流程
- **implemented + tested**：Skill 能力卡片原生执行体验（开始/暂停/停止/完成/中止 → 本地记录 → 服务端上报）；旧版主动录入（签到/日记/量表/练习）入口已停用，仅保留历史只读
- **implemented + tested**：Android Keystore 字段加密、SQLCipher 全库加密、Room v6（原始传感数据不落盘）、离线 Outbox（410 迁移/dead-letter/429 Retry-After/每事件独立处理）
- **implemented + tested**：本地确定性安全规则（仅针对用户主动文本）；12356、110、120 固定入口；未收到服务端 ACK 时不宣称人工已收到
- **implemented + tested**：趋势页七态（加载中/离线缓存/新鲜/部分/无数据/错误/权限关闭），明确区分"没有数据"与"加载失败"；趋势来自设备行为派生特征，不推断情绪

**机构服务端与工作台：**
- **integrated + tested**：FastAPI、PostgreSQL/SQLite、Alembic 迁移（v0.6 代号）和 OpenAPI 契约
- **integrated + tested**：JWT 角色鉴权、租户隔离、同租户幂等、版本化同意和 L0 分流
- **integrated + tested**：被动感知特征摄入（幂等 + consent 门控 + `sources_present` 覆盖字段）、只读画像/叙事（GET 无副作用）、显式画像重建、批量叙事查询
- **implemented + tested**：Skill 治理状态机（draft→reviewed→signed→retired，**仅 signed 下发**）与执行完成上报（幂等）
- **implemented + tested**：L3 事件 `open → acknowledged → taken_over → closed → reviewed` 状态机
- **implemented + tested**：值班 SLA、人工队列、处置记录、复盘和指标
- **implemented + tested**：AES-256-GCM 敏感字段加密；pilot/production 拒绝默认密钥和未加密旧字段
- **integrated + tested**：追加式审计哈希链及完整性验证接口；审计写入 tenant 级串行化（防并发分叉）；request-id 全链一致
- **implemented + tested**：数据主体请求分类删除矩阵（删除派生产物/主动内容，依法保留审计与危机处置记录并去标识）
- **implemented + tested**：沙箱子进程隔离执行（真超时终止 + CPU/内存配额）、租户级执行期并发配额、隐私敏感 feature flag fail-closed 语义

**安全、质量与交付：**
- **tested**：650 条合成红队语料；当前规则包在该合成集上 650/650，**不代表临床效度**
- **tested**：后端自动测试 **924 项全绿**（见 `RELEASE_NOTES_v0.6.0.md`）
- **tested**：内容包校验、宣称扫描、动态代码检查、安全集回归
- **implemented**：后端、Android、安全三条 CI 工作流
- **implemented**：试点责任矩阵、单独同意、PIPIA、Alpha、危机演练、事件响应和 Go/No-Go 模板
- **implemented**：SBOM、文件哈希、交付清单和 Git Bundle

### 它能帮你解决什么问题？

- **状态看不清**：被动感知与能力卡片相结合，趋势图会帮你看出状态变化的规律。
- **想说却不知从何说起**：结构化的能力卡片（Skill）由专业内容治理后下发，让你更容易表达自己的感受。
- **一个人扛着**：当你需要真正的帮助时，它会把你的合作机构中的专业支持人员带到你面前。

### 适合谁使用？

- **18 岁以上的成年人**；
- 由合作机构（如学校、企业、社区机构）提供的心理支持服务的使用者；
- 想用低门槛方式记录和了解自己状态的人。

ECHO Mind 不是"诊断神器"，也不是"聊天机器人陪聊"。它提供的是**记录、趋势回顾、能力练习和人工支持入口**。

> ⚠️ **请一定记住：**
>
> - ECHO Mind **不提供诊断**，也不替代医生或心理专业人员。
> - 任何趋势与筛查提示**不等于**疾病诊断。
> - 被动感知数据仅用于数据覆盖度、活动节律、屏幕互动变化等非诊断行为趋势，**不能**推断情绪、自杀意图或任何精神疾病。
> - ECHO Mind **不是紧急服务**。如果你或你身边的人正处于立即危险之中，请第一时间联系身边可信任的人，或拨打当地紧急电话（110 / 120）。

### v0.6.1 Hardening & UX Closure（本轮收口）

在 v0.6 功能完成基础上做的行为正确性收口，**未新增产品功能**：

- **P0 人工支持客户端闭环**：Android「请求机构支持」→ event_id → 本地持久化 → Outbox → POST /v1/escalations（幂等）；用户侧最小状态（等待送达/已送达/人工已确认/正在接管/已完成），未收到服务端 ACK 前绝不显示"人工已收到"
- **P0 consent 唯一状态机**：`ServiceRevocationCoordinator.revokeService()` 原子协调 停止感知/关麦/清缓冲/全量撤回证据/DSR/UI；OFF→ON 重新产生 granted 证据且同步顺序先于新特征，等待授权同步有明确 UI 态
- **P0 Skill 会话一致性**：`SkillSessionCoordinator` 统一生命周期；进程死亡恢复沿用原 sessionId；single-active-session 在 DB 与代码双重强制；completion 删除真实会话且幂等
- **P0 正式激活码模型**：`ActivationCode`（哈希存储/TTL/一次性/replay/并发原子消费/IP+device rate limit/审计），替代 `User.external_ref` 隐式激活语义（legacy 回退 v0.8 退役）
- **P1 同步状态语义**：`lastSyncAttemptAt`/`lastSuccessfulSyncAt`/`lastPartialSyncAt`/`lastSyncErrorClass`/`pendingCountSnapshot` 拆分，UI 文案区分"最近成功同步/等待网络/N 项待同步/部分未同步/需要重新授权/失败可重试"
- **P1 Onboarding 状态机**：READY_OFFLINE →（服务端 consent ack 核对）→ READY；本地提交幂等位；进程死亡不重头开始
- **P1 趋势七态真实接入**：电池优化限制 + collector heartbeat + 后端 sources_present 真实驱动 NO_DATA 细分（删除不可达的 SERVER_UNAVAILABLE）；行为数据不再包装成"活动量高低"，source code 映射人类可读名且不出现在普通 UI
- **P1 沙箱并发配额**：数据库原子租户执行槽（sandbox_tenant_slots）取代 check-then-act；worker crash 心跳过期回收；SQLite/PG 同语义
- **P2 工程收口**：backend routes.py 按 bounded context 拆 11 个 router（URL/OpenAPI 不变）；escalation 队列 cursor 分页 + metrics SQL 下推；隐私安全结构化遥测；版本/文档一致性自动测试（README/pyproject/versionName/Room v6/OpenAPI/迁移 head）

### 明确未完成的外部发布门

以下事项依赖真实设备、机构和责任主体，不能由代码生成替代；在证据完成前，版本状态只能是 `pilot-candidate`：

- Android SDK 联网全量构建、APK/AAB 签名、模拟器和目标真机矩阵（本机无 JDK，未在本仓库执行）
- 机构 IAM/SSO/MFA、OIDC/OAuth2 生产接入、值班排班、真实通知和第二升级联系人
- 法务、临床、隐私、伦理和网络安全正式审批
- KMS/HSM、生产 PostgreSQL、备份恢复和不可篡改日志存储
- 独立渗透测试、外部红队和值班演练
- 真实用户招募、伦理审批和机构试点

---

## 二、你可以用它做什么？

### 1. 授权被动感知，看见状态节律

完成知情同意后，App 在端侧按 5 分钟窗口聚合活动/屏幕/通知等行为派生特征（原始数据不落盘、不上云），形成你的个人活动节律与数据覆盖度。

### 2. 能力卡片（Skill）

由机构专业人员审签后下发的引导式能力练习（如缓慢呼吸），可随时开始、暂停或停止，执行结果仅记录"开始/完成/停止"状态，不做任何情绪评判。

### 3. 个人趋势回顾

在"趋势"页查看一段时间内的数据覆盖度、活动节律与状态线索变化，而不是只盯着某一天。

### 4. 人工支持入口

当你需要帮助时，可一键向合作机构提交支持请求——请求送达后应用会显示已送达，只有人工确认/接管后才会显示人工已连接。

### 5. 数据权利

你可以随时查看、导出或要求删除自己的数据。删除按数据分类矩阵执行，依法需保留的记录（审计、危机处置）会明确说明保留原因。

### 一个简单的上手示例

> 你打开 App，完成知情同意并授权被动感知。几天后打开趋势页，你看到最近一周的活动节律与屏幕互动变化——你开始留意自己的状态规律，也更有底气地和支持人员聊聊这件事。

---

## 三、快速上手

### 作为普通用户

1. 从合作机构获取安装包和激活信息；
2. 首次打开，完成年龄确认、阅读并同意知情说明；
3. 按引导完成机构绑定与被动感知授权；
4. 在"趋势"页回顾自己的状态变化；
5. 在"能力"页开始由机构审签下发的能力练习；
6. 需要帮助时，使用 App 内的人工支持入口联系机构专业人员。

### 作为开发 / 机构部署人员

请先阅读下方「六、技术安装与运行」，按步骤完成服务端与客户端构建后即可本地体验。

---

## 四、隐私与安全

我们把你的隐私放在第一位，用最朴素的语言告诉你我们是怎么做的：

| 你的关心 | 我们如何回应 |
| --- | --- |
| 我的日记安全吗？ | 敏感字段在端侧与传输中均加密保存（SQLCipher + 字段加密），并记录每一次访问痕迹（审计）。 |
| 我的原始传感数据会"上交"吗？ | **不会**。原始传感数据仅在端侧内存处理，不落盘、不上云；上云的是派生特征摘要与向量。 |
| 会不会在后台偷偷录音？ | **不会**。麦克风是可选模块，**默认关闭**，只有你显式授权才会开启，且原始音频即时处理即丢弃。 |
| 撤销同意会怎样？ | 你可以随时撤回同意，撤回后采集立即停止、本地缓冲清空，并上传撤回证据。 |
| 有谁在看我？ | 完整的人工接管需由机构专业人员进行确认与跟进，每一步都有审计记录。 |

---

## 五、常见问题（FAQ）

**Q1：ECHO Mind 能诊断我有抑郁症吗？**

不能。ECHO Mind 提供记录与趋势提示，任何趋势或提示都不是诊断。诊断需要由专业医生完成。

**Q2：我处于紧急危险中，能用它求助吗？**

请优先联系身边可信任的人或当地紧急服务（110 / 120）。ECHO Mind 是日常支持工具，不是紧急响应服务。

**Q3：我的状态会暴露给机构所有人吗？**

只有负责支持你的专业人员能看到你的相关记录，且每一步操作都有审计记录。

**Q4：我拒绝麦克风授权，还能正常使用吗？**

可以。麦克风是可选项，拒绝它不影响核心功能的使用，你可以放心选择。

**Q5：趋势显示我最近活动变少，是不是很严重？**

不一定。被动行为趋势只是数据线索，不代表诊断。如果你感到担心，请把它作为和专业人员交流的起点，而不是结论。

**Q6：我的数据会保留多久？**

由于涉及数据留存与删除，请以合作机构提供的隐私政策为准，你也可以随时通过 App 内的数据权利入口提出删除请求。

---

## 六、技术安装与运行

> 以下内容面向开发者与机构部署人员。普通终端用户无需操作这些步骤。

### 快速验证服务端

```bash
./scripts/release_preflight.sh
```

### 启动后端（本地体验）

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -e '.[dev]'
cp .env.example .env
python scripts/seed_demo.py
python scripts/create_demo_tokens.py
uvicorn app.main:app --reload --port 8000
```

启动后可以访问：

- API 文档：`http://127.0.0.1:8000/docs`
- 机构工作台：`http://127.0.0.1:8000/console`
- 健康检查：`http://127.0.0.1:8000/health`
- 就绪检查：`http://127.0.0.1:8000/ready`

### 构建 Android 客户端

```bash
cd android
./gradlew test assembleDebug lint
```

Android 构建基线：AGP 8.13.2、Gradle 8.13、Kotlin 2.3.20、KSP 2.3.9、compile/target SDK 36。自带的 `gradlew` 下载后会校验 Gradle 发行包 SHA-256。

模拟器默认 API：`http://10.0.2.2:8000`。真机和试点环境必须改用机构 HTTPS 网关，不得发布明文 HTTP 配置。

### 生产预检

```bash
cd backend
ENVIRONMENT=pilot \
JWT_SECRET='<至少32字符并由密钥系统注入>' \
FIELD_ENCRYPTION_SECRET='<至少32字符并由密钥系统注入>' \
BOOTSTRAP_KEY='<随机高强度值>' \
DATABASE_URL='<机构PostgreSQL连接>' \
python scripts/pilot_preflight.py
```

---

## 七、当前版本与边界

- 当前版本为 **v0.6.0 pilot-candidate（试点就绪）**。在完成真实设备构建、机构审批、渗透测试和试点等外部发布门之前，**不得**标记为生产上线。
- 本包已实现的工程能力包括：Android 主端被动感知链路、Skill 能力闭环、机构服务端与人工接管工作台、确定性的安全规则、字段加密、审计哈希链与回滚、874 项后端自动测试以及三套 CI 门禁。
- 尚未完成的外部发布门（依赖真实设备、机构与责任主体）：装机签名矩阵、机构 IAM/SSO/MFA、法务/临床/隐私/伦理审批、KMS/生产数据库、独立渗透测试、真实用户试点等。

---

## 八、目录速览

```text
android/             Android 手机主端
backend/             FastAPI 服务端、工作台与 Alembic 迁移
content-packs/       量表、能力练习和固定安全脚本
safety-eval/         合成红队语料和评估报告
pilot-pack/          机构试点治理与执行模板
docs/                PRD、架构、API、安全、测试与交付报告
scripts/             本地发布门禁、SBOM、哈希和安全检查
.github/workflows/   CI/CD 门禁
```

---

愿每一天的状态都被温柔地看见。ECHO Mind 与你一起，慢慢来。
