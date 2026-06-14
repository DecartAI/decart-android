package ai.decart.sdk.realtime

import ai.decart.sdk.RealtimeModel

/**
 * SDK-only connectivity preflight — run before [RealTimeClient.connect] to
 * decide whether to show the integration. Spins up a throwaway
 * `PeerConnection` against public STUN (no session, no inference) to check
 * whether WebRTC can leave the network over UDP and roughly how laggy the path
 * is. It does not measure throughput — use the in-session
 * [ConnectionQualityReport] signal for that.
 */
enum class ConnectivityTransport {
    /** Direct UDP works (a server-reflexive candidate was gathered). */
    UDP,

    /** Will need TURN — only non-srflx candidates gathered (unverified, SDK-only). */
    RELAY,

    /** No connectivity — no ICE candidates gathered. */
    FAILED,
}

data class ConnectivityMetrics(
    val transport: ConnectivityTransport,
    /** Approximate network round-trip time (ms) — STUN time-to-first-candidate, or real RTT in deep mode. */
    val rttMs: Long?,
    /** Deep-probe only: measured mid-stream (steady-state) glass-to-glass latency (ms), or null. */
    val g2gMs: Double? = null,
    /** Deep-probe only: time-to-first-frame (ms) — startup latency to the first rendered model frame, or null. */
    val ttffMs: Double? = null,
    /** Deep-probe only: end-to-end frame drop ratio (0–1), or null. */
    val g2gDropRatio: Double? = null,
    /** Deep-probe only: server's view of upstream jitter (ms), or null. */
    val upstreamJitterMs: Long? = null,
    /** Deep-probe only: server-reported upstream packet loss (0–1), or null. */
    val packetLoss: Double? = null,
    /** Deep-probe only: number of glass-to-glass samples collected. */
    val sampleCount: Int? = null,
)

data class ConnectivityReport(
    /** Pre-connect quality on the same scale as the in-session signal — you decide what to do. */
    val quality: ConnectionQuality,
    val metrics: ConnectivityMetrics,
    /** Human-readable explanations for any non-GOOD verdict. */
    val reasons: List<String>,
)

data class CheckConnectivityOptions(
    /** Override the ICE servers used for the (default, STUN-only) probe. */
    val iceServers: List<String> = PreflightConfig.DEFAULT_STUN_URLS,
    /** Abort candidate gathering after this long (STUN-only probe). */
    val iceGatherTimeoutMs: Long = PreflightConfig.ICE_GATHER_TIMEOUT_MS,
    /**
     * Opt-in deep probe: instead of the STUN-only network check, briefly open a
     * real session with a synthetic source, measure true glass-to-glass latency,
     * then tear it down. Requires [model]. Costs a short GPU session.
     */
    val deep: Boolean = false,
    /** Required when [deep]: the realtime model to probe (latency is model-specific). */
    val model: RealtimeModel? = null,
    /** Deep-probe duration (ms). Defaults to config. */
    val durationMs: Long? = null,
)

internal data class PreflightRttThresholds(val goodMs: Long, val marginalMs: Long)

internal object PreflightConfig {
    /** Public STUN servers used to gather server-reflexive candidates. */
    val DEFAULT_STUN_URLS = listOf("stun:stun.l.google.com:19302")

    /** Abort candidate gathering after this long. */
    const val ICE_GATHER_TIMEOUT_MS = 5_000L

    /** RTT bands (ms) for the preflight verdict. */
    val RTT_THRESHOLDS = PreflightRttThresholds(goodMs = 150L, marginalMs = 300L)

    /**
     * Deep probe: open a real session with a synthetic source + pixel-marker
     * measurement, then tear it down. Duration must cover TTFF (~4–5s) + mid-stream
     * warm-up (~2s) before steady-state samples accrue; resolves early once
     * [ACTIVE_MIN_SAMPLES] exist.
     */
    const val ACTIVE_DURATION_MS = 12_000L
    const val ACTIVE_MIN_SAMPLES = 5

    /**
     * Budget for session establishment, on top of the sampling window. The probe
     * is hard-capped at `durationMs + this` so a preflight gate can't grind through
     * the full reconnect/backoff cycle.
     */
    const val ACTIVE_CONNECT_BUDGET_MS = 20_000L
}

