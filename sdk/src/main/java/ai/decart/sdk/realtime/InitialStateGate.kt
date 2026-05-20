package ai.decart.sdk.realtime

import kotlinx.coroutines.Deferred
import java.util.concurrent.atomic.AtomicInteger

internal data class InitialState(
    val image: String? = null,
    val prompt: String? = null,
    val enhance: Boolean? = null,
)

/**
 * Two jobs: detect stale connect attempts after a newer one starts, and
 * hold back remote-stream emission until the initial-state ack resolves
 * (so callers don't see frames from the previous prompt).
 */
internal class InitialStateGate {
    private val attemptId = AtomicInteger(0)

    fun startAttempt(initialState: InitialState?): Attempt {
        val myAttempt = attemptId.incrementAndGet()
        val shouldWait = hasCallerProvidedInitialState(initialState)
        return Attempt(myAttempt = myAttempt, shouldWait = shouldWait)
    }

    fun reset() {
        attemptId.incrementAndGet()
    }

    inner class Attempt internal constructor(
        private val myAttempt: Int,
        val shouldWait: Boolean,
    ) {
        suspend fun waitForReadiness(initialStateAck: Deferred<Unit>): Boolean {
            if (shouldWait) initialStateAck.await()
            return attemptId.get() == myAttempt
        }
    }

    companion object {
        fun hasCallerProvidedInitialState(state: InitialState?): Boolean {
            if (state == null) return false
            return state.image != null || state.prompt != null
        }
    }
}
