package ai.decart.sdk.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialStateGateTest {

    @Test
    fun `attempt is current until a newer attempt starts`() {
        val gate = InitialStateGate()
        val first = gate.startAttempt()
        assertTrue(first.isCurrent)

        val second = gate.startAttempt()
        assertFalse("superseded attempt is no longer current", first.isCurrent)
        assertTrue(second.isCurrent)
    }

    @Test
    fun `reset supersedes the in-flight attempt`() {
        val gate = InitialStateGate()
        val attempt = gate.startAttempt()
        assertTrue(attempt.isCurrent)

        gate.reset()
        assertFalse(attempt.isCurrent)
    }
}