/** Map probe metrics to a connectivity quality verdict. Pure. */
internal fun classifyConnectivity(
    metrics: ConnectivityMetrics,
    thresholds: PreflightRttThresholds,
): ConnectivityReport {
    val reasons = mutableListOf<String>()
    val rttMs = metrics.rttMs
    val quality = when {
        metrics.transport == ConnectivityTransport.FAILED -> {
            reasons += "Could not establish any WebRTC connectivity (no ICE candidates gathered). " +
                "Real-time streaming is unlikely to work on this network."
            ConnectionQuality.CRITICAL
        }
        metrics.transport == ConnectivityTransport.RELAY -> {
            reasons += "Direct UDP connectivity could not be confirmed; the session will need a TURN " +
                "relay, which adds latency and can't be verified without starting a session."
            ConnectionQuality.POOR
        }
        rttMs != null && rttMs > thresholds.marginalMs -> {
            reasons += "Network round-trip time is high (~${rttMs}ms > ${thresholds.marginalMs}ms); " +
                "the real-time experience may feel laggy."
            ConnectionQuality.POOR
        }
        rttMs != null && rttMs > thresholds.goodMs -> {
            reasons += "Network round-trip time is elevated (~${rttMs}ms > ${thresholds.goodMs}ms)."
            ConnectionQuality.FAIR
        }
        else -> ConnectionQuality.GOOD
    }

    return ConnectivityReport(quality = quality, metrics = metrics, reasons = reasons)
}

/** Render a 0..1 fraction as a percentage, trimming trailing zeros (0.001 → "0.1", 0.03 → "3"). */
private fun formatPercent(fraction: Double): String =
    "%.1f".format(fraction * 100).removeSuffix(".0")

/**
 * Classify a deep-probe result. Judges startup (TTFF) and steady-state
 * (mid-stream glass-to-glass) latency separately — both are real experienced
 * latency on different scales — and folds in drops + upstream loss. Falls back
 * to RTT only when neither latency could be measured. Pure. Reuses the in-session
 * [ConnectionQualityThresholds].
 */
internal fun classifyActiveProbe(
    metrics: ConnectivityMetrics,
    thresholds: ConnectionQualityThresholds,
): ConnectivityReport {
    if (metrics.transport == ConnectivityTransport.FAILED) {
        return ConnectivityReport(
            quality = ConnectionQuality.CRITICAL,
            metrics = metrics,
            reasons = listOf("Could not establish a realtime session for the deep probe."),
        )
    }

    val reasons = mutableListOf<String>()
    val dims = mutableListOf<ConnectionQuality>()

    metrics.ttffMs?.let { ttff ->
        val t = thresholds.ttff
        val q = scoreLowerBetter(ttff, t.goodMs, t.fairMs, t.poorMs)
        dims += q
        if (q != ConnectionQuality.GOOD) {
            reasons += "Time to first frame is ~${"%.1f".format(ttff / 1000)}s " +
                "(good ≤ ${(t.goodMs / 1000).toInt()}s); the session is slow to start."
        }
    }

    metrics.g2gMs?.let { g2g ->
        val g = thresholds.glassToGlass
        val q = scoreLowerBetter(g2g, g.goodMs, g.fairMs, g.poorMs)
        dims += q
        if (q != ConnectionQuality.GOOD) {
            reasons += "Mid-stream glass-to-glass latency is ~${g2g.toInt()}ms " +
                "(good ≤ ${g.goodMs.toInt()}ms); the real-time experience may feel laggy."
        }
    }

    if (metrics.ttffMs == null && metrics.g2gMs == null) {
        if (metrics.rttMs != null) {
            reasons += "Could not measure glass-to-glass latency during the probe " +
                "(no marker round-trip); using network RTT instead."
            dims += scoreLowerBetter(
                metrics.rttMs.toDouble(),
                thresholds.rtt.goodMs,
                thresholds.rtt.fairMs,
                thresholds.rtt.poorMs,
            )
        } else {
            reasons += "The probe connected but could not measure latency (no marker round-trip and no RTT sample)."
        }
    }

    metrics.g2gDropRatio?.let { drop ->
        val d = thresholds.g2gDrop
        val q = scoreLowerBetter(drop, d.good, d.fair, d.poor)
        dims += q
        if (q != ConnectionQuality.GOOD) {
            reasons += "End-to-end frame drop ratio is ${formatPercent(drop)}% (good ≤ ${formatPercent(d.good)}%)."
        }
    }

    metrics.packetLoss?.let { loss ->
        val l = thresholds.loss
        val q = scoreLowerBetter(loss, l.good, l.fair, l.poor)
        dims += q
        if (q != ConnectionQuality.GOOD) {
            reasons += "Upstream packet loss is ${formatPercent(loss)}% (good ≤ ${formatPercent(l.good)}%)."
        }
    }

    // Connected but no usable quality signal — don't claim "good" we never verified.
    if (dims.isEmpty()) return ConnectivityReport(ConnectionQuality.FAIR, metrics, reasons)

    return ConnectivityReport(worst(*dims.toTypedArray()), metrics, reasons)
}
