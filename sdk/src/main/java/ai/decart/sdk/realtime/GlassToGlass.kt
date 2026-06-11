package ai.decart.sdk.realtime

/**
 * True glass-to-glass latency measurement for the realtime pipeline.
 *
 * Opt-in (the marker is visible in the output and pixel work has a cost). When
 * enabled, the SDK stamps a monotonic sequence number into the bottom-left of
 * every outgoing frame and reads it back off the rendered remote frames; the
 * server re-stamps the seq from input to matching output (its `pixel_latency`
 * mode). [SeqTracker] matches stamp time to render time to compute the real
 * camera→display latency through the model, and infers end-to-end frame drops
 * from seqs that are stamped but never rendered.
 *
 * Faithful port of the JS SDK's `observability/glass-to-glass.ts` `SeqTracker`.
 */

/** Monotonic millisecond clock shared by the stamp pump, marker reader, and TTFF start. */
internal fun monotonicMs(): Double = System.nanoTime() / 1_000_000.0

/**
 * Aggregated glass-to-glass metrics. TTFF (startup) and mid-stream (steady
 * state) are measured separately — they differ by an order of magnitude and the
 * cold-start frames must not pollute the steady-state numbers.
 */
data class G2GMetrics(
    /** Time-to-first-frame (ms): connect attempt start → first rendered model frame. Null until it arrives. */
    val ttffMs: Double?,
    /** Median mid-stream (steady-state) glass-to-glass latency (ms), excluding warm-up. Null until past warm-up. */
    val medianMs: Double?,
    /** p90 mid-stream glass-to-glass latency (ms), or null until past warm-up. */
    val p90Ms: Double?,
    /** Mid-stream latency samples in the window (post-warm-up). */
    val sampleCount: Int,
    /** End-to-end frame drop ratio (0–1): seqs stamped but never rendered. Null until enough outcomes exist. */
    val dropRatio: Double?,
)

/**
 * Matches outgoing stamp times to incoming render times. Shared by the stamp
 * pump (writer) and the marker reader (matcher); owned by [livekit.LiveKitMediaChannel].
 *
 * Tracks two latencies: TTFF (start → first frame) and mid-stream median (steady
 * state, after a warm-up). Call [markStart] at the beginning of each connect
 * attempt so TTFF measures the full setup→first-frame wait.
 *
 * Accessed from three threads — the capturer (stamp), the renderer (record), and
 * the stats loop (snapshot) — so every method is `@Synchronized`. Internal: the
 * public surface exposes only the derived [G2GMetrics] via `getGlassToGlass()`.
 */
internal class SeqTracker {
    private val stampTimes = LinkedHashMap<Int, Double>()
    private val latencies = ArrayDeque<Double>()

    /** true = delivered (matched), false = dropped (aged out unmatched). */
    private val outcomes = ArrayDeque<Boolean>()
    private var nextSeq = 0
    private var startMs: Double? = null
    private var firstMatchMs: Double? = null
    private var ttffMs: Double? = null

    /** Mark the start of a connect attempt; resets measurement state. TTFF is measured from here. */
    @Synchronized
    fun markStart(nowMs: Double) {
        reset()
        startMs = nowMs
    }

    /** Allocate the next seq for an outgoing frame and record its stamp time. Returns the 16-bit seq. */
    @Synchronized
    fun stampNext(nowMs: Double): Int {
        val seq = nextSeq and 0xffff
        nextSeq = (nextSeq + 1) and 0xffff
        stampTimes[seq] = nowMs
        if (stampTimes.size > MAX_PENDING) {
            // Oldest insertion (LinkedHashMap preserves order) aged out without a match.
            val oldest = stampTimes.keys.firstOrNull()
            if (oldest != null) {
                stampTimes.remove(oldest)
                // Only a real drop once the stream is live and past warm-up; pre-publish
                // and cold-start stamps that age out are not counted.
                if (isPastWarmup(nowMs)) recordOutcome(false)
            }
        }
        return seq
    }

