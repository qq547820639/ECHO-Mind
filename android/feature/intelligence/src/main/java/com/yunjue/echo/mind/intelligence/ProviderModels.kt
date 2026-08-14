package com.yunjue.echo.mind.intelligence

/**
 * ERA 4 — AI Provider 领域模型（纯 Kotlin，无 Android 依赖）。
 *
 * 产品宪法：**LLM Provider ≠ ECHO**。Provider 是可替换的 Compute Provider；
 * 业务代码禁止直连供应商 SDK，一律走 [EchoReasoningProvider] 抽象。
 * BYOM 默认网络链路：Android Device → User-selected Provider（ECHO server 不知道 API Key）。
 */

/** Provider 类型（架构从一开始允许扩展；实现顺序见 AI_PROVIDER_SPEC）。 */
enum class ProviderType { ECHO_PROVIDER, OPENAI_COMPATIBLE, OPENAI, ANTHROPIC, GEMINI, CUSTOM_HTTP, LOCAL }

/** 模型能力（EchoModelRouter 按任务需求选择）。 */
enum class ModelCapability {
    TEXT_REASONING,
    STRUCTURED_OUTPUT,
    TOOL_USE,
    STREAMING,
    VISION,
    EMBEDDING,
    LONG_CONTEXT,
    LOCAL_EXECUTION,
}

/** Provider 状态机（普通用户显示人话，禁止 raw stack trace）。 */
enum class ProviderStatus {
    NOT_CONFIGURED,
    VALIDATING,
    READY,
    AUTH_FAILED,
    MODEL_NOT_FOUND,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    NETWORK_ERROR,
    PROVIDER_ERROR,
    UNSUPPORTED_CAPABILITY,
}

/** 用户可见状态文案（单测锚点；UI 不得另行硬编码）。 */
fun providerStatusText(status: ProviderStatus): String = when (status) {
    ProviderStatus.NOT_CONFIGURED -> "尚未连接 AI"
    ProviderStatus.VALIDATING -> "正在验证连接…"
    ProviderStatus.READY -> "连接正常"
    ProviderStatus.AUTH_FAILED -> "密钥无效，请检查后重试"
    ProviderStatus.MODEL_NOT_FOUND -> "模型不存在，请检查模型名"
    ProviderStatus.RATE_LIMITED -> "请求太频繁，稍后再试"
    ProviderStatus.QUOTA_EXCEEDED -> "额度已用尽"
    ProviderStatus.NETWORK_ERROR -> "网络不可用，请稍后再试"
    ProviderStatus.PROVIDER_ERROR -> "AI 服务暂时不可用"
    ProviderStatus.UNSUPPORTED_CAPABILITY -> "当前模型不支持此能力"
}

data class ModelDescriptor(
    val id: String,
    val displayName: String? = null,
    val capabilities: Set<ModelCapability> = emptySet(),
)

data class ProviderHealth(
    val status: ProviderStatus,
    val detail: String? = null,
)

/** typed reasoning task（见 REASONING_TASKS.md；ERA 5 由 Context Compiler 填充内容）。 */
data class EchoReasoningRequest(
    val task: String,
    val systemInstruction: String,
    val userContent: String,
    val outputSchemaHint: String? = null,
    val timeoutSeconds: Int = 60,
    val maxTokens: Int = 1024,
)

data class EchoReasoningResponse(
    val text: String,
    val structuredJson: String? = null,
    val model: String? = null,
    val status: ProviderStatus,
    val detail: String? = null,
)

/** provider-neutral 推理接口（业务代码唯一入口）。 */
interface EchoReasoningProvider {
    suspend fun healthCheck(): ProviderHealth
    suspend fun listModels(): List<ModelDescriptor>
    suspend fun reason(request: EchoReasoningRequest): EchoReasoningResponse
}

/** AI 失败降级链（Master Prompt PART 76：AI 失败不得破坏 ECHO）。 */
enum class NarrativeFallbackLevel { AI_NARRATIVE, DETERMINISTIC_NARRATIVE, OBSERVATION_FACTS }

/**
 * Provider 失败 → 降级层级（纯函数）：
 * READY 才允许 AI 叙事；其余一律降级到确定性叙事；
 * AUTH_FAILED / NOT_CONFIGURED（无 Provider）→ 最底层的观察事实（确定性模板仍可用）。
 */
fun narrativeFallbackFor(status: ProviderStatus): NarrativeFallbackLevel = when (status) {
    ProviderStatus.READY -> NarrativeFallbackLevel.AI_NARRATIVE
    ProviderStatus.RATE_LIMITED,
    ProviderStatus.QUOTA_EXCEEDED,
    ProviderStatus.NETWORK_ERROR,
    ProviderStatus.PROVIDER_ERROR,
    ProviderStatus.MODEL_NOT_FOUND,
    ProviderStatus.UNSUPPORTED_CAPABILITY,
    ProviderStatus.VALIDATING -> NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE
    ProviderStatus.AUTH_FAILED,
    ProviderStatus.NOT_CONFIGURED -> NarrativeFallbackLevel.OBSERVATION_FACTS
}
