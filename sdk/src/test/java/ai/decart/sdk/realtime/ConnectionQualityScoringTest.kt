package ai.decart.sdk.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of the JS `scoreSnapshot` unit tests. */
class ConnectionQualityScoringTest {

    /** Build a signal set that scores "good" by default; override per test. */
    private fun makeSignals(
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

    @Test
    fun `rates a healthy snapshot as good with no limiting factor`() {
        val result = scoreSnapshot(makeSignals())
        assertEquals(ConnectionQuality.GOOD, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.NONE, result.limitingFactor)
    }

    @Test
    fun `flags high RTT as a critical latency problem`() {
        val result = scoreSnapshot(makeSignals(rttMs = 600.0))
        assertEquals(ConnectionQuality.CRITICAL, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.LATENCY, result.limitingFactor)
    }

    @Test
    fun `flags high packet loss as a critical loss problem`() {
        val result = scoreSnapshot(makeSignals(fractionLost = 0.2))
        assertEquals(ConnectionQuality.CRITICAL, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.LOSS, result.limitingFactor)
    }

    @Test
    fun `a few percent loss reads as poor, not critical`() {
        assertEquals(ConnectionQuality.POOR, scoreSnapshot(makeSignals(fractionLost = 0.03)).quality)
    }

    @Test
    fun `flags insufficient upstream headroom as a bandwidth problem`() {
        // available 1 Mbps vs 3.5 Mbps required → ratio 0.29 < 0.5 critical
        val result = scoreSnapshot(makeSignals(availableOutgoingKbps = 1_000.0))
        assertEquals(ConnectionQuality.CRITICAL, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.BANDWIDTH, result.limitingFactor)
    }

    @Test
    fun `flags throttled upstream against the intended bitrate, not the dropped target`() {
        // Congestion control cut the uplink to ~1.2 Mbps; scoring against the
        // intended 3.5 Mbps still flags it rather than reading "good".
        val result = scoreSnapshot(makeSignals(availableOutgoingKbps = 1_200.0))
        assertEquals(ConnectionQuality.CRITICAL, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.BANDWIDTH, result.limitingFactor)
    }

    @Test
    fun `caps upstream at fair when the encoder reports a bandwidth limit, even with good BWE`() {
        val result = scoreSnapshot(
            makeSignals(availableOutgoingKbps = 6_000.0, qualityLimitationReason = "bandwidth"),
        )
        assertEquals(ConnectionQuality.FAIR, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.BANDWIDTH, result.limitingFactor)
    }

    @Test
    fun `treats a CPU-limited encoder as informational, never dragging quality down`() {
        val result = scoreSnapshot(makeSignals(qualityLimitationReason = "cpu"))
        assertEquals(ConnectionQuality.GOOD, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.CPU, result.limitingFactor)
    }

    @Test
    fun `flags a stalled inbound stream as a stall problem`() {
        val result = scoreSnapshot(makeSignals(fps = 3.0))
        assertEquals(ConnectionQuality.CRITICAL, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.STALL, result.limitingFactor)
    }

    @Test
    fun `widens RTT bands on TURN-relayed paths`() {
        // 250ms: fair on a direct path, good once relay adds +100ms headroom.
        assertEquals(ConnectionQuality.FAIR, scoreSnapshot(makeSignals(rttMs = 250.0, isRelayed = false)).quality)
        assertEquals(ConnectionQuality.GOOD, scoreSnapshot(makeSignals(rttMs = 250.0, isRelayed = true)).quality)
    }

    @Test
    fun `skips the bandwidth dimension when skipBitrate is set`() {
        val weak = makeSignals(availableOutgoingKbps = 1_000.0)
        assertEquals(ConnectionQuality.GOOD, scoreSnapshot(weak, skipBitrate = true).quality)
        assertEquals(ConnectionQuality.CRITICAL, scoreSnapshot(weak).quality)
    }

    @Test
    fun `drives the latency verdict off measured glass-to-glass when present, not RTT`() {
        // Low RTT alone reads good, but a high measured g2g (slow model path) must
        // pull latency down — the whole point of the feature.
        val result = scoreSnapshot(makeSignals(rttMs = 50.0, g2gMs = 1800.0))
        assertEquals(ConnectionQuality.CRITICAL, result.quality) // 1800 > poor band (1500)
        assertEquals(ConnectionQualityLimitingFactor.LATENCY, result.limitingFactor)
    }

    @Test
    fun `rates a typical mid-stream glass-to-glass latency as good`() {
        assertEquals(ConnectionQuality.GOOD, scoreSnapshot(makeSignals(g2gMs = 450.0)).quality)
    }

    @Test
    fun `rates a good glass-to-glass latency as good even on a relayed path`() {
        // g2g already includes the network legs, so no relay headroom is applied.
        assertEquals(ConnectionQuality.GOOD, scoreSnapshot(makeSignals(g2gMs = 450.0, isRelayed = true)).quality)
    }

    @Test
    fun `falls back to RTT for latency when glass-to-glass is absent`() {
        assertEquals(ConnectionQuality.CRITICAL, scoreSnapshot(makeSignals(rttMs = 600.0)).quality)
    }

    @Test
    fun `flags a high end-to-end frame drop ratio as a stall problem`() {
        val result = scoreSnapshot(makeSignals(g2gDropRatio = 0.2))
        assertEquals(ConnectionQuality.CRITICAL, result.quality) // 20% > poor band (5%)
        assertEquals(ConnectionQualityLimitingFactor.STALL, result.limitingFactor)
    }

    @Test
    fun `treats missing metrics as good`() {
        val allNull = makeSignals(
            rttMs = null,
            fractionLost = null,
            availableOutgoingKbps = null,
            fps = null,
            freezeCountDelta = null,
            qualityLimitationReason = null,
        )
        val result = scoreSnapshot(allNull)
        assertEquals(ConnectionQuality.GOOD, result.quality)
        assertEquals(ConnectionQualityLimitingFactor.NONE, result.limitingFactor)
    }
}
