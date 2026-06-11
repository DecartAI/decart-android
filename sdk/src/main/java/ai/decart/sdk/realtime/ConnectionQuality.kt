package ai.decart.sdk.realtime

/**
 * Smoothed verdict on whether the connection is good enough for the realtime
 * pipeline, derived from the raw WebRTC stats the SDK already collects.
 *
 * Ordinal order is significant: it doubles as the rank (CRITICAL worst, GOOD
 * best), so [worst] can pick the lowest by ordinal.
 */
enum class ConnectionQuality { CRITICAL, POOR, FAIR, GOOD }

/** Which dimension pulled the verdict down to its current level. */
enum class ConnectionQualityLimitingFactor { BANDWIDTH, LATENCY, LOSS, STALL, CPU, NONE }

/** Human-meaningful numbers behind the verdict; the full raw stats are on the `stats` flow. */
data class ConnectionQualityMetrics(
    /** Round-trip time in ms, or null until measured. */
    val rttMs: Double?,
    /**
     * Mid-stream (steady-state) glass-to-glass latency (ms) — the real per-frame
     * camera→display latency through the model, excluding startup. Only populated
     * under the opt-in pixel-marker measurement (`connect(debugQuality = true)`)
     * and past warm-up; null otherwise. When present it drives the latency verdict
     * instead of [rttMs].
     */
    val g2gMs: Double?,
    /**
     * Time-to-first-frame (ms) — startup latency from connect to the first rendered
     * model frame. One-shot; populated under g2g measurement once the first frame
     * arrives. Surfaced for visibility; does not drive the live verdict.
     */
    val ttffMs: Double?,
    /** Rendered (inbound) frames per second, or null until measured. */
    val fps: Double?,
    /** Fraction (0–1) of our outbound packets the server reports lost, or null until measured. */
    val packetLoss: Double?,
    /** Server's view of upstream (client→server) jitter in ms, or null. Observational. */
    val upstreamJitterMs: Double?,
    /** End-to-end frame drop ratio (0–1) inferred from the pixel-marker seq stream. Null unless g2g is on. */
    val g2gDropRatio: Double?,
    /** Estimated available upstream bandwidth in kbps, or null until measured. */
    val availableUpstreamKbps: Double?,
)

data class ConnectionQualityReport(
    val quality: ConnectionQuality,
    val limitingFactor: ConnectionQualityLimitingFactor,
    /** True while the connection ramps; the verdict is provisional. */
    val warmingUp: Boolean,
    val metrics: ConnectionQualityMetrics,
)

/**
 * Full set of raw signals the scorer needs; the public report exposes a subset.
 * On Android these are merged from the publisher peer-connection stats (RTT,
 * loss, upstream BWE, relay, quality-limitation) and the subscriber
 * peer-connection stats (rendered fps, freezes).
 */
internal data class QualitySignals(
    val rttMs: Double?,
    val g2gMs: Double?,
    val ttffMs: Double?,
    val upstreamJitterMs: Double?,
    /** Already normalized to a 0–1 packet-loss fraction (raw RFC 3550 value ÷ 256). */
    val fractionLost: Double?,
    val g2gDropRatio: Double?,
    val availableOutgoingKbps: Double?,
    val fps: Double?,
    val freezeCountDelta: Double?,
    val qualityLimitationReason: String?,
    val isRelayed: Boolean,
)

