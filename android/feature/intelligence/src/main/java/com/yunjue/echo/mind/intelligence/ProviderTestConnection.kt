package com.yunjue.echo.mind.intelligence

/**
 * v2 §39 — Provider 测试连接（纯数据 + 纯映射，JVM 可测）。
 *
 * 四步：auth（GET /models 健康检查）→ 模型可用性（/models 含配置模型）→
 * 结构化输出（json_object 最小请求）→ 基础请求（同一次调用承载）。
 */
data class TestConnectionResult(
    /** 认证与连通（healthCheck 状态）。 */
    val auth: ProviderStatus,
    /** 模型是否在 /models 列表中（null = 服务未列出模型，无法确认）。 */
    val modelAvailable: Boolean?,
    /** 结构化输出是否可用（最小 json_object 请求返回可解析 JSON）。 */
    val structuredOutputSupported: Boolean?,
    /** 基础请求状态（与结构化输出同一次调用；失败时为错误状态）。 */
    val basicRequest: ProviderStatus,
)

/** 整体结论（供 UI 一句话 + 状态色）。 */
val TestConnectionResult.overall: ProviderStatus
    get() = when {
        auth != ProviderStatus.READY -> auth
        modelAvailable == false -> ProviderStatus.MODEL_NOT_FOUND
        basicRequest != ProviderStatus.READY -> basicRequest
        else -> ProviderStatus.READY
    }

/** 用户可见的一行总结（普通用户人话；禁止 raw stack trace）。 */
fun testConnectionSummary(result: TestConnectionResult): String = when (result.overall) {
    ProviderStatus.READY -> "连接成功，模型可用"
    ProviderStatus.AUTH_FAILED -> "认证失败：API Key 无效或无权访问"
    ProviderStatus.MODEL_NOT_FOUND -> "模型不存在：请检查模型名"
    ProviderStatus.RATE_LIMITED -> "连接正常，但当前被限流（稍后再试）"
    ProviderStatus.QUOTA_EXCEEDED -> "连接正常，但额度已用尽"
    ProviderStatus.NETWORK_ERROR -> "服务不可达：请检查网络或 Base URL"
    ProviderStatus.PROVIDER_ERROR -> "服务暂时不可用"
    ProviderStatus.UNSUPPORTED_CAPABILITY -> "该 Provider 类型暂不支持"
    else -> "尚未测试"
}

/** 逐步明细（Me → Intelligence 展示）。 */
fun testConnectionDetail(result: TestConnectionResult): String = buildString {
    append("认证：${providerStatusText(result.auth)}")
    append("\n模型：${when (result.modelAvailable) {
        true -> "已确认存在"
        false -> "不存在"
        null -> "服务未列出模型，无法确认"
    }}")
    append("\n结构化输出：${when (result.structuredOutputSupported) {
        true -> "支持"
        false -> "不支持（将使用降级链）"
        null -> "未验证"
    }}")
    append("\n基础请求：${providerStatusText(result.basicRequest)}")
}
