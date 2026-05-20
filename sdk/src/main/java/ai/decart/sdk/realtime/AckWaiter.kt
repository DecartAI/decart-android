package ai.decart.sdk.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class AckResult(val success: Boolean, val error: String?)

// Two correctness notes:
// - UNDISPATCHED start makes the listener subscribe to its SharedFlow before
//   `send()` runs. Without it, a fast ack on a non-replaying flow can be
//   delivered before the collector exists and get dropped (false timeout).
// - The caller-provided [register] hook gets a fail callback that the
//   external failure paths (websocket close, server error, cleanup) can
//   invoke synchronously. The callback routes through `resolve()` so the
//   helper's own child jobs are cancelled, avoiding leaks across cleanup +
//   cancelChildren on a single-threaded dispatcher.
internal suspend fun awaitAck(
    scope: CoroutineScope,
    timeoutMs: Long,
    timeoutMessage: String,
    ackFailureMessage: String,
    register: ((Throwable) -> Unit) -> Unit,
    unregister: ((Throwable) -> Unit) -> Unit,
    awaitMatchingAck: suspend () -> AckResult,
    send: () -> Boolean,
): Unit = suspendCancellableCoroutine { cont ->
    val jobs = mutableListOf<Job>()
    lateinit var failHook: (Throwable) -> Unit

    fun resolve(result: Result<Unit>) {
        if (!cont.isActive) return
        jobs.forEach { it.cancel() }
        unregister(failHook)
        try {
            cont.resumeWith(result)
        } catch (_: IllegalStateException) {
            // already resumed by a race winner
        }
    }

    failHook = { resolve(Result.failure(it)) }

    jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
        delay(timeoutMs)
        resolve(Result.failure(Exception(timeoutMessage)))
    }

    jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val ack = awaitMatchingAck()
        if (ack.success) resolve(Result.success(Unit))
        else resolve(Result.failure(Exception(ack.error ?: ackFailureMessage)))
    }

    // Register only after jobs is fully populated. The failure path runs on
    // OkHttp threads and iterates jobs.forEach — exposing an incomplete list
    // would race with this thread's additions.
    register(failHook)

    if (!send()) {
        resolve(Result.failure(Exception("WebSocket is not open")))
        return@suspendCancellableCoroutine
    }

    cont.invokeOnCancellation {
        jobs.forEach { it.cancel() }
        unregister(failHook)
    }
}