data class ConnectionQualityThresholds(
    val windowSamples: Int,
    val warmupSamples: Int,
    val downgradeConsecutive: Int,
    val upgradeConsecutive: Int,
    val rtt: Rtt,
    val glassToGlass: MsBand,
    val ttff: MsBand,
    val loss: Loss,
    val g2gDrop: Loss,
    val upstream: Upstream,
    val stall: Stall,
) {
    /** Round-trip time bands (ms). Bands widen by [relayExtraMs] on TURN-relayed paths. */
    data class Rtt(val goodMs: Double, val fairMs: Double, val poorMs: Double, val relayExtraMs: Double)

    /** Generic millisecond bands (no relay headroom) — used for glass-to-glass and time-to-first-frame. */
    data class MsBand(val goodMs: Double, val fairMs: Double, val poorMs: Double)

    /** A 0..1 fraction band — used for packet loss and end-to-end frame drop ratio. */
    data class Loss(val good: Double, val fair: Double, val poor: Double)

    /** Upstream headroom = available BWE ÷ the intended publish bitrate ([requiredUpstreamKbps]). */
    data class Upstream(val goodRatio: Double, val fairRatio: Double, val poorRatio: Double, val requiredUpstreamKbps: Double)

    /** Rendered (inbound) frames-per-second bands. */
    data class Stall(val goodFps: Double, val fairFps: Double, val poorFps: Double)

    companion object {
        /**
         * Tuned for a camera-up realtime pipeline (~3–3.5 Mbps upstream, model
         * fps ~25–30). Values mirror the JS SDK's `config-realtime.ts`.
         */
        val DEFAULT = ConnectionQualityThresholds(
            windowSamples = 5,
            warmupSamples = 8,
            downgradeConsecutive = 5,
            upgradeConsecutive = 5,
            rtt = Rtt(goodMs = 150.0, fairMs = 300.0, poorMs = 500.0, relayExtraMs = 100.0),
            // Steady-state glass-to-glass through the model (already includes both
            // network legs, so relay headroom does not apply). Anchored to server
            // pipeline latency (~285ms median) + network/jitter/decode headroom.
            glassToGlass = MsBand(goodMs = 500.0, fairMs = 900.0, poorMs = 1500.0),
            // Startup latency (connect → first rendered frame); judged separately,
            // an order of magnitude larger than steady state.
            ttff = MsBand(goodMs = 4_000.0, fairMs = 6_000.0, poorMs = 10_000.0),
            loss = Loss(good = 0.02, fair = 0.05, poor = 0.1),
            g2gDrop = Loss(good = 0.02, fair = 0.05, poor = 0.1),
            upstream = Upstream(goodRatio = 1.0, fairRatio = 0.8, poorRatio = 0.5, requiredUpstreamKbps = 3500.0),
            stall = Stall(goodFps = 20.0, fairFps = 12.0, poorFps = 5.0),
        )
    }
}

internal data class ScoreResult(
    val quality: ConnectionQuality,
    val limitingFactor: ConnectionQualityLimitingFactor,
)

/** Worst (lowest-ranked) of the given qualities. */
internal fun worst(vararg qualities: ConnectionQuality): ConnectionQuality =
    qualities.minByOrNull { it.ordinal } ?: ConnectionQuality.GOOD

// A null metric scores "good" — absence of evidence is not evidence of badness.
internal fun scoreLowerBetter(value: Double?, good: Double, fair: Double, poor: Double): ConnectionQuality =
    when {
        value == null -> ConnectionQuality.GOOD
        value <= good -> ConnectionQuality.GOOD
        value <= fair -> ConnectionQuality.FAIR
        value <= poor -> ConnectionQuality.POOR
        else -> ConnectionQuality.CRITICAL
    }

internal fun scoreHigherBetter(value: Double?, good: Double, fair: Double, poor: Double): ConnectionQuality =
    when {
        value == null -> ConnectionQuality.GOOD
        value >= good -> ConnectionQuality.GOOD
        value >= fair -> ConnectionQuality.FAIR
        value >= poor -> ConnectionQuality.POOR
        else -> ConnectionQuality.CRITICAL
    }

