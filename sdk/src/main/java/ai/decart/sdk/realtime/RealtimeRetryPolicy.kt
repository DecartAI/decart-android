package ai.decart.sdk.realtime

/** Shared with the JS and iOS SDKs; kept here so per-session overrides stay easy. */
internal object RealtimeRetryPolicy {
    const val MAX_ATTEMPTS: Int = 5
    const val INITIAL_DELAY_MS: Long = 1_000L
    const val MAX_DELAY_MS: Long = 10_000L
    const val BACKOFF_FACTOR: Double = 2.0

    private val PERMANENT_ERROR_SUBSTRINGS: List<String> = listOf(
        "permission denied",
        "not allowed",
        "invalid session",
        "401",
        "invalid api key",
        "unauthorized",
    )

    fun isPermanentError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lowered = message.lowercase()
        return PERMANENT_ERROR_SUBSTRINGS.any { lowered.contains(it) }
    }

    fun delayMsFor(attempt: Int): Long {
        val raw = INITIAL_DELAY_MS * Math.pow(BACKOFF_FACTOR, attempt.toDouble())
        return raw.toLong().coerceAtMost(MAX_DELAY_MS).coerceAtLeast(INITIAL_DELAY_MS)
    }
}
