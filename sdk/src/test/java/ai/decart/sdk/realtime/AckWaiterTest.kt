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

    private class StubRegistry {
        val hooks = mutableListOf<(Throwable) -> Unit>()
        val register: ((Throwable) -> Unit) -> Unit = { hooks.add(it) }
        val unregister: ((Throwable) -> Unit) -> Unit = { hooks.remove(it) }
    }

    @Test
    fun `resolves successfully when ack reports success`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val registry = StubRegistry()
        var sendInvoked = false

        val job = async {
            awaitAck(
                scope = this@runTest,
                timeoutMs = 10_000,
                timeoutMessage = "timeout",
                ackFailureMessage = "failed",
                register = registry.register,
                unregister = registry.unregister,
                awaitMatchingAck = { ackFlow.first() },
                send = { sendInvoked = true; true },
            )
        }
        runCurrent()

        assertTrue("send must run after subscribers are armed", sendInvoked)
        assertEquals(1, registry.hooks.size)
        assertFalse(job.isCompleted)

        ackFlow.tryEmit(AckResult(success = true, error = null))
        job.await()
        assertTrue("waiter must be unregistered on success", registry.hooks.isEmpty())
    }

    @Test
    fun `ack subscriber is armed before send runs`() = runTest(UnconfinedTestDispatcher()) {
        // Verifies the UNDISPATCHED contract: an ack that arrives during the
        // send call must be delivered to the listener.
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val registry = StubRegistry()

        val job = async {
            awaitAck(
                scope = this@runTest,
                timeoutMs = 10_000,
                timeoutMessage = "timeout",
                ackFailureMessage = "failed",
                register = registry.register,
                unregister = registry.unregister,
                awaitMatchingAck = { ackFlow.first() },
                send = {
                    ackFlow.tryEmit(AckResult(success = true, error = null))
                    true
                },
            )
        }
        runCurrent()

        job.await()
    }

    @Test
    fun `throws ack error when ack reports failure`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val registry = StubRegistry()

        val job = async {
            runCatching {
                awaitAck(
                    scope = this@runTest,
                    timeoutMs = 10_000,
                    timeoutMessage = "timeout",
                    ackFailureMessage = "default-failure",
                    register = registry.register,
                    unregister = registry.unregister,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true },
                )
            }
        }
        runCurrent()

        ackFlow.tryEmit(AckResult(success = false, error = "moderation rejected"))
        val result = job.await()

        assertTrue(result.isFailure)
        assertEquals("moderation rejected", result.exceptionOrNull()?.message)
        assertTrue(registry.hooks.isEmpty())
    }

    @Test
    fun `throws default error when failed ack has no error message`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val registry = StubRegistry()

        val job = async {
            runCatching {
                awaitAck(
                    scope = this@runTest,
                    timeoutMs = 10_000,
                    timeoutMessage = "timeout",
                    ackFailureMessage = "default-failure",
                    register = registry.register,
                    unregister = registry.unregister,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true },
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
        val registry = StubRegistry()
        val target = "match-me"

        val job = async {
            awaitAck(
                scope = this@runTest,
                timeoutMs = 10_000,
                timeoutMessage = "timeout",
                ackFailureMessage = "failed",
                register = registry.register,
                unregister = registry.unregister,
                awaitMatchingAck = {
                    val a = ackFlow.first { it.prompt == target }
                    AckResult(success = a.success, error = a.error)
                },
                send = { true },
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
        val registry = StubRegistry()

        val job = async {
            runCatching {
                awaitAck(
                    scope = this@runTest,
                    timeoutMs = 5_000,
                    timeoutMessage = "Prompt send timed out",
                    ackFailureMessage = "failed",
                    register = registry.register,
                    unregister = registry.unregister,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true },
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
        assertTrue(registry.hooks.isEmpty())
    }

    @Test
    fun `throws WebSocket-is-not-open when send returns false`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val registry = StubRegistry()

        val result = runCatching {
            awaitAck(
                scope = this@runTest,
                timeoutMs = 10_000,
                timeoutMessage = "timeout",
                ackFailureMessage = "failed",
                register = registry.register,
                unregister = registry.unregister,
                awaitMatchingAck = { ackFlow.first() },
                send = { false },
            )
        }

        assertTrue(result.isFailure)
        assertEquals("WebSocket is not open", result.exceptionOrNull()?.message)
        assertTrue(registry.hooks.isEmpty())
    }

    @Test
    fun `external failure hook fails the wait and cancels child jobs`() = runTest(UnconfinedTestDispatcher()) {
        // Simulates resolvePendingWaits walking the registry on websocket
        // close / server error and invoking each waiter's fail hook.
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val registry = StubRegistry()

        val job = async {
            runCatching {
                awaitAck(
                    scope = this@runTest,
                    timeoutMs = 10_000,
                    timeoutMessage = "timeout",
                    ackFailureMessage = "failed",
                    register = registry.register,
                    unregister = registry.unregister,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true },
                )
            }
        }
        runCurrent()

        assertEquals(1, registry.hooks.size)
        registry.hooks[0].invoke(Exception("WebSocket closed"))
        val result = job.await()

        assertTrue(result.isFailure)
        assertEquals("WebSocket closed", result.exceptionOrNull()?.message)
        assertTrue("fail hook must be unregistered", registry.hooks.isEmpty())
    }

    @Test
    fun `late ack arrival after timeout is ignored without leak`() = runTest(UnconfinedTestDispatcher()) {
        val ackFlow = MutableSharedFlow<AckResult>(extraBufferCapacity = 4)
        val registry = StubRegistry()

        val job = async {
            runCatching {
                awaitAck(
                    scope = this@runTest,
                    timeoutMs = 100,
                    timeoutMessage = "timeout",
                    ackFailureMessage = "failed",
                    register = registry.register,
                    unregister = registry.unregister,
                    awaitMatchingAck = { ackFlow.first() },
                    send = { true },
                )
            }
        }

        advanceTimeBy(101)
        val result = job.await()
        assertTrue(result.isFailure)
        assertEquals("timeout", result.exceptionOrNull()?.message)
        assertTrue(registry.hooks.isEmpty())

        ackFlow.tryEmit(AckResult(success = true, error = null))
        runCurrent()
    }
}
