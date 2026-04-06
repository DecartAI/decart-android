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
    val LUCY = RealtimeModel("lucy", "/v1/stream", 25, 1280, 704)
    val LUCY_2 = RealtimeModel("lucy-2", "/v1/stream", 20, 1280, 720)
    val LUCY_2_1 = RealtimeModel("lucy-2.1", "/v1/stream", 20, 1088, 624)
    val LUCY_2_1_VTON = RealtimeModel("lucy-2.1-vton", "/v1/stream", 20, 1088, 624)
    val LUCY_RESTYLE = RealtimeModel("lucy-restyle", "/v1/stream", 25, 1280, 704)
    val LUCY_RESTYLE_2 = RealtimeModel("lucy-restyle-2", "/v1/stream", 22, 1280, 704)
    val LIVE_AVATAR = RealtimeModel("live-avatar", "/v1/stream", 25, 1280, 720)

    // Latest aliases (server-side resolution)
    val LUCY_LATEST = RealtimeModel("lucy-latest", "/v1/stream", 20, 1088, 624)
    val LUCY_VTON_LATEST = RealtimeModel("lucy-vton-latest", "/v1/stream", 20, 1088, 624)
    val LUCY_RESTYLE_LATEST = RealtimeModel("lucy-restyle-latest", "/v1/stream", 22, 1280, 704)

    // Deprecated models (old names, still work on the API)
    @Deprecated("Use LUCY_RESTYLE instead", replaceWith = ReplaceWith("LUCY_RESTYLE"))
    val MIRAGE = RealtimeModel("mirage", "/v1/stream", 25, 1280, 704)
    @Deprecated("Use LUCY_RESTYLE_2 instead", replaceWith = ReplaceWith("LUCY_RESTYLE_2"))
    val MIRAGE_V2 = RealtimeModel("mirage_v2", "/v1/stream", 22, 1280, 704)
    @Deprecated("Use LUCY instead", replaceWith = ReplaceWith("LUCY"))
    val LUCY_V2V_720P_RT = RealtimeModel("lucy_v2v_720p_rt", "/v1/stream", 25, 1280, 704)
    @Deprecated("Use LUCY_2 instead", replaceWith = ReplaceWith("LUCY_2"))
    val LUCY_2_RT = RealtimeModel("lucy_2_rt", "/v1/stream", 20, 1280, 720)
    @Deprecated("Use LIVE_AVATAR instead", replaceWith = ReplaceWith("LIVE_AVATAR"))
    val LIVE_AVATAR_LEGACY = RealtimeModel("live_avatar", "/v1/stream", 25, 1280, 720)

    /** Get model by name, or null if not found */
    fun fromName(name: String): RealtimeModel? = when (name) {
        // Canonical names
        "lucy" -> LUCY
        "lucy-2" -> LUCY_2
        "lucy-2.1" -> LUCY_2_1
        "lucy-2.1-vton" -> LUCY_2_1_VTON
        "lucy-restyle" -> LUCY_RESTYLE
        "lucy-restyle-2" -> LUCY_RESTYLE_2
        "live-avatar" -> LIVE_AVATAR
        // Latest aliases
        "lucy-latest" -> LUCY_LATEST
        "lucy-vton-latest" -> LUCY_VTON_LATEST
        "lucy-restyle-latest" -> LUCY_RESTYLE_LATEST
        // Deprecated names
        @Suppress("DEPRECATION") "mirage" -> MIRAGE
        @Suppress("DEPRECATION") "mirage_v2" -> MIRAGE_V2
        @Suppress("DEPRECATION") "lucy_v2v_720p_rt" -> LUCY_V2V_720P_RT
        @Suppress("DEPRECATION") "lucy_2_rt" -> LUCY_2_RT
        @Suppress("DEPRECATION") "live_avatar" -> LIVE_AVATAR_LEGACY
        else -> null
    }

    /** All available realtime models (canonical only) */
    val all: List<RealtimeModel> = listOf(
        LUCY, LUCY_2, LUCY_2_1, LUCY_2_1_VTON, LUCY_RESTYLE, LUCY_RESTYLE_2, LIVE_AVATAR,
        LUCY_LATEST, LUCY_VTON_LATEST, LUCY_RESTYLE_LATEST,
    )
}
