@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.decart.sdk.realtime

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AckWaiterTest {

    @Test
    fun `resolves successfully when ack reports success`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val failureFlow = MutableSharedFlow<Exception>(extraBufferCapacity = 4)
        var sendInvoked = false

        val job = async {
            awaitAckWithFailureRace(
                scope = this@runTest,
                timeoutMs = 10_000,
                timeoutMessage = "timeout",
                ackFailureMessage = "failed",
                failureFlow = failureFlow,
                awaitMatchingAck = { ackFlow.first() },
                send = { sendInvoked = true; true }
            )
        }

        runCurrent()
        assertTrue("send must be invoked synchronously after subscribers are armed", sendInvoked)
        assertFalse(job.isCompleted)

        ackFlow.tryEmit(AckResult(success = true, error = null))
        job.await() // resumes Unit
    }

    @Test
    fun `throws ack error when ack reports failure`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val failureFlow = MutableSharedFlow<Exception>(extraBufferCapacity = 4)

        val job = async {
            runCatching {
                awaitAckWithFailureRace(
                    scope = this@runTest,
                    timeoutMs = 10_000,
                    timeoutMessage = "timeout",
                    ackFailureMessage = "default-failure",
                    failureFlow = failureFlow,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true }
                )
            }
        }
        runCurrent()

        ackFlow.tryEmit(AckResult(success = false, error = "moderation rejected"))
        val result = job.await()

        assertTrue(result.isFailure)
        assertEquals("moderation rejected", result.exceptionOrNull()?.message)
    }

    @Test
    fun `throws default error when failed ack has no error message`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val failureFlow = MutableSharedFlow<Exception>(extraBufferCapacity = 4)

        val job = async {
            runCatching {
                awaitAckWithFailureRace(
                    scope = this@runTest,
                    timeoutMs = 10_000,
                    timeoutMessage = "timeout",
                    ackFailureMessage = "default-failure",
                    failureFlow = failureFlow,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true }
                )
            }
        }
        runCurrent()

        ackFlow.tryEmit(AckResult(success = false, error = null))
        val result = job.await()

        assertTrue(result.isFailure)
        assertEquals("default-failure", result.exceptionOrNull()?.message)
    }

    @Test
    fun `awaitMatchingAck predicate filters out non-matching acks`() = runTest(UnconfinedTestDispatcher()) {
        data class PromptAck(val prompt: String, val success: Boolean, val error: String?)
        val ackFlow = MutableSharedFlow<PromptAck>(extraBufferCapacity = 4)
        val failureFlow = MutableSharedFlow<Exception>(extraBufferCapacity = 4)
        val target = "match-me"

        val job = async {
            awaitAckWithFailureRace(
                scope = this@runTest,
                timeoutMs = 10_000,
                timeoutMessage = "timeout",
                ackFailureMessage = "failed",
                failureFlow = failureFlow,
                awaitMatchingAck = {
                    val a = ackFlow.first { it.prompt == target }
                    AckResult(success = a.success, error = a.error)
                },
                send = { true }
            )
        }
        runCurrent()

        ackFlow.tryEmit(PromptAck(prompt = "other", success = true, error = null))
        runCurrent()
        assertFalse("non-matching ack must not resolve the wait", job.isCompleted)

        ackFlow.tryEmit(PromptAck(prompt = target, success = true, error = null))
        job.await()
    }

    @Test
    fun `throws timeout exception when no ack arrives in time`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val failureFlow = MutableSharedFlow<Exception>(extraBufferCapacity = 4)

        val job = async {
            runCatching {
                awaitAckWithFailureRace(
                    scope = this@runTest,
                    timeoutMs = 5_000,
                    timeoutMessage = "Prompt send timed out",
                    ackFailureMessage = "failed",
                    failureFlow = failureFlow,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true }
                )
            }
        }
        runCurrent()
        assertFalse(job.isCompleted)

        advanceTimeBy(4_999)
        assertFalse(job.isCompleted)

        advanceTimeBy(2)
        val result = job.await()

        assertTrue(result.isFailure)
        assertEquals("Prompt send timed out", result.exceptionOrNull()?.message)
    }

    @Test
    fun `throws WebSocket-is-not-open when send returns false`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val failureFlow = MutableSharedFlow<Exception>(extraBufferCapacity = 4)

        val result = runCatching {
            awaitAckWithFailureRace(
                scope = this@runTest,
                timeoutMs = 10_000,
                timeoutMessage = "timeout",
                ackFailureMessage = "failed",
                failureFlow = failureFlow,
                awaitMatchingAck = { ackFlow.first() },
                send = { false }
            )
        }

        assertTrue(result.isFailure)
        assertEquals("WebSocket is not open", result.exceptionOrNull()?.message)
    }

    @Test
    fun `propagates failure when failureFlow emits before ack`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val failureFlow = MutableSharedFlow<Exception>(extraBufferCapacity = 4)

        val job = async {
            runCatching {
                awaitAckWithFailureRace(
                    scope = this@runTest,
                    timeoutMs = 10_000,
                    timeoutMessage = "timeout",
                    ackFailureMessage = "failed",
                    failureFlow = failureFlow,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true }
                )
            }
        }
        runCurrent()

        failureFlow.tryEmit(Exception("WebSocket closed"))
        val result = job.await()

        assertTrue(result.isFailure)
        assertEquals("WebSocket closed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `late ack arrival after timeout is ignored without leak`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val failureFlow = MutableSharedFlow<Exception>(extraBufferCapacity = 4)

        val job = async {
            runCatching {
                awaitAckWithFailureRace(
                    scope = this@runTest,
                    timeoutMs = 100,
                    timeoutMessage = "timeout",
                    ackFailureMessage = "failed",
                    failureFlow = failureFlow,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true }
                )
            }
        }

        advanceTimeBy(101)
        val result = job.await()
        assertTrue(result.isFailure)
        assertEquals("timeout", result.exceptionOrNull()?.message)

        // Late ack must not throw or affect state.
        ackFlow.tryEmit(AckResult(success = true, error = null))
        runCurrent()
    }
}
