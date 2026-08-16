package com.yunjue.echo.mind.wearable

import org.json.JSONObject

/**
 * Wear Protocol v1 编解码（org.json —— 与项目现行序列化约定一致）。
 *
 * 纪律（ECHO_WRIST_CONTRACT §Protocol）：
 * - forward compatible：未知字段（当前字段与未来字段）一律忽略，不报错；
 * - 缺少必填字段 → [DecodeResult.Malformed]；
 * - 缺少可选字段 → 默认值；
 * - schemaVersion > 当前支持 → [DecodeResult.UnsupportedSchema]（拒绝执行，不猜语义）；
 * - malformed JSON → [DecodeResult.Malformed]。
 *
 * 时钟偏移与重复/乱序由 [WearableRuntime] 与 [WearablePolicy] 处理（codec 无状态）。
 */
object WearMessageCodec {

    private const val TYPE_PRESENCE = "presence"
    private const val TYPE_OBSERVATION = "observation"
    private const val TYPE_ACTION = "action"
    private const val TYPE_ACK = "ack"
    private const val TYPE_CAPABILITY = "capability"

    sealed class DecodeResult {
        data class Ok(val message: WearMessage) : DecodeResult()
        data class Malformed(val reason: String) : DecodeResult()
        data class UnsupportedSchema(val schemaVersion: Int) : DecodeResult()
    }

    fun encode(message: WearMessage): String = when (message) {
        is WearMessage.Presence -> encodePresence(message.envelope).toString()
        is WearMessage.Observation -> encodeObservation(message.envelope).toString()
        is WearMessage.Action -> encodeAction(message.envelope).toString()
        is WearMessage.Ack -> encodeAck(message.envelope).toString()
        is WearMessage.Capability -> encodeCapability(message.envelope).toString()
    }

    fun decode(text: String): DecodeResult {
        val root: JSONObject = try {
            JSONObject(text)
        } catch (e: Exception) {
            return DecodeResult.Malformed("malformed JSON: ${e.message}")
        }
        val type = root.optString("type", "")
        val schemaVersion = root.readInt("schemaVersion")
            ?: return DecodeResult.Malformed("missing or non-numeric schemaVersion")
        if (schemaVersion > WEAR_SCHEMA_CURRENT) {
            return DecodeResult.UnsupportedSchema(schemaVersion)
        }
        return when (type) {
            TYPE_PRESENCE -> decodePresence(root, schemaVersion)
            TYPE_OBSERVATION -> decodeObservation(root, schemaVersion)
            TYPE_ACTION -> decodeAction(root, schemaVersion)
            TYPE_ACK -> decodeAck(root, schemaVersion)
            TYPE_CAPABILITY -> decodeCapability(root, schemaVersion)
            else -> DecodeResult.Malformed("unknown message type: $type")
        }
    }

    // ------------------------------------------------------------------ encode

    private fun encodePresence(e: WearPresenceEnvelope): JSONObject {
        val identity = JSONObject().apply {
            put("topology", e.identity.topology.toDouble())
            put("symmetry", e.identity.symmetry.toDouble())
            put("orbit", e.identity.orbit.toDouble())
            put("motion", e.identity.motion.toDouble())
            put("texture", e.identity.texture)
            put("colorFamily", e.identity.colorFamily)
            put("accent", e.identity.accent.toDouble())
        }
        val moment = JSONObject().apply {
            put("flow", e.moment.flow.toDouble())
            put("coherence", e.moment.coherence.toDouble())
            put("density", e.moment.density.toDouble())
            put("turbulence", e.moment.turbulence.toDouble())
            put("brightness", e.moment.brightness.toDouble())
        }
        val surface = JSONObject().apply {
            put("motionLevel", e.surface.motionLevel)
            put("lowPower", e.surface.lowPower)
            put("reducedMotion", e.surface.reducedMotion)
            put("motionSummaryEnabled", e.surface.motionSummaryEnabled)
        }
        return envelopeBase(TYPE_PRESENCE, e).apply {
            put("revision", e.revision)
            put("expiresAt", e.expiresAt)
            put("maturity", e.maturity)
            put("identity", identity)
            put("moment", moment)
            put("surface", surface)
            if (e.publicHeadline != null) put("publicHeadline", e.publicHeadline)
            if (e.availableActions.isNotEmpty()) {
                put("availableActions", org.json.JSONArray(e.availableActions))
            }
        }
    }

