package ai.decart.sdk.realtime

/** Caller-provided initial prompt; see [ConnectOptions.initialPrompt]. */
data class InitialPrompt(
    val text: String,
    val enhance: Boolean = true,
)
