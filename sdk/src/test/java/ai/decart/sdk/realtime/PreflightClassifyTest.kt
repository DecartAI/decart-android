package ai.decart.sdk.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the JS `classifyConnectivity` unit tests. */
class PreflightClassifyTest {

    private val rtt = PreflightRttThresholds(goodMs = 150L, marginalMs = 300L)

    @Test
    fun `treats no connectivity as critical`() {
        val report = classifyConnectivity(ConnectivityMetrics(ConnectivityTransport.FAILED, null), rtt)
        assertEquals(ConnectionQuality.CRITICAL, report.quality)
        assertTrue(report.reasons.isNotEmpty())
        assertEquals(ConnectivityMetrics(ConnectivityTransport.FAILED, null), report.metrics)
    }

    @Test
    fun `treats relay-only as poor`() {
        val report = classifyConnectivity(ConnectivityMetrics(ConnectivityTransport.RELAY, 90L), rtt)
        assertEquals(ConnectionQuality.POOR, report.quality)
        assertTrue(report.reasons.isNotEmpty())
    }

    @Test
    fun `treats direct UDP with low RTT as good`() {
        val report = classifyConnectivity(ConnectivityMetrics(ConnectivityTransport.UDP, 100L), rtt)
        assertEquals(ConnectionQuality.GOOD, report.quality)
        assertTrue(report.reasons.isEmpty())
        assertEquals(100L, report.metrics.rttMs)
    }

    @Test
    fun `treats elevated RTT on direct UDP as fair`() {
        val report = classifyConnectivity(ConnectivityMetrics(ConnectivityTransport.UDP, 200L), rtt)
        assertEquals(ConnectionQuality.FAIR, report.quality)
    }

    @Test
    fun `treats very high RTT on direct UDP as poor`() {
        val report = classifyConnectivity(ConnectivityMetrics(ConnectivityTransport.UDP, 420L), rtt)
        assertEquals(ConnectionQuality.POOR, report.quality)
    }

    @Test
    fun `treats direct UDP with unknown RTT as good`() {
        val report = classifyConnectivity(ConnectivityMetrics(ConnectivityTransport.UDP, null), rtt)
        assertEquals(ConnectionQuality.GOOD, report.quality)
    }

    // --- Deep probe (classifyActiveProbe) ---

    private val probeThresholds = ConnectionQualityThresholds.DEFAULT

    private fun probeMetrics(
        transport: ConnectivityTransport = ConnectivityTransport.UDP,
        rttMs: Long? = 50,
        g2gMs: Double? = 450.0,
        ttffMs: Double? = 2_000.0,
        g2gDropRatio: Double? = 0.0,
        packetLoss: Double? = 0.0,
    ) = ConnectivityMetrics(
        transport = transport,
        rttMs = rttMs,
        g2gMs = g2gMs,
        ttffMs = ttffMs,
        g2gDropRatio = g2gDropRatio,
        upstreamJitterMs = 5,
        packetLoss = packetLoss,
        sampleCount = 30,
    )

    @Test
    fun `rates a fast startup and low mid-stream latency as good with no reasons`() {
        val report = classifyActiveProbe(probeMetrics(ttffMs = 2_000.0, g2gMs = 450.0), probeThresholds)
        assertEquals(ConnectionQuality.GOOD, report.quality)
        assertTrue(report.reasons.isEmpty())
    }

    @Test
    fun `drives the verdict off mid-stream glass-to-glass even when RTT is low`() {
        val report = classifyActiveProbe(probeMetrics(rttMs = 30, g2gMs = 1_800.0), probeThresholds)
        assertEquals(ConnectionQuality.CRITICAL, report.quality) // 1800 > poor band (1500)
        assertTrue(report.reasons.any { it.contains("glass-to-glass") })
    }

    @Test
    fun `scores time-to-first-frame separately from mid-stream latency`() {
        // Good steady state, but a very slow cold start (12s) must pull the verdict down.
        val report = classifyActiveProbe(probeMetrics(ttffMs = 12_000.0, g2gMs = 450.0), probeThresholds)
        assertEquals(ConnectionQuality.CRITICAL, report.quality) // 12s > poor band (10s)
        assertTrue(report.reasons.any { it.contains("first frame") })
    }

    @Test
    fun `treats a 4-5s cold start as fair, not critical`() {
        val report = classifyActiveProbe(probeMetrics(ttffMs = 4_500.0, g2gMs = 450.0), probeThresholds)
        assertEquals(ConnectionQuality.FAIR, report.quality) // 4.5s within the fair band (≤6s)
    }

    @Test
    fun `falls back to RTT when neither latency could be measured`() {
        val report = classifyActiveProbe(
            probeMetrics(g2gMs = null, ttffMs = null, rttMs = 600, g2gDropRatio = null, packetLoss = null),
            probeThresholds,
        )
        assertEquals(ConnectionQuality.CRITICAL, report.quality) // RTT 600 > poor band (500)
        assertTrue(report.reasons.any { it.contains("Could not measure") })
    }

    @Test
    fun `flags a high end-to-end drop ratio even when latency is good`() {
        val report = classifyActiveProbe(probeMetrics(g2gMs = 150.0, g2gDropRatio = 0.2), probeThresholds)
        assertEquals(ConnectionQuality.CRITICAL, report.quality)
    }

    @Test
    fun `flags high upstream packet loss`() {
        val report = classifyActiveProbe(probeMetrics(g2gMs = 150.0, packetLoss = 0.2), probeThresholds)
        assertEquals(ConnectionQuality.CRITICAL, report.quality)
    }

    @Test
    fun `treats a failed connection as critical`() {
        val report = classifyActiveProbe(probeMetrics(transport = ConnectivityTransport.FAILED), probeThresholds)
        assertEquals(ConnectionQuality.CRITICAL, report.quality)
        assertTrue(report.reasons.isNotEmpty())
    }

    @Test
    fun `returns fair when connected but nothing could be measured`() {
        val report = classifyActiveProbe(
            probeMetrics(g2gMs = null, ttffMs = null, rttMs = null, g2gDropRatio = null, packetLoss = null),
            probeThresholds,
        )
        assertEquals(ConnectionQuality.FAIR, report.quality)
        assertTrue(report.reasons.isNotEmpty())
    }
}
