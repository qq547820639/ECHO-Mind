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
        // normalize baseUrl 与 validate() 保持一致（§39 草稿校验即归一化）
        return providerFor(stored.type, normalizeBaseUrl(stored.baseUrl), stored.model, stored.apiKey).healthCheck()
    }

    /** 用已保存配置推理；未配置 → NOT_CONFIGURED（上层走 fallback 链）。 */
    suspend fun reason(request: EchoReasoningRequest): EchoReasoningResponse {
        val stored = store.load()
            ?: return EchoReasoningResponse(
                text = "",
                status = ProviderStatus.NOT_CONFIGURED,
                detail = "no provider configured",
            )
        // normalize baseUrl 与 validate() 保持一致（§39 草稿校验即归一化）
        return providerFor(stored.type, normalizeBaseUrl(stored.baseUrl), stored.model, stored.apiKey).reason(request)
    }

    /**
     * v2 §39：测试连接（四步：认证 → 模型可用性 → 结构化输出 → 基础请求）。
     * 用草稿或已保存配置；最小请求绝不携带任何个人数据。
     */
    suspend fun testConnection(
        draft: ProviderConfigDraft? = null,
    ): TestConnectionResult {
        val stored = draft?.let { d ->
            ProviderCredentialStore.Stored(
                type = d.providerType,
                displayName = d.displayName,
                baseUrl = normalizeBaseUrl(d.baseUrl),
                model = d.model.trim(),
                apiKey = d.apiKey.trim(),
            )
        } ?: store.load()
        if (stored == null) {
            return TestConnectionResult(
                auth = ProviderStatus.NOT_CONFIGURED,
                modelAvailable = null,
                structuredOutputSupported = null,
                basicRequest = ProviderStatus.NOT_CONFIGURED,
            )
        }
        val provider = providerFor(stored.type, stored.baseUrl, stored.model, stored.apiKey)

        // 1. 认证/连通
        val health = provider.healthCheck()

        // 2. 模型可用性（部分网关不暴露 /models → null=无法确认）
        val models = if (health.status == ProviderStatus.READY) provider.listModels() else emptyList()
        val modelAvailable: Boolean? = when {
            health.status != ProviderStatus.READY -> null
            models.isEmpty() -> null
            else -> models.any { it.id.equals(stored.model, ignoreCase = true) }
        }

        // 3+4. 结构化输出 + 基础请求（一次最小调用，不含任何个人数据）
        val probe = provider.reason(
            EchoReasoningRequest(
                task = "provider_test_connection",
                systemInstruction = "这是连接测试。只输出 JSON：{\"ok\":true}，不要输出其他文字。",
                userContent = "ping",
                outputSchemaHint = "test-connection-v1",
                timeoutSeconds = 30,
                maxTokens = 32,
            )
        )
        val structured: Boolean? = when (probe.status) {
            ProviderStatus.READY -> StructuredOutputValidator.parseNowNarrative(
                probe.structuredJson ?: probe.text
            )?.let { it.statement.isNotBlank() } ?: runCatching {
                org.json.JSONObject(probe.text.trim()).optBoolean("ok")
            }.getOrDefault(false)
            else -> null
        }

        return TestConnectionResult(
            auth = health.status,
            modelAvailable = modelAvailable,
            structuredOutputSupported = structured,
            basicRequest = probe.status,
        )
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
