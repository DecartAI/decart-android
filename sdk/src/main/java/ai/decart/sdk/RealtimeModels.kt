package ai.decart.sdk

import ai.decart.sdk.realtime.Speed

data class RealtimeModel(
    val name: String,
    val urlPath: String,
    val fps: Int,
    val width: Int,
    val height: Int,
    /**
     * Compute tiers this model can be served from via `ConnectOptions.speed`.
     * Empty (default) means standard mode only; the server ignores `speed` for
     * models that do not declare the tier.
     */
    val supportedSpeeds: Set<Speed> = emptySet(),
)

object RealtimeModels {
    // Canonical models
    val LUCY_2_1 = RealtimeModel("lucy-2.1", "/v1/stream", 30, 1088, 624)
    val LUCY_2_5 = RealtimeModel("lucy-2.5", "/v1/stream", 30, 1280, 720, setOf(Speed.FAST))
    val LUCY_VTON_3_5 = RealtimeModel("lucy-vton-3.5", "/v1/stream", 30, 1280, 720, setOf(Speed.FAST))
    val LUCY_RESTYLE_2 = RealtimeModel("lucy-restyle-2", "/v1/stream", 30, 1280, 704)

    // Latest aliases (server-side resolution)
    val LUCY_LATEST = RealtimeModel("lucy-latest", "/v1/stream", 30, 1088, 624, setOf(Speed.FAST))
    val LUCY_VTON_LATEST = RealtimeModel("lucy-vton-latest", "/v1/stream", 30, 1280, 720, setOf(Speed.FAST))
    val LUCY_RESTYLE_LATEST = RealtimeModel("lucy-restyle-latest", "/v1/stream", 30, 1280, 704)

    /** Get model by name, or null if not found */
    fun fromName(name: String): RealtimeModel? = when (name) {
        // Canonical names
        "lucy-2.1" -> LUCY_2_1
        "lucy-2.5" -> LUCY_2_5
        "lucy-vton-3.5" -> LUCY_VTON_3_5
        "lucy-restyle-2" -> LUCY_RESTYLE_2
        // Latest aliases
        "lucy-latest" -> LUCY_LATEST
        "lucy-vton-latest" -> LUCY_VTON_LATEST
        "lucy-restyle-latest" -> LUCY_RESTYLE_LATEST
        else -> null
    }

    /** All available realtime models (canonical only) */
    val all: List<RealtimeModel> = listOf(
        LUCY_2_1, LUCY_2_5, LUCY_VTON_3_5, LUCY_RESTYLE_2,
        LUCY_LATEST, LUCY_VTON_LATEST, LUCY_RESTYLE_LATEST,
    )
}