    /** Match a seq read off an inbound rendered frame. Ignores unknown/duplicate seqs. */
    @Synchronized
    fun recordInbound(seq: Int, nowMs: Double) {
        val stampedAt = stampTimes[seq] ?: return // unknown, already consumed, or evicted
        stampTimes.remove(seq)
        val g2g = nowMs - stampedAt
        if (g2g < 0 || g2g > MAX_PLAUSIBLE_MS) return

        if (firstMatchMs == null) {
            // First rendered frame: capture TTFF and discard any older (pre-publish /
            // pre-warm) pending stamps so they don't later age out as phantom drops.
            firstMatchMs = nowMs
            startMs?.let { ttffMs = nowMs - it }
            val iter = stampTimes.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.value < stampedAt) iter.remove() else break // insertion order == time order
            }
        }

        if (!isPastWarmup(nowMs)) return // first frame + warm-up don't pollute steady state
        latencies.addLast(g2g)
        if (latencies.size > LATENCY_WINDOW) latencies.removeFirst()
        recordOutcome(true)
    }

    @Synchronized
    fun snapshot(): G2GMetrics {
        val sorted = latencies.sorted()
        val n = sorted.size
        // True median: average the two middle samples on an even count (nearest-rank
        // would bias high and can tip a verdict near a band threshold). p90 stays
        // nearest-rank (the standard percentile convention).
        val medianMs: Double? = when {
            n == 0 -> null
            n % 2 == 0 -> Math.round((sorted[n / 2 - 1] + sorted[n / 2]) / 2).toDouble()
            else -> Math.round(sorted[(n - 1) / 2]).toDouble()
        }
        val p90Ms: Double? =
            if (n == 0) null else Math.round(sorted[minOf(n - 1, Math.floor(0.9 * n).toInt())]).toDouble()

        var dropRatio: Double? = null
        if (outcomes.size >= DROP_MIN_OUTCOMES) {
            val dropped = outcomes.count { !it }
            dropRatio = dropped.toDouble() / outcomes.size
        }
        return G2GMetrics(ttffMs = ttffMs, medianMs = medianMs, p90Ms = p90Ms, sampleCount = n, dropRatio = dropRatio)
    }

    /** Clear measurement state. Keeps `nextSeq` monotonic to avoid stale collisions. */
    @Synchronized
    fun reset() {
        stampTimes.clear()
        latencies.clear()
        outcomes.clear()
        startMs = null
        firstMatchMs = null
        ttffMs = null
    }

    private fun isPastWarmup(nowMs: Double): Boolean {
        val first = firstMatchMs ?: return false
        return nowMs >= first + MID_STREAM_WARMUP_MS
    }

    private fun recordOutcome(delivered: Boolean) {
        outcomes.addLast(delivered)
        if (outcomes.size > OUTCOME_WINDOW) outcomes.removeFirst()
    }

    companion object {
        /** Bound on in-flight seqs; a seq that ages out unmatched is an end-to-end drop. */
        private const val MAX_PENDING = 256

        /** Rolling window for the latency percentiles (≈10s at 30fps). */
        private const val LATENCY_WINDOW = 300

        /** Rolling window of delivered/dropped outcomes for the drop ratio. */
        private const val OUTCOME_WINDOW = 300

        /** Don't report a drop ratio until this many outcomes exist (head-of-stream frames are still in flight). */
        private const val DROP_MIN_OUTCOMES = 30

        /** Discard implausible deltas (clock weirdness, seq wrap collisions). */
        private const val MAX_PLAUSIBLE_MS = 60_000.0

        /**
         * After the first frame, ignore this long before counting steady-state samples.
         * The first frames after a cold start run slow while the pipeline warms; folding
         * them into the mid-stream median would inflate it. (TTFF still captures the first frame.)
         */
        private const val MID_STREAM_WARMUP_MS = 2_000.0
    }
}
