package ai.decart.sdk

data class RealtimeModel(
    val name: String,
    val urlPath: String,
    val fps: Int,
    val width: Int,
    val height: Int
)

object RealtimeModels {
    // Canonical models
    val LUCY_2_1 = RealtimeModel("lucy-2.1", "/v1/stream", 30, 1088, 624)
    val LUCY_2_1_VTON = RealtimeModel("lucy-2.1-vton", "/v1/stream", 30, 1088, 624)
    val LUCY_VTON_2 = RealtimeModel("lucy-vton-2", "/v1/stream", 30, 1088, 624)
    val LUCY_VTON_3 = RealtimeModel("lucy-vton-3", "/v1/stream", 30, 1088, 624)
    val LUCY_RESTYLE_2 = RealtimeModel("lucy-restyle-2", "/v1/stream", 30, 1280, 704)

    // Latest aliases (server-side resolution)
    val LUCY_LATEST = RealtimeModel("lucy-latest", "/v1/stream", 30, 1088, 624)
    val LUCY_VTON_LATEST = RealtimeModel("lucy-vton-latest", "/v1/stream", 30, 1088, 624)
    val LUCY_RESTYLE_LATEST = RealtimeModel("lucy-restyle-latest", "/v1/stream", 30, 1280, 704)

    // Deprecated models (old names, still work on the API)
    @Deprecated("Use LUCY_RESTYLE_2 instead", replaceWith = ReplaceWith("LUCY_RESTYLE_2"))
    val MIRAGE_V2 = RealtimeModel("mirage_v2", "/v1/stream", 30, 1280, 704)

    @Deprecated("Use LUCY_VTON_2 instead", replaceWith = ReplaceWith("LUCY_VTON_2"))
    val LUCY_VTON = RealtimeModel("lucy-vton", "/v1/stream", 30, 1088, 624)

    @Deprecated("Use LUCY_VTON_2 instead", replaceWith = ReplaceWith("LUCY_VTON_2"))
    val LUCY_2_1_VTON_2 = RealtimeModel("lucy-2.1-vton-2", "/v1/stream", 30, 1088, 624)

    /** Get model by name, or null if not found */
    @Suppress("DEPRECATION")
    fun fromName(name: String): RealtimeModel? = when (name) {
        // Canonical names
        "lucy-2.1" -> LUCY_2_1
        "lucy-2.1-vton" -> LUCY_2_1_VTON
        "lucy-vton-2" -> LUCY_VTON_2
        "lucy-vton-3" -> LUCY_VTON_3
        "lucy-restyle-2" -> LUCY_RESTYLE_2
        // Latest aliases
        "lucy-latest" -> LUCY_LATEST
        "lucy-vton-latest" -> LUCY_VTON_LATEST
        "lucy-restyle-latest" -> LUCY_RESTYLE_LATEST
        // Deprecated names
        "mirage_v2" -> MIRAGE_V2
        "lucy-vton" -> LUCY_VTON
        "lucy-2.1-vton-2" -> LUCY_2_1_VTON_2
        else -> null
    }

    /** All available realtime models (canonical only) */
    val all: List<RealtimeModel> = listOf(
        LUCY_2_1, LUCY_2_1_VTON, LUCY_VTON_2, LUCY_VTON_3, LUCY_RESTYLE_2,
        LUCY_LATEST, LUCY_VTON_LATEST, LUCY_RESTYLE_LATEST,
    )
}
