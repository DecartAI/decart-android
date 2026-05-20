package ai.decart.sdk.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class AckResult(val success: Boolean, val error: String?)

internal suspend fun awaitAckWithFailureRace(
    scope: CoroutineScope,
    timeoutMs: Long,
    timeoutMessage: String,
    ackFailureMessage: String,
    failureFlow: Flow<Exception>,
    awaitMatchingAck: suspend () -> AckResult,
    send: () -> Boolean,
): Unit = suspendCancellableCoroutine { cont ->
    val jobs = mutableListOf<Job>()

    fun resolve(result: Result<Unit>) {
        if (!cont.isActive) return
        jobs.forEach { it.cancel() }
        cont.resumeWith(result)
    }

    jobs += scope.launch {
        delay(timeoutMs)
        resolve(Result.failure(Exception(timeoutMessage)))
    }

    jobs += scope.launch {
        val ack = awaitMatchingAck()
        if (ack.success) {
            resolve(Result.success(Unit))
        } else {
            resolve(Result.failure(Exception(ack.error ?: ackFailureMessage)))
        }
    }

    jobs += scope.launch {
        resolve(Result.failure(failureFlow.first()))
    }

    if (!send()) {
        resolve(Result.failure(Exception("WebSocket is not open")))
        return@suspendCancellableCoroutine
    }

    cont.invokeOnCancellation { jobs.forEach { it.cancel() } }
}
