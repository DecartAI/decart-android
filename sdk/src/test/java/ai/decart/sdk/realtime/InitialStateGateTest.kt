@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.decart.sdk.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialStateGateTest {

    @Test
    fun `passes through immediately when no initial state was provided`() = runTest(UnconfinedTestDispatcher()) {
        val gate = InitialStateGate()
        val attempt = gate.startAttempt(null)
        assertFalse("nothing to wait for", attempt.shouldWait)

        val ack = CompletableDeferred<Unit>()
        // The ack never resolves; should still return immediately.
        val ok = attempt.waitForReadiness(ack)
        assertTrue(ok)
    }

    @Test
    fun `waits for ack when initial image is provided`() = runTest(UnconfinedTestDispatcher()) {
        val gate = InitialStateGate()
        val attempt = gate.startAttempt(InitialState(image = "data:image/png;base64,XYZ"))
        assertTrue("must wait for ack", attempt.shouldWait)

        val ack = CompletableDeferred<Unit>()
        val deferred = async { attempt.waitForReadiness(ack) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        ack.complete(Unit)
        assertTrue(deferred.await())
    }

    @Test
    fun `waits for ack when initial prompt is provided`() = runTest(UnconfinedTestDispatcher()) {
        val gate = InitialStateGate()
        val attempt = gate.startAttempt(InitialState(prompt = "a watercolor scene"))
        assertTrue(attempt.shouldWait)

        val ack = CompletableDeferred<Unit>()
        val deferred = async { attempt.waitForReadiness(ack) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        ack.complete(Unit)
        assertTrue(deferred.await())
    }

    @Test
    fun `does not wait when initial state has both image and prompt set to null`() = runTest(UnconfinedTestDispatcher()) {
        val gate = InitialStateGate()
        val attempt = gate.startAttempt(InitialState(image = null, prompt = null))
        assertFalse(
            "JS-shape passthrough init has nothing to ack; mirrors hasCallerProvidedInitialState",
            attempt.shouldWait,
        )

        val ack = CompletableDeferred<Unit>()
        assertTrue(attempt.waitForReadiness(ack))
    }

    @Test
    fun `returns false when a newer attempt has started`() = runTest(UnconfinedTestDispatcher()) {
        val gate = InitialStateGate()
        val first = gate.startAttempt(InitialState(prompt = "first"))
        val ack = CompletableDeferred<Unit>()

        val deferred = async { first.waitForReadiness(ack) }
        runCurrent()

        gate.startAttempt(InitialState(prompt = "second"))
        ack.complete(Unit)
        assertFalse("superseded attempt must report false", deferred.await())
    }

    @Test
    fun `returns false when gate has been reset`() = runTest(UnconfinedTestDispatcher()) {
        val gate = InitialStateGate()
        val attempt = gate.startAttempt(InitialState(prompt = "first"))
        val ack = CompletableDeferred<Unit>()

        val deferred = async { attempt.waitForReadiness(ack) }
        runCurrent()
        gate.reset()
        ack.complete(Unit)
        assertFalse(deferred.await())
    }
}
