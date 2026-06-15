package ai.decart.sdk.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Port of the JS `SeqTracker` unit tests. */
class GlassToGlassSeqTrackerTest {

    // Mid-stream samples are only counted past a 2s warm-up after the first frame,
    // so tests establish a first frame, then feed steady-state samples well past it.
    private val pastWarmup = 5_000.0

    @Test
    fun `allocates monotonic 16-bit seqs that wrap at 0xffff`() {
        val t = SeqTracker()
        assertEquals(0, t.stampNext(0.0))
        assertEquals(1, t.stampNext(0.0))
        assertEquals(2, t.stampNext(0.0))

        val t2 = SeqTracker()
        var last = -1
        repeat(0xffff) { last = t2.stampNext(0.0) }
        assertEquals(0xffff - 1, last)
        assertEquals(0xffff, t2.stampNext(0.0))
        assertEquals(0, t2.stampNext(0.0)) // wrap
    }

    @Test
    fun `measures time-to-first-frame from markStart to the first rendered frame`() {
        val t = SeqTracker()
        t.markStart(1_000.0)
        val seq = t.stampNext(1_100.0)
        t.recordInbound(seq, 6_000.0) // first frame at 6000 → TTFF = 6000 - 1000
        val snap = t.snapshot()
        assertEquals(5_000.0, snap.ttffMs)
        // The first frame is the cold-start frame; it does not count toward mid-stream.
        assertEquals(0, snap.sampleCount)
        assertNull(snap.medianMs)
    }

    @Test
    fun `computes mid-stream latency percentiles, excluding warm-up frames`() {
        val t = SeqTracker()
        t.markStart(0.0)
        val warm = t.stampNext(0.0)
        t.recordInbound(warm, 10.0) // first frame establishes warm-up window (not a sample)

        for (latency in listOf(100, 200, 150, 300, 250)) {
            val seq = t.stampNext(pastWarmup)
            t.recordInbound(seq, pastWarmup + latency)
        }
        val snap = t.snapshot()
        assertEquals(5, snap.sampleCount)
        assertEquals(200.0, snap.medianMs) // sorted [100,150,200,250,300]
        assertEquals(300.0, snap.p90Ms)
    }

    @Test
    fun `averages the two middle samples for an even-count median`() {
        val t = SeqTracker()
        t.markStart(0.0)
        val warm = t.stampNext(0.0)
        t.recordInbound(warm, 10.0)

        for (latency in listOf(100, 200, 150, 300)) {
            val seq = t.stampNext(pastWarmup)
            t.recordInbound(seq, pastWarmup + latency)
        }
        val snap = t.snapshot()
        assertEquals(4, snap.sampleCount)
        assertEquals(175.0, snap.medianMs) // sorted [100,150,200,300] -> (150 + 200) / 2
    }

    @Test
    fun `ignores unknown, duplicate, and implausible inbound seqs`() {
        val t = SeqTracker()
        val warm = t.stampNext(0.0)
        t.recordInbound(warm, 0.0) // first frame

        val seq = t.stampNext(pastWarmup)
        t.recordInbound(9999, pastWarmup + 10) // unknown seq
        t.recordInbound(seq, pastWarmup + 120) // valid -> 120ms
        t.recordInbound(seq, pastWarmup + 130) // duplicate (already consumed) -> ignored
        val seq2 = t.stampNext(pastWarmup + 1_000)
        t.recordInbound(seq2, pastWarmup + 500) // negative delta -> ignored
        val snap = t.snapshot()
        assertEquals(1, snap.sampleCount)
        assertEquals(120.0, snap.medianMs)
    }

    @Test
    fun `reports null drop ratio until enough outcomes exist`() {
        val t = SeqTracker()
        val warm = t.stampNext(0.0)
        t.recordInbound(warm, 0.0)
        val seq = t.stampNext(pastWarmup)
        t.recordInbound(seq, pastWarmup + 50)
        assertNull(t.snapshot().dropRatio) // 1 outcome < DROP_MIN_OUTCOMES
    }

    @Test
    fun `infers end-to-end drops from seqs that age out unmatched`() {
        val t = SeqTracker()
        val warm = t.stampNext(0.0)
        t.recordInbound(warm, 0.0) // first frame; subsequent stamps are post-warm-up

        // Stamp 286 past warm-up: 30 oldest (beyond MAX_PENDING=256) age out unmatched -> 30 drops.
        val seqs = IntArray(286) { t.stampNext(pastWarmup) }
        // Deliver 20 of the still-pending seqs.
        for (i in 100 until 120) t.recordInbound(seqs[i], pastWarmup + 100)
        val snap = t.snapshot()
        // 30 dropped + 20 delivered = 50 outcomes -> 0.6 drop ratio.
        assertEquals(0.6, snap.dropRatio!!, 1e-6)
        assertEquals(20, snap.sampleCount)
    }

    @Test
    fun `does not count pre-first-frame stamps as drops`() {
        val t = SeqTracker()
        t.markStart(0.0)
        // 300 stamps before any frame ever renders (e.g. while still connecting).
        for (i in 0 until 300) t.stampNext(i.toDouble())
        // No match yet -> no outcomes recorded despite > MAX_PENDING evictions.
        assertNull(t.snapshot().dropRatio)
    }

    @Test
    fun `reset clears measurement state but keeps seq monotonic`() {
        val t = SeqTracker()
        t.markStart(0.0)
        val seq = t.stampNext(0.0)
        t.recordInbound(seq, 100.0)
        t.reset()
        val snap = t.snapshot()
        assertNull(snap.ttffMs)
        assertNull(snap.medianMs)
        assertNull(snap.p90Ms)
        assertEquals(0, snap.sampleCount)
        assertNull(snap.dropRatio)
        assertEquals(1, t.stampNext(0.0)) // continues, not reset to 0
    }
}
