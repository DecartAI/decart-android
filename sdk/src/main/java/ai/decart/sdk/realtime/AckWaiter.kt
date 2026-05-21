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
 *    before [send] runs. A fast ack on a non-replaying flow would
 *    otherwise be dropped, producing a false timeout.
 *  - [register] runs after the jobs list is populated. The fail-hook can
 *    fire from background OkHttp threads and iterates `jobs`.
 *  - The post-register liveness check covers an UNDISPATCHED listener that
 *    resolved synchronously before [register] could run; without it the
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

    // Register only after jobs is fully populated. The failure path runs on
    // OkHttp threads and iterates jobs.forEach; exposing an incomplete list
    // would race with this thread's additions.
    register(failHook)
    // If a UNDISPATCHED listener already ran synchronously (buffered ack, or
    // timeoutMs <= 0) and resumed cont before register, the hook and any
    // pending jobs would otherwise leak.
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
