package ai.decart.sdk.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class AckResult(val success: Boolean, val error: String?)

/**
 * Suspends until a matching ack arrives, the timeout elapses, an external
 * failure ([register] hook fires), or [send] fails.
 *
 * The non-obvious bits:
 *  - Both jobs use [CoroutineStart.UNDISPATCHED] so the listener subscribes
 *    *before* [send] runs — a fast ack on a non-replaying flow would
 *    otherwise be dropped, producing a false timeout.
 *  - [register] runs after the jobs list is populated; the fail-hook fires
 *    from background OkHttp threads and iterates `jobs`.
 *  - The post-register liveness check covers an UNDISPATCHED listener that
 *    resolved synchronously before [register] could run — without it the
 *    fail-hook leaks until cleanup.
 */
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

    register(failHook)
    if (!cont.isActive) {
        jobs.forEach { it.cancel() }
        unregister(failHook)
        return@suspendCancellableCoroutine
    }

    if (!send()) {
        resolve(Result.failure(Exception("WebSocket is not open")))
        return@suspendCancellableCoroutine
    }

    cont.invokeOnCancellation {
        jobs.forEach { it.cancel() }
        unregister(failHook)
    }
}