/** Score an already-extracted (optionally smoothed) signal set. Pure. */
internal fun scoreMetrics(
    signals: QualitySignals,
    thresholds: ConnectionQualityThresholds,
    skipBitrate: Boolean = false,
): ScoreResult {
    // Prefer measured glass-to-glass — the real experienced latency — when the
    // opt-in pixel-marker measurement is active. It already includes both network
    // legs, so relay headroom doesn't apply. Fall back to RTT otherwise.
    val relayExtra = if (signals.isRelayed) thresholds.rtt.relayExtraMs else 0.0
    val latency = if (signals.g2gMs != null) {
        scoreLowerBetter(
            signals.g2gMs,
            thresholds.glassToGlass.goodMs,
            thresholds.glassToGlass.fairMs,
            thresholds.glassToGlass.poorMs,
        )
    } else {
        scoreLowerBetter(
            signals.rttMs,
            thresholds.rtt.goodMs + relayExtra,
            thresholds.rtt.fairMs + relayExtra,
            thresholds.rtt.poorMs + relayExtra,
        )
    }

    val loss = scoreLowerBetter(signals.fractionLost, thresholds.loss.good, thresholds.loss.fair, thresholds.loss.poor)

    // Upstream only: available BWE ÷ the INTENDED publish bitrate. Dividing by the
    // encoder's adaptive target would mask throttling (it drops with the uplink).
    // Downstream bitrate is intentionally not scored — it's server-chosen.
    var bandwidth = ConnectionQuality.GOOD
    if (!skipBitrate) {
        val ratio = signals.availableOutgoingKbps?.let { it / thresholds.upstream.requiredUpstreamKbps }
        bandwidth = scoreHigherBetter(
            ratio,
            thresholds.upstream.goodRatio,
            thresholds.upstream.fairRatio,
            thresholds.upstream.poorRatio,
        )
        // Encoder self-reporting a bandwidth limit is a stronger signal than the ratio.
        if (signals.qualityLimitationReason == "bandwidth") bandwidth = worst(bandwidth, ConnectionQuality.FAIR)
    }

    var stall = scoreHigherBetter(
        signals.fps,
        thresholds.stall.goodFps,
        thresholds.stall.fairFps,
        thresholds.stall.poorFps,
    )
    if (signals.freezeCountDelta != null && signals.freezeCountDelta > 0) stall = worst(stall, ConnectionQuality.FAIR)
    // End-to-end frame drops (server backpressure / overload, or transit loss)
    // surface as the same user-visible symptom as a low frame rate.
    val drop = scoreLowerBetter(
        signals.g2gDropRatio,
        thresholds.g2gDrop.good,
        thresholds.g2gDrop.fair,
        thresholds.g2gDrop.poor,
    )
    stall = worst(stall, drop)

    val quality = worst(bandwidth, latency, loss, stall)

    // Worst network dimension (tie-break bandwidth > loss > latency > stall). "cpu"
    // is informational and only surfaces when the network is otherwise clean.
    val limitingFactor = when {
        quality == ConnectionQuality.GOOD ->
            if (signals.qualityLimitationReason == "cpu") ConnectionQualityLimitingFactor.CPU
            else ConnectionQualityLimitingFactor.NONE
        bandwidth == quality -> ConnectionQualityLimitingFactor.BANDWIDTH
        loss == quality -> ConnectionQualityLimitingFactor.LOSS
        latency == quality -> ConnectionQualityLimitingFactor.LATENCY
        else -> ConnectionQualityLimitingFactor.STALL
    }

    return ScoreResult(quality, limitingFactor)
}

/** Convenience for tests: score a raw signal snapshot in one call. */
internal fun scoreSnapshot(
    signals: QualitySignals,
    thresholds: ConnectionQualityThresholds = ConnectionQualityThresholds.DEFAULT,
    skipBitrate: Boolean = false,
): ScoreResult = scoreMetrics(signals, thresholds, skipBitrate)

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
}

private class RingBuffer(private val size: Int) {
    private val values = ArrayDeque<Double>()

    fun push(value: Double?) {
        if (value == null) return
        values.addLast(value)
        if (values.size > size) values.removeFirst()
    }

    fun median(): Double? = median(values.toList())

    fun min(): Double? = values.minOrNull()

    fun clear() = values.clear()
}

/**
 * Smooths metrics over a rolling window and applies asymmetric hysteresis so the
 * emitted level doesn't flap. [update] returns a report only when the level or
 * warm-up state changes; [current] returns the latest at any time.
 */
