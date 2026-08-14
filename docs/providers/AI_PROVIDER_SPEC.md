# AI Provider Spec

> 状态：v1.0 · 冻结（架构层）· 实现顺序按 ERA 4，但抽象必须从 ERA 1 起预留。
> 位置：`docs/providers/AI_PROVIDER_SPEC.md`

## 1. 核心原则

1. **LLM Provider ≠ ECHO。** Provider 是可替换的 Compute Provider；换 Provider 不失忆、不换人格（ECHO 身份见 Product Constitution §3.1）。
2. **AI 不是启动前置条件。** 无 Provider 时 Observation Intelligence 可用，Deep Reasoning 不可用。
3. **BYOM（Bring Your Own Model）是一等公民**，不只是 BYOK。
4. **Secret 永远留在设备端**：BYOM 默认链路 `Android Device → User-selected Provider`；只有用户明确选择 Server-mediated intelligence 才允许经 ECHO backend。

## 2. Provider 类型（枚举，架构从一开始允许扩展）

```text
ECHO_PROVIDER        官方托管（未来）
OPENAI_COMPATIBLE    社区生态最关键（官方/第三方兼容/自建网关/局域网端点）
OPENAI
ANTHROPIC
GEMINI
CUSTOM_HTTP
LOCAL                设备端本地推理
```

`OPENAI_COMPATIBLE` 配置项：Display Name、Base URL、API Key、Model、Optional headers、Timeout。

## 3. Provider 接口（provider-neutral，业务代码禁止直连供应商 SDK）

```kotlin
interface EchoReasoningProvider {
    suspend fun healthCheck(): ProviderHealth
    suspend fun listModels(): List<ModelDescriptor>
    suspend fun reason(request: EchoReasoningRequest): EchoReasoningResponse
}
```

- `EchoReasoningRequest`：task（见 REASONING_TASKS.md）、compiled context、privacy budget 标识、output schema、timeout。
- `EchoReasoningResponse`：结构化结果 + `evidenceIds` + `confidence` + `interpretationLevel` + 原始 trace（本地诊断用，不上传）。

## 4. Model Capabilities

```text
TEXT_REASONING / STRUCTURED_OUTPUT / TOOL_USE / STREAMING /
VISION / EMBEDDING / LONG_CONTEXT / LOCAL_EXECUTION
```

`EchoModelRouter` 按任务需求选择能力（社区版第一阶段可能只有一个 configured provider，但业务层不绑定）。未来可分别路由 small model / reasoning model / embedding model / local model。

## 5. Provider 状态机（用户可见文案与诊断分离）

```text
NOT_CONFIGURED
VALIDATING
READY
AUTH_FAILED
MODEL_NOT_FOUND
RATE_LIMITED
QUOTA_EXCEEDED
NETWORK_ERROR
PROVIDER_ERROR
UNSUPPORTED_CAPABILITY
```

普通用户显示人话（如「连接正常」「密钥无效，请检查」）；高级诊断才显示技术错误；**禁止把 raw stack trace 给普通用户。**

## 6. Credential 安全（硬性要求）

- 不写源码、不写普通 SharedPreferences、不写日志、不上传 telemetry、不进 crash report、不进数据库 export；
- 默认不进 Android backup；
- UI 默认 masked；提供删除；Provider 切换后清理无用 secret；
- 使用 Android Keystore 保护本地 credential encryption key；不自行设计脆弱 crypto（仓库已有 `AndroidKeystoreFieldCipher` 模式可复用）。

## 7. Secret 隔离

Provider credential 与 EchoMemory / Portrait 数据 / conversation 逻辑隔离。**禁止为了省事建一个巨大 Preferences JSON。**

## 8. Provider UI 分层

- 默认页：`AI Intelligence`：Connected（Claude/GPT/Gemini/Custom）、Model、Status: Ready。
- 高级设置才显示：Base URL、Model ID、Headers、Timeout、Structured output options。
- 入口：`Me → Intelligence → AI Provider`；首次进入 ECHO Scene 后出现**非阻塞**提示「连接一个 AI，让 ECHO 开始更深入地理解你的变化」+「以后再说」。**API Key 输入永远不放在核心授权之前。**

## 9. Community Import / Export

Provider Profile 导出**不含 secret**：

```text
provider type / base url / model / capabilities
```

API key 永不导出。方便社区分享「推荐模型配置」。

## 10. Structured Output

- 定义正式 schema（JSON Schema）；不依赖「希望模型按格式回复」。
- Provider 不支持 native structured output 时：`validation → repair → retry → fallback`。
- 所有 AI 结果必须通过 domain validator（`EchoDomainValidator`）。

## 11. AI Usage Transparency

`AI activity`：provider / model / task / time / categories of data used / result status。默认不展示 raw prompt；高级模式支持本地诊断；API key 永不展示。

## 12. 网络边界提示

Custom Provider 必须提示：「当你使用自定义 AI 服务时，ECHO 为完成当前请求而选择的数据会发送给该服务商。其数据处理规则由对应服务商决定。」允许用户在调用前查看 Data access categories。
