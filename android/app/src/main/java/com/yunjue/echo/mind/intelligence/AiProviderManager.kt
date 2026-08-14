package com.yunjue.echo.mind.intelligence

/**
 * ERA 4 — AI Provider Manager（业务层唯一入口；Model Router 雏形）。
 *
 * - 业务代码不直接接触供应商 SDK，一律经 [EchoReasoningProvider] 抽象；
 * - 社区版第一阶段只有一个 configured provider，但路由抽象已就位
 *   （未来 small/reasoning/embedding/local 分别路由）；
 * - AI 失败不得破坏 ECHO：reason 失败按 [narrativeFallbackFor] 降级链处理。
 */
class AiProviderManager(private val store: ProviderCredentialStore) {

    fun stored(): ProviderCredentialStore.Stored? = store.load()

    fun hasProvider(): Boolean = store.exists()

    /** 从草稿构建 Provider（不落盘）。 */
    fun providerFor(
        type: ProviderType,
        baseUrl: String,
        model: String,
        apiKey: String,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 60,
    ): EchoReasoningProvider = when (type) {
        ProviderType.OPENAI_COMPATIBLE,
        ProviderType.OPENAI,
        ProviderType.CUSTOM_HTTP -> OpenAiCompatibleProvider(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            extraHeaders = extraHeaders,
            timeoutSeconds = timeoutSeconds,
        )
        // ERA 4 实现顺序：OpenAI-compatible 先行；其余类型返回不支持能力
        else -> UnsupportedProvider(type)
    }

    /** 校验草稿连接（不落盘；成功后由调用方决定保存）。 */
    suspend fun validate(draft: ProviderConfigDraft): ProviderHealth {
        val errors = validateProviderConfig(draft)
        if (errors.isNotEmpty()) {
            return ProviderHealth(ProviderStatus.PROVIDER_ERROR, errors.first())
        }
        return providerFor(
            type = draft.providerType,
            baseUrl = normalizeBaseUrl(draft.baseUrl),
            model = draft.model.trim(),
            apiKey = draft.apiKey.trim(),
            extraHeaders = draft.extraHeaders,
            timeoutSeconds = draft.timeoutSeconds,
        ).healthCheck()
    }

    /** 用已保存配置做健康检查；未配置 → NOT_CONFIGURED。 */
    suspend fun healthCheck(): ProviderHealth {
        val stored = store.load() ?: return ProviderHealth(ProviderStatus.NOT_CONFIGURED)
        return providerFor(stored.type, stored.baseUrl, stored.model, stored.apiKey).healthCheck()
    }

    /** 用已保存配置推理；未配置 → NOT_CONFIGURED（上层走 fallback 链）。 */
    suspend fun reason(request: EchoReasoningRequest): EchoReasoningResponse {
        val stored = store.load()
            ?: return EchoReasoningResponse(
                text = "",
                status = ProviderStatus.NOT_CONFIGURED,
                detail = "no provider configured",
            )
        return providerFor(stored.type, stored.baseUrl, stored.model, stored.apiKey).reason(request)
    }

    fun save(stored: ProviderCredentialStore.Stored) = store.save(stored)

    fun clear() = store.clear()
}

/** 未实现 Provider 类型：诚实返回 UNSUPPORTED_CAPABILITY，绝不假装成功。 */
internal class UnsupportedProvider(private val type: ProviderType) : EchoReasoningProvider {
    override suspend fun healthCheck(): ProviderHealth =
        ProviderHealth(ProviderStatus.UNSUPPORTED_CAPABILITY, type.name)

    override suspend fun listModels(): List<ModelDescriptor> = emptyList()

    override suspend fun reason(request: EchoReasoningRequest): EchoReasoningResponse =
        EchoReasoningResponse(
            text = "",
            status = ProviderStatus.UNSUPPORTED_CAPABILITY,
            detail = type.name,
        )
}