internal class ConnectionQualityEvaluator(
    private val thresholds: ConnectionQualityThresholds = ConnectionQualityThresholds.DEFAULT,
) {
    private val rtt = RingBuffer(thresholds.windowSamples)
    private val glassToGlass = RingBuffer(thresholds.windowSamples)
    private val loss = RingBuffer(thresholds.windowSamples)
    private val availableOutgoing = RingBuffer(thresholds.windowSamples)
    private val fps = RingBuffer(thresholds.windowSamples)

    private var sampleCount = 0
    private var currentLevel: ConnectionQuality? = null

    // Reason for the current verdict; refreshed to the live cause, but held across a
    // recovery lag (bad level still debounced while the latest sample improved).
    private var currentFactor: ConnectionQualityLimitingFactor = ConnectionQualityLimitingFactor.NONE
    private var candidateLevel: ConnectionQuality? = null
    private var candidateCount = 0
    private var prevWarmingUp = true
    private var lastReport: ConnectionQualityReport? = null

    /** Feed one raw signal sample. Returns a report only when the level or warm-up state changes. */
    fun update(raw: QualitySignals): ConnectionQualityReport? {
        sampleCount++

        rtt.push(raw.rttMs)
        glassToGlass.push(raw.g2gMs)
        loss.push(raw.fractionLost)
        availableOutgoing.push(raw.availableOutgoingKbps)
        fps.push(raw.fps)

        // `upstreamJitterMs` (observational, unscored), `ttffMs` (one-shot startup),
        // and `g2gDropRatio` (already windowed by the SeqTracker) ride through raw.
        val smoothed = raw.copy(
            rttMs = rtt.median(),
            g2gMs = glassToGlass.median(),
            fractionLost = loss.median(),
            availableOutgoingKbps = availableOutgoing.median(),
            fps = fps.min(),
        )

        val warmingUp = sampleCount < thresholds.warmupSamples
        val scored = scoreMetrics(smoothed, thresholds, skipBitrate = warmingUp)

        // Warm-up skips bandwidth scoring; when it ends, commit the fully-scored verdict
        // immediately so the first non-warming report is authoritative, rather than
        // holding the optimistic "good" through the downgrade debounce.
        val warmupJustEnded = prevWarmingUp && !warmingUp
        prevWarmingUp = warmingUp

        val changed: Boolean
        if (warmupJustEnded) {
            changed = currentLevel != scored.quality
            currentLevel = scored.quality
            candidateLevel = null
            candidateCount = 0
        } else {
            changed = applyHysteresis(scored.quality)
        }

        val emitted = currentLevel ?: scored.quality

        // limitingFactor explains why we're at `emitted`: nothing when good; otherwise
        // the current worst dimension — but keep the last committed reason while a bad
        // level is held and the latest sample has already recovered above it.
        if (emitted == ConnectionQuality.GOOD) {
            currentFactor = if (smoothed.qualityLimitationReason == "cpu") {
                ConnectionQualityLimitingFactor.CPU
            } else {
                ConnectionQualityLimitingFactor.NONE
            }
        } else if (scored.quality.ordinal <= emitted.ordinal) {
            currentFactor = scored.limitingFactor
        }

        lastReport = ConnectionQualityReport(
            quality = emitted,
            limitingFactor = currentFactor,
            warmingUp = warmingUp,
            metrics = ConnectionQualityMetrics(
                rttMs = smoothed.rttMs,
                g2gMs = smoothed.g2gMs,
                // ttffMs (one-shot startup), upstreamJitterMs (observational), and
                // g2gDropRatio (already windowed) are surfaced raw, not re-smoothed.
                ttffMs = raw.ttffMs,
                fps = smoothed.fps,
                packetLoss = smoothed.fractionLost,
                upstreamJitterMs = raw.upstreamJitterMs,
                g2gDropRatio = raw.g2gDropRatio,
                availableUpstreamKbps = smoothed.availableOutgoingKbps,
            ),
        )

        return if (changed || warmupJustEnded) lastReport else null
    }

    fun current(): ConnectionQualityReport? = lastReport

    fun reset() {
        rtt.clear()
        glassToGlass.clear()
        loss.clear()
        availableOutgoing.clear()
        fps.clear()
        sampleCount = 0
        currentLevel = null
        currentFactor = ConnectionQualityLimitingFactor.NONE
        candidateLevel = null
        candidateCount = 0
        prevWarmingUp = true
        lastReport = null
    }

    /** Returns true if the debounced level changed this tick. */
    private fun applyHysteresis(raw: ConnectionQuality): Boolean {
        val level = currentLevel
        if (level == null) {
            currentLevel = raw // first verdict — emit immediately
            candidateLevel = null
            candidateCount = 0
            return true
        }

        if (raw == level) {
            candidateLevel = null
            candidateCount = 0
            return false
        }

        if (raw == candidateLevel) {
            candidateCount++
        } else {
            candidateLevel = raw
            candidateCount = 1
        }

        val isDowngrade = raw.ordinal < level.ordinal
        val required = if (isDowngrade) thresholds.downgradeConsecutive else thresholds.upgradeConsecutive
        if (candidateCount >= required) {
            currentLevel = raw
            candidateLevel = null
            candidateCount = 0
            return true
        }
        return false
    }
}
