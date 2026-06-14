package ai.decart.sdk.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the JS `ConnectionQualityEvaluator` unit tests. */
class ConnectionQualityEvaluatorTest {

    private fun fastThresholds(
        windowSamples: Int = 1,
        warmupSamples: Int = 1,
        downgradeConsecutive: Int = 3,
        upgradeConsecutive: Int = 3,
    ) = ConnectionQualityThresholds.DEFAULT.copy(
        windowSamples = windowSamples,
        warmupSamples = warmupSamples,
        downgradeConsecutive = downgradeConsecutive,
        upgradeConsecutive = upgradeConsecutive,
    )

    @Test
    fun `emits the first verdict immediately`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds())
        assertEquals(ConnectionQuality.GOOD, evaluator.update(makeSignals())?.quality)
    }

    @Test
    fun `requires consecutive samples before downgrading, then upgrading`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds())
        assertEquals(ConnectionQuality.GOOD, evaluator.update(makeSignals())?.quality)

        val bad = { makeSignals(rttMs = 600.0) }
        assertNull(evaluator.update(bad())) // 1
        assertNull(evaluator.update(bad())) // 2
        assertEquals(ConnectionQuality.CRITICAL, evaluator.update(bad())?.quality) // 3 → downgrade
        assertEquals(ConnectionQuality.CRITICAL, evaluator.current()?.quality)

        assertNull(evaluator.update(makeSignals())) // 1
        assertNull(evaluator.update(makeSignals())) // 2
        assertEquals(ConnectionQuality.GOOD, evaluator.update(makeSignals())?.quality) // 3 → upgrade
    }

    @Test
    fun `emits again when warm-up ends, even if the level stayed good`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds(warmupSamples = 3))
        evaluator.update(makeSignals()).let {
            assertEquals(ConnectionQuality.GOOD, it?.quality)
            assertTrue(it!!.warmingUp)
        }
        assertNull(evaluator.update(makeSignals())) // still warming, no change
        evaluator.update(makeSignals()).let {
            assertEquals(ConnectionQuality.GOOD, it?.quality)
            assertFalse(it!!.warmingUp) // warm-up ended → re-emit
        }
        assertNull(evaluator.update(makeSignals())) // steady afterward → silent
    }

    @Test
    fun `snaps to the real verdict when warm-up ends`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds(warmupSamples = 3))
        val weakUplink = { makeSignals(availableOutgoingKbps = 800.0) } // ratio ~0.23 → critical
        evaluator.update(weakUplink()).let {
            assertEquals(ConnectionQuality.GOOD, it?.quality) // bandwidth skipped while warming
            assertTrue(it!!.warmingUp)
        }
        assertNull(evaluator.update(weakUplink()))
        evaluator.update(weakUplink()).let {
            assertEquals(ConnectionQuality.CRITICAL, it?.quality)
            assertFalse(it!!.warmingUp)
            assertEquals(ConnectionQualityLimitingFactor.BANDWIDTH, it.limitingFactor)
        }
    }

    @Test
    fun `refreshes the limiting factor when the cause shifts at the same held level`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds())
        evaluator.update(makeSignals()) // good
        evaluator.update(makeSignals(rttMs = 600.0))
        evaluator.update(makeSignals(rttMs = 600.0))
        assertEquals(
            ConnectionQualityLimitingFactor.LATENCY,
            evaluator.update(makeSignals(rttMs = 600.0))?.limitingFactor,
        )
        // Still critical, but latency recovered and bandwidth is now the culprit.
        evaluator.update(makeSignals(availableOutgoingKbps = 500.0))
        val current = evaluator.current()!!
        assertEquals(ConnectionQuality.CRITICAL, current.quality)
        assertEquals(ConnectionQualityLimitingFactor.BANDWIDTH, current.limitingFactor)
    }

    @Test
    fun `keeps the limiting factor of the held verdict during recovery`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds())
        evaluator.update(makeSignals()) // good
        val badLatency = { makeSignals(rttMs = 600.0) }
        evaluator.update(badLatency())
        evaluator.update(badLatency())
        assertEquals(ConnectionQuality.CRITICAL, evaluator.update(badLatency())?.quality)
        assertEquals(ConnectionQualityLimitingFactor.LATENCY, evaluator.current()?.limitingFactor)

        // One good recovery sample: still debounced at critical — reason stays latency.
        assertNull(evaluator.update(makeSignals()))
        val current = evaluator.current()!!
        assertEquals(ConnectionQuality.CRITICAL, current.quality)
        assertEquals(ConnectionQualityLimitingFactor.LATENCY, current.limitingFactor)
    }

    @Test
    fun `resets the debounce counter when a sample returns to the current level`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds())
        evaluator.update(makeSignals()) // good
        val bad = { makeSignals(rttMs = 600.0) }
        assertNull(evaluator.update(bad())) // 1 bad
        assertNull(evaluator.update(bad())) // 2 bad
        assertNull(evaluator.update(makeSignals())) // good resets counter
        assertNull(evaluator.update(bad())) // 1 bad again
        assertNull(evaluator.update(bad())) // 2 bad
        assertEquals(ConnectionQuality.CRITICAL, evaluator.update(bad())?.quality) // 3 → downgrade
    }

    @Test
    fun `stays provisional and ignores bandwidth during warm-up, then scores it after`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds(warmupSamples = 3, downgradeConsecutive = 1))
        val lowUp = { makeSignals(availableOutgoingKbps = 1_000.0) }

        val first = evaluator.update(lowUp())
        assertEquals(ConnectionQuality.GOOD, first?.quality)
        assertTrue(first!!.warmingUp)

        assertNull(evaluator.update(lowUp())) // still warming, still good
        assertTrue(evaluator.current()!!.warmingUp)

        val afterWarmup = evaluator.update(lowUp()) // sample 3 → warm-up over, bandwidth counts
        assertFalse(afterWarmup!!.warmingUp)
        assertEquals(ConnectionQuality.CRITICAL, afterWarmup.quality)
    }

    @Test
    fun `reset clears all state`() {
        val evaluator = ConnectionQualityEvaluator(fastThresholds())
        evaluator.update(makeSignals(rttMs = 600.0))
        evaluator.reset()
        assertNull(evaluator.current())
        assertEquals(ConnectionQuality.GOOD, evaluator.update(makeSignals())?.quality)
    }
}
