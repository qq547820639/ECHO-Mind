package com.yunjue.echo.mind.wearable

import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState

/**
 * WearablePrivacyProjector —— 手环 payload 的隐私边界。
 *
 * Phone: EchoPresenceState (+ optional public evidence) → 隐私投影 → WearPresenceEnvelope。
 * 手环默认 VISUAL_FIRST：publicHeadline 默认为 null，只有用户主动 WHY 时才生成一条
 * 来自固定允许清单的克制表达。
 *
 * NEVER SEND TO BAND（自动测试对每个 payload 做 hard FAIL）：
 * API key / provider credential / provider base URL / raw Memory / Correction 原文 /
 * private Context / private Narrative / raw audio / notification body /
 * precise location / Identity secret seed / 有隐私含义的数据库 ID。
 */
object WearablePrivacyProjector {

    /** 腕上中立氛围状态（由手机 AmbientEngine 映射而来；手环无 AmbientEngine）。 */
    enum class WearAmbientState { QUIET, ACTIVE, DENSE, SLOW, LATE, UNKNOWN }

    /**
     * 公开表达允许清单（全部硬编码；禁止自由文本进入腕上）。
     * 允许的风格：“今天开始得比通常慢一些。” —— 节奏性、中性、无诊断。
     * 禁止：“你最近压力很大。”/“你连续几天异常。”/“你昨晚睡得很糟。”/用户私人 Correction。
     */
    object HeadlineAllowlist {
        const val SEED = "初见。"
        const val DISCOVERING = "我开始看到一些属于你的节奏。"
        const val KNOWN = "我开始认识通常的你了。"
        const val LATE_START = "今天开始得比通常晚一些。"
        const val QUIET_DAY = "今天很安静。"
        const val ACTIVE_DAY = "今天的节奏很活跃。"

        /** 全部允许值（测试断言：任何 headline 必须 ∈ 本集合）。 */
        val ALL: Set<String> = setOf(SEED, DISCOVERING, KNOWN, LATE_START, QUIET_DAY, ACTIVE_DAY)
    }

    /**
     * 生成克制 headline：
     * - 默认（VISUAL_FIRST）→ null；
     * - WHY 请求且 maturity < KNOWN → 学习期中性表达；
     * - WHY 请求且 KNOWN+ → 氛围模板（LATE/QUIET/ACTIVE），其余为 null（宁可没有，不编造）。
     * 永远不使用 publicNarrative / privateNarrative / affectiveState。
     */
    fun headlineFor(
        state: EchoPresenceState,
        ambient: WearAmbientState?,
    ): String? {
        if (state.maturity == EchoMaturity.SEED || state.maturity == EchoMaturity.DISCOVERING ||
            state.maturity == EchoMaturity.EMERGING
        ) {
            return when (state.maturity) {
                EchoMaturity.SEED -> HeadlineAllowlist.SEED
                else -> HeadlineAllowlist.DISCOVERING
            }
        }
        return when (ambient) {
            WearAmbientState.LATE -> HeadlineAllowlist.LATE_START
            WearAmbientState.QUIET -> HeadlineAllowlist.QUIET_DAY
            WearAmbientState.ACTIVE -> HeadlineAllowlist.ACTIVE_DAY
            else -> HeadlineAllowlist.KNOWN
        }
    }

    /** 隐私扫描违规类别（hard FAIL 依据）。 */
    enum class ViolationKind {
        API_KEY,
        PROVIDER_CREDENTIAL,
        PROVIDER_BASE_URL,
        RAW_MEMORY,
        CORRECTION_TEXT,
        PRIVATE_CONTEXT,
        PRIVATE_NARRATIVE,
        RAW_AUDIO,
        NOTIFICATION_BODY,
        PRECISE_LOCATION,
        IDENTITY_SEED,
        UNKNOWN_SENSITIVE_KEY,
    }

    data class PrivacyViolation(
        val kind: ViolationKind,
        val keyOrFragment: String,
    )

    data class PrivacyScanReport(
        val scannedJson: String,
        val violations: List<PrivacyViolation>,
    ) {
        val isPublicSafe: Boolean get() = violations.isEmpty()
    }

