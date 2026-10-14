package ai.decart.sdk.realtime

import java.util.concurrent.atomic.AtomicInteger

internal data class InitialState(
    val image: String? = null,
    val prompt: String? = null,
    val enhance: Boolean? = null,
)

/**
 * Detects stale connect attempts: a newer [startAttempt] (or [reset])
 * supersedes any in-flight one, so the loser can bail before marking the
 * session connected. The initial-state ack no longer gates publishing —
 * it is observed out-of-band — so this gate only tracks attempt identity.
 */
internal class InitialStateGate {
    private val attemptId = AtomicInteger(0)

    fun startAttempt(): Attempt = Attempt(attemptId.incrementAndGet())

    fun reset() {
        attemptId.incrementAndGet()
    }

    inner class Attempt internal constructor(private val myAttempt: Int) {
        val isCurrent: Boolean
            get() = attemptId.get() == myAttempt
    }
}
