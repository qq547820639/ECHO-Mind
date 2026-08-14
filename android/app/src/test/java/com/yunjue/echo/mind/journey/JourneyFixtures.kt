package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.presence.EchoIdentityGenome
import com.yunjue.echo.mind.presence.EchoMaturity
import com.yunjue.echo.mind.presence.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoVisualParameters
import java.time.Instant

/**
 * ERA 16 测试共用夹具：确定性构造 JourneyDay / EchoVisualParameters / JourneyCanonicalDay。
 */

/** 12 维视觉参数测试构造器（detekt LongParameterList 阈值 12 → builder 风格）。 */
internal class VisualParamsBuilder {
    var flowSpeed: Float = 0.4f
    var coherence: Float = 0.5f
    var turbulence: Float = 0.2f
    var particleDensity: Float = 0.4f
    var coreOpenness: Float = 0.5f
    var dispersion: Float = 0.4f
    var pulsePeriodSeconds: Float = 5f
    var depth: Float = 0.5f
    var brightness: Float = 0.7f
    var contrast: Float = 0.4f
    var accentIntensity: Float = 0.5f
    var structureComplexity: Float = 0.5f

    fun build() = EchoVisualParameters(
        flowSpeed = flowSpeed,
        coherence = coherence,
        turbulence = turbulence,
        particleDensity = particleDensity,
        coreOpenness = coreOpenness,
        dispersion = dispersion,
        pulsePeriodSeconds = pulsePeriodSeconds,
        depth = depth,
        brightness = brightness,
        contrast = contrast,
        accentIntensity = accentIntensity,
        structureComplexity = structureComplexity,
    )
}

internal fun visualParams(build: VisualParamsBuilder.() -> Unit = {}): EchoVisualParameters =
    VisualParamsBuilder().apply(build).build()

internal fun journeyDay(
    date: String,
    params: EchoVisualParameters? = visualParams(),
    dimensionValues: Map<String, String> = mapOf("RHYTHM" to "SIMILAR"),
) = JourneyDay(
    date = date,
    baselineDays = 10,
    headline = "接近",
    summary = "今天和平时很接近。",
    dimensionValues = dimensionValues,
    visualParams = params,
)

internal fun identityGenome(seed: Long = 42L) = EchoIdentityGenome(
    seed = seed,
    accentHue = 0.5f,
    colorFamily = 2,
    textureFamily = 1,
    coreTopology = 0.6f,
    symmetryTendency = 0.7f,
    orbitGeometry = 0.4f,
    motionPersonality = 0.5f,
)

internal fun canonicalDay(
    date: String,
    seed: Long = 42L,
    params: EchoVisualParameters = visualParams(),
) = JourneyCanonicalDay(
    date = date,
    visualSeed = seed,
    visualParams = params,
    identityReference = identityGenome(seed),
    maturity = EchoMaturity.KNOWN,
    keyEvidenceIds = listOf("portrait:$date"),
    createdAtEpochMs = 1L,
)

internal fun presenceState(seed: Long = 42L): EchoPresenceState = EchoPresenceState(
    updatedAt = Instant.EPOCH,
    maturity = EchoMaturity.KNOWN,
    identityGenome = identityGenome(seed),
)