    private fun encodeObservation(e: WearObservationEnvelope): JSONObject {
        val obj = envelopeBase(TYPE_OBSERVATION, e)
        e.motion?.let { m ->
            val motion = JSONObject().apply {
                if (m.motionEnergy != null) put("motionEnergy", m.motionEnergy.toDouble())
                put("movementClass", m.movementClass)
                put("sampleCoverage", m.sampleCoverage.toDouble())
                put("quality", m.quality)
                put("windowStartMs", m.windowStartMs)
                put("windowEndMs", m.windowEndMs)
            }
            obj.put("motion", motion)
        }
        e.deviceState?.let { d ->
            val state = JSONObject().apply {
                put("wearing", d.wearing)
                put("sleep", d.sleep)
                if (d.batteryPercent != null) put("batteryPercent", d.batteryPercent)
                if (d.charging != null) put("charging", d.charging)
            }
            obj.put("deviceState", state)
        }
        return obj
    }

    private fun encodeAction(e: WearActionEnvelope): JSONObject =
        envelopeBase(TYPE_ACTION, e).apply { put("command", e.command) }

    private fun encodeAck(e: WearAckEnvelope): JSONObject =
        envelopeBase(TYPE_ACK, e).apply {
            put("ackFor", e.ackFor)
            put("revision", e.revision)
            put("status", e.status)
        }

    private fun encodeCapability(e: WearCapabilityEnvelope): JSONObject =
        envelopeBase(TYPE_CAPABILITY, e).apply {
            put("protocolVersion", e.protocolVersion)
            put("screenWidth", e.screenWidth)
            put("screenHeight", e.screenHeight)
            if (e.capabilities.isNotEmpty()) {
                put("capabilities", JSONObject(e.capabilities))
            }
        }

    private fun envelopeBase(type: String, e: WearEnvelope): JSONObject = JSONObject().apply {
        put("type", type)
        put("schemaVersion", e.schemaVersion)
        put("messageId", e.messageId)
        put("generatedAt", e.generatedAt)
        put("source", e.source.name)
    }

    // ------------------------------------------------------------------ decode

