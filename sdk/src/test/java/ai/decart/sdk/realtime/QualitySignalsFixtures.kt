package ai.decart.sdk.realtime

/** Shared builder for [QualitySignals] in connection-quality tests; defaults score GOOD. */
internal fun makeSignals(
    rttMs: Double? = 50.0,
    g2gMs: Double? = null,
    ttffMs: Double? = null,
    upstreamJitterMs: Double? = null,
    fractionLost: Double? = 0.0,
    g2gDropRatio: Double? = null,
    availableOutgoingKbps: Double? = 4_000.0,
    fps: Double? = 30.0,
    freezeCountDelta: Double? = 0.0,
    qualityLimitationReason: String? = "none",
    isRelayed: Boolean = false,
) = QualitySignals(
    rttMs = rttMs,
    g2gMs = g2gMs,
    ttffMs = ttffMs,
    upstreamJitterMs = upstreamJitterMs,
    fractionLost = fractionLost,
    g2gDropRatio = g2gDropRatio,
    availableOutgoingKbps = availableOutgoingKbps,
    fps = fps,
    freezeCountDelta = freezeCountDelta,
    qualityLimitationReason = qualityLimitationReason,
    isRelayed = isRelayed,
)
