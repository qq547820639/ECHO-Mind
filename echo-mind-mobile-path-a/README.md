# ECHO Mind 手机 App 主端（Path A）

版本：**v0.2.0 试点就绪工程包**
分支：`feat/pilot-readiness-v0.2`

这是面向 18 岁以上成人的机构版心理健康支持工程基线，覆盖 Android 主端、最小机构服务端、人工接管工作台、确定性安全规则、版本化量表与内容、字段加密、审计、测试和试点治理材料。

> 产品只提供记录、筛查提示、趋势回顾、审核练习、人工支持和转介。它不是诊断工具，不替代医生或心理专业人员，也不是紧急服务。

## v0.2 已实现

### Android 主端

- AI 身份提示、年龄门、机构绑定、版本化同意和 L0 准入流程
- 每日签到、情绪日记、PHQ-9/GAD-7、审核练习、7/14/28 日趋势和数据权利入口
- Android Keystore 字段加密、Room v2、离线 Outbox、高优先级风险同步
- 本地确定性安全规则；红色信号冻结普通交互并切换固定安全脚本
- 12356、110、120 固定入口；未收到服务端 ACK 时不宣称人工已收到

### 机构服务端与工作台

- FastAPI、PostgreSQL/SQLite、Alembic 基线迁移和 OpenAPI 契约
- JWT 角色鉴权、租户隔离、同租户幂等、版本化同意和 L0 分流
- 签到、日记修订链、量表、练习、趋势、数据主体请求
- L3 事件 `open → acknowledged → taken_over → closed → reviewed` 状态机
- 值班 SLA、人工队列、处置记录、复盘和指标
- AES-256-GCM 敏感字段加密；pilot/production 拒绝默认密钥和未加密旧字段
- 追加式审计哈希链及完整性验证接口

### 安全、质量与交付

- 650 条合成红队语料；当前规则包在该合成集上 650/650，**不代表临床效度**
- 671 项后端自动测试通过
- 内容包校验、宣称扫描、动态代码检查、安全集回归
- 后端、Android、安全三条 CI 工作流
- 试点责任矩阵、单独同意、PIPIA、Alpha、危机演练、事件响应和 Go/No-Go 模板
- SBOM、文件哈希、交付清单和 Git Bundle

## 明确未完成的外部发布门

以下事项依赖真实设备、机构和责任主体，不能由代码生成替代：

- Android SDK 联网全量构建、APK/AAB 签名、模拟器和目标真机矩阵
- 机构 IAM/SSO/MFA、值班排班、真实通知和第二升级联系人
- 法务、临床、隐私、伦理和网络安全正式审批
- KMS/HSM、生产 PostgreSQL、备份恢复和不可篡改日志存储
- 独立渗透测试、外部红队和值班演练
- 真实用户招募、伦理审批和机构试点

在上述证据完成前，版本状态只能是 `pilot-candidate`，不得标记为生产上线。

## 快速验证

```bash
./scripts/release_preflight.sh
```

单独启动后端：

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

- API 文档：`http://127.0.0.1:8000/docs`
- 机构工作台：`http://127.0.0.1:8000/console`
- 健康检查：`http://127.0.0.1:8000/health`
- 就绪检查：`http://127.0.0.1:8000/ready`

## Android 构建

```bash
cd android
./gradlew test assembleDebug lint
```

Android 构建基线：AGP 8.13.2、Gradle 8.13、Kotlin 2.3.20、KSP 2.3.9、compile/target SDK 36。自带的 `gradlew` 下载后校验 Gradle 发行包 SHA-256。

模拟器默认 API：`http://10.0.2.2:8000`。真机和试点环境必须改用机构 HTTPS 网关，不得发布明文 HTTP 配置。

## 生产预检

```bash
cd backend
ENVIRONMENT=pilot \
JWT_SECRET='<至少32字符并由密钥系统注入>' \
FIELD_ENCRYPTION_SECRET='<至少32字符并由密钥系统注入>' \
BOOTSTRAP_KEY='<随机高强度值>' \
DATABASE_URL='<机构PostgreSQL连接>' \
python scripts/pilot_preflight.py
```

## 目录

```text
android/             Android 手机主端
backend/             FastAPI 服务端、工作台和 Alembic
content-packs/       量表、审核练习和固定安全脚本
safety-eval/         合成红队语料和评估报告
pilot-pack/          机构试点治理与执行模板
docs/                PRD、架构、API、安全、测试与交付报告
scripts/             本地发布门禁、SBOM、哈希和安全检查
.github/workflows/   CI/CD 门禁
```