    private fun decodePresence(root: JSONObject, schemaVersion: Int): DecodeResult {
        val messageId = root.requireString("messageId") ?: return DecodeResult.Malformed("presence missing messageId")
        val generatedAt = root.readLong("generatedAt") ?: return DecodeResult.Malformed("presence missing generatedAt")
        val source = root.requireString("source") ?: return DecodeResult.Malformed("presence missing source")
        val revision = root.readLong("revision") ?: return DecodeResult.Malformed("presence missing revision")
        val expiresAt = root.readLong("expiresAt") ?: return DecodeResult.Malformed("presence missing expiresAt")
        val maturity = root.requireString("maturity") ?: return DecodeResult.Malformed("presence missing maturity")

        val identityJson = root.optJSONObject("identity") ?: return DecodeResult.Malformed("presence missing identity")
        val identity = WearIdentityProjection(
            topology = identityJson.readFloat("topology") ?: return DecodeResult.Malformed("identity missing topology"),
            symmetry = identityJson.readFloat("symmetry") ?: return DecodeResult.Malformed("identity missing symmetry"),
            orbit = identityJson.readFloat("orbit") ?: return DecodeResult.Malformed("identity missing orbit"),
            motion = identityJson.readFloat("motion") ?: return DecodeResult.Malformed("identity missing motion"),
            texture = identityJson.readInt("texture") ?: return DecodeResult.Malformed("identity missing texture"),
            colorFamily = identityJson.readInt("colorFamily") ?: return DecodeResult.Malformed("identity missing colorFamily"),
            accent = identityJson.readFloat("accent") ?: return DecodeResult.Malformed("identity missing accent"),
        )
        val momentJson = root.optJSONObject("moment") ?: return DecodeResult.Malformed("presence missing moment")
        val moment = WearMomentProjection(
            flow = momentJson.readFloat("flow") ?: return DecodeResult.Malformed("moment missing flow"),
            coherence = momentJson.readFloat("coherence") ?: return DecodeResult.Malformed("moment missing coherence"),
            density = momentJson.readFloat("density") ?: return DecodeResult.Malformed("moment missing density"),
            turbulence = momentJson.readFloat("turbulence") ?: return DecodeResult.Malformed("moment missing turbulence"),
            brightness = momentJson.readFloat("brightness") ?: return DecodeResult.Malformed("moment missing brightness"),
        )
        val surfaceJson = root.optJSONObject("surface") ?: return DecodeResult.Malformed("presence missing surface")
        val surface = WearSurfaceParams(
            motionLevel = surfaceJson.optString("motionLevel", "DEFAULT"),
            lowPower = surfaceJson.optBoolean("lowPower", false),
            reducedMotion = surfaceJson.optBoolean("reducedMotion", false),
            motionSummaryEnabled = surfaceJson.optBoolean("motionSummaryEnabled", false),
        )
        val envelope = WearPresenceEnvelope(
            schemaVersion = schemaVersion,
            messageId = messageId,
            generatedAt = generatedAt,
            source = parseSource(source),
            revision = revision,
            expiresAt = expiresAt,
            maturity = maturity,
            identity = identity,
            moment = moment,
            surface = surface,
            publicHeadline = root.optStringOrNull("publicHeadline"),
            availableActions = root.optStringList("availableActions"),
        )
        return DecodeResult.Ok(WearMessage.Presence(envelope))
    }

    private fun decodeObservation(root: JSONObject, schemaVersion: Int): DecodeResult {
        val messageId = root.requireString("messageId") ?: return DecodeResult.Malformed("observation missing messageId")
        val generatedAt = root.readLong("generatedAt") ?: return DecodeResult.Malformed("observation missing generatedAt")
        val source = root.requireString("source") ?: return DecodeResult.Malformed("observation missing source")

        var motion: WearMotionSummary? = null
        root.optJSONObject("motion")?.let { m ->
            val coverage = m.readFloat("sampleCoverage")
                ?: return DecodeResult.Malformed("motion missing sampleCoverage")
            val start = m.readLong("windowStartMs") ?: return DecodeResult.Malformed("motion missing windowStartMs")
            val end = m.readLong("windowEndMs") ?: return DecodeResult.Malformed("motion missing windowEndMs")
            motion = WearMotionSummary(
                motionEnergy = m.readFloatOrNull("motionEnergy"),
                movementClass = m.optString("movementClass", "UNKNOWN"),
                sampleCoverage = coverage,
                quality = m.optString("quality", "UNKNOWN"),
                windowStartMs = start,
                windowEndMs = end,
            )
        }
        var deviceState: WearDeviceStateSnapshot? = null
        root.optJSONObject("deviceState")?.let { d ->
            deviceState = WearDeviceStateSnapshot(
                wearing = d.optString("wearing", "UNKNOWN"),
                sleep = d.optString("sleep", "UNKNOWN"),
                batteryPercent = d.readIntOrNull("batteryPercent"),
                charging = if (d.has("charging")) d.optBoolean("charging") else null,
            )
        }
        val envelope = WearObservationEnvelope(
            schemaVersion = schemaVersion,
            messageId = messageId,
            generatedAt = generatedAt,
            source = parseSource(source),
            motion = motion,
            deviceState = deviceState,
        )
        return DecodeResult.Ok(WearMessage.Observation(envelope))
    }