    /** 敏感键名黑名单（小写匹配，覆盖 provider 配置/密钥/位置/音频/通知/隐私叙事）。 */
    private val FORBIDDEN_KEY_FRAGMENTS: Map<ViolationKind, List<String>> = mapOf(
        ViolationKind.API_KEY to listOf("apikey", "api_key", "apisecret", "openai_key", "claude_key"),
        ViolationKind.PROVIDER_CREDENTIAL to listOf("credential", "password", "authorization", "access_token", "refresh_token", "client_secret", "bearer"),
        ViolationKind.PROVIDER_BASE_URL to listOf("baseurl", "base_url", "provider_url", "api_endpoint", "endpoint_url"),
        ViolationKind.RAW_MEMORY to listOf("memory_content", "rawmemory", "raw_memory"),
        ViolationKind.CORRECTION_TEXT to listOf("correction_text", "correctiontext"),
        ViolationKind.PRIVATE_CONTEXT to listOf("privatecontext", "private_context"),
        ViolationKind.PRIVATE_NARRATIVE to listOf("privatenarrative", "private_narrative"),
        ViolationKind.RAW_AUDIO to listOf("audio", "rawaudio", "raw_audio"),
        ViolationKind.NOTIFICATION_BODY to listOf("notification", "notifybody"),
        ViolationKind.PRECISE_LOCATION to listOf("latitude", "longitude", "location", "geolocation", "coordinates"),
        ViolationKind.IDENTITY_SEED to listOf("identityseed", "identity_seed", "genomeseed"),
    )

    /** 敏感值模式（值中出现即违规；例如 "sk-…"、"Bearer …"、google api key 前缀）。 */
    private val FORBIDDEN_VALUE_PATTERNS: List<Pair<ViolationKind, Regex>> = listOf(
        ViolationKind.API_KEY to Regex("sk-[A-Za-z0-9]{8,}"),
        ViolationKind.PROVIDER_CREDENTIAL to Regex("Bearer\\s+[A-Za-z0-9._~+/-]{8,}", RegexOption.IGNORE_CASE),
        ViolationKind.API_KEY to Regex("AIza[0-9A-Za-z_-]{20,}"),
    )

    /**
     * 扫描任意 Band payload JSON：
     * - 键名命中黑名单（含嵌套键）→ 违规；
     * - 值命中敏感模式 → 违规；
     * - 结构上不属于 Wear envelope 的键（本 codec 只输出 envelope 键）→ 由 codec 边界保证，
     *   此处额外标记未知敏感键为 UNKNOWN_SENSITIVE_KEY（白名单外防御）。
     */
    fun scanPayload(jsonText: String): PrivacyScanReport {
        val violations = mutableListOf<PrivacyViolation>()
        val root: org.json.JSONObject = try {
            org.json.JSONObject(jsonText)
        } catch (e: Exception) {
            return PrivacyScanReport(jsonText, listOf(PrivacyViolation(ViolationKind.UNKNOWN_SENSITIVE_KEY, "<unparseable>")))
        }
        scanObject(root, violations)
        return PrivacyScanReport(jsonText, violations)
    }

    private fun scanObject(obj: org.json.JSONObject, violations: MutableList<PrivacyViolation>) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val lower = key.lowercase()
            for ((kind, fragments) in FORBIDDEN_KEY_FRAGMENTS) {
                if (fragments.any { lower.contains(it) }) {
                    violations.add(PrivacyViolation(kind, key))
                }
            }
            val value = obj.opt(key)
            when (value) {
                is String -> scanStringValue(value, violations)
                is org.json.JSONObject -> scanObject(value, violations)
                is org.json.JSONArray -> for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    when (item) {
                        is String -> scanStringValue(item, violations)
                        is org.json.JSONObject -> scanObject(item, violations)
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
    }

    private fun scanStringValue(value: String, violations: MutableList<PrivacyViolation>) {
        for ((kind, pattern) in FORBIDDEN_VALUE_PATTERNS) {
            if (pattern.containsMatchIn(value)) {
                violations.add(PrivacyViolation(kind, value.take(24)))
            }
        }
    }

    /**
     * 腕上可用动作集合（同一 Action Runtime 的动作名；仅 BREATHING / PAUSE）。
     * 由手机 EchoActionRuntime 当前可用性决定，不由手环自创。
     */
    fun availableActions(breathingAvailable: Boolean, pauseAvailable: Boolean): List<String> {
        val actions = mutableListOf<String>()
        if (breathingAvailable) actions.add(WearActionCommand.START_BREATHING.name)
        if (pauseAvailable) actions.add(WearActionCommand.START_PAUSE.name)
        return actions
    }
}