    private fun decodeAction(root: JSONObject, schemaVersion: Int): DecodeResult {
        val messageId = root.requireString("messageId") ?: return DecodeResult.Malformed("action missing messageId")
        val generatedAt = root.readLong("generatedAt") ?: return DecodeResult.Malformed("action missing generatedAt")
        val source = root.requireString("source") ?: return DecodeResult.Malformed("action missing source")
        val command = root.requireString("command") ?: return DecodeResult.Malformed("action missing command")
        val envelope = WearActionEnvelope(
            schemaVersion = schemaVersion,
            messageId = messageId,
            generatedAt = generatedAt,
            source = parseSource(source),
            command = command,
        )
        return DecodeResult.Ok(WearMessage.Action(envelope))
    }

    private fun decodeAck(root: JSONObject, schemaVersion: Int): DecodeResult {
        val messageId = root.requireString("messageId") ?: return DecodeResult.Malformed("ack missing messageId")
        val generatedAt = root.readLong("generatedAt") ?: return DecodeResult.Malformed("ack missing generatedAt")
        val source = root.requireString("source") ?: return DecodeResult.Malformed("ack missing source")
        val ackFor = root.requireString("ackFor") ?: return DecodeResult.Malformed("ack missing ackFor")
        val status = root.requireString("status") ?: return DecodeResult.Malformed("ack missing status")
        val envelope = WearAckEnvelope(
            schemaVersion = schemaVersion,
            messageId = messageId,
            generatedAt = generatedAt,
            source = parseSource(source),
            ackFor = ackFor,
            revision = root.readLong("revision") ?: 0L,
            status = status,
        )
        return DecodeResult.Ok(WearMessage.Ack(envelope))
    }

    private fun decodeCapability(root: JSONObject, schemaVersion: Int): DecodeResult {
        val messageId = root.requireString("messageId") ?: return DecodeResult.Malformed("capability missing messageId")
        val generatedAt = root.readLong("generatedAt") ?: return DecodeResult.Malformed("capability missing generatedAt")
        val source = root.requireString("source") ?: return DecodeResult.Malformed("capability missing source")
        val screenWidth = root.readInt("screenWidth") ?: return DecodeResult.Malformed("capability missing screenWidth")
        val screenHeight = root.readInt("screenHeight") ?: return DecodeResult.Malformed("capability missing screenHeight")
        val caps = mutableMapOf<String, String>()
        root.optJSONObject("capabilities")?.let { c ->
            val keys = c.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = c.opt(k)
                if (v is String) caps[k] = v
            }
        }
        val envelope = WearCapabilityEnvelope(
            schemaVersion = schemaVersion,
            messageId = messageId,
            generatedAt = generatedAt,
            source = parseSource(source),
            protocolVersion = root.readInt("protocolVersion") ?: WEAR_SCHEMA_CURRENT,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            capabilities = caps,
        )
        return DecodeResult.Ok(WearMessage.Capability(envelope))
    }
}

private fun parseSource(raw: String): WearMessageSource =
    WearMessageSource.entries.firstOrNull { it.name == raw } ?: WearMessageSource.XIAOMI_BAND

// ------------------------------------------------------------------ JSON helpers

/** 必填字符串：缺失或非字符串 → null。 */
private fun JSONObject.requireString(name: String): String? {
    if (!has(name)) return null
    val value = opt(name) ?: return null
    return value as? String
}

private fun JSONObject.readLong(name: String): Long? = readNumber(name)?.toLong()
private fun JSONObject.readInt(name: String): Int? = readNumber(name)?.toInt()
private fun JSONObject.readIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) readInt(name) else null
private fun JSONObject.readFloat(name: String): Float? = readNumber(name)?.toFloat()
private fun JSONObject.readFloatOrNull(name: String): Float? =
    if (has(name) && !isNull(name)) readFloat(name) else null

private fun JSONObject.readNumber(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = opt(name) ?: return null
    return (value as? Number)?.toDouble()
}

/** 可选字符串：缺失或 null → null；非字符串 → null（宽容，保持 forward compatible）。 */
private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) opt(name) as? String else null

private fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val v = array.opt(i)
        if (v is String) out.add(v)
    }
    return out
}
