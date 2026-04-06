package ai.decart.sdk

/**
 * Category of input a video model expects.
 * Determines which [ai.decart.sdk.queue.QueueJobInput] subclass to use.
 */
enum class ModelInputType {
    /** Video + prompt + optional reference image ([ai.decart.sdk.queue.VideoEditInput]) */
    VIDEO_EDIT,
    /** Video + (prompt XOR reference image) ([ai.decart.sdk.queue.VideoRestyleInput]) */
    VIDEO_RESTYLE,
    /** Image + trajectory ([ai.decart.sdk.queue.MotionVideoInput]) */
    MOTION_VIDEO,
}

/**
 * Definition for a batch video model that supports the queue (async job) API.
 *
 * @property name The model identifier used in API endpoints (e.g., "lucy-2-v2v")
 * @property jobsUrlPath The URL path for submitting jobs (e.g., "/v1/jobs/lucy-2-v2v")
 * @property fps Output video frame rate
 * @property width Output video width in pixels
 * @property height Output video height in pixels
 * @property inputType The category of input this model expects
 */
data class VideoModel(
    val name: String,
    val jobsUrlPath: String,
    val fps: Int,
    val width: Int,
    val height: Int,
    val inputType: ModelInputType,
)

/**
 * Registry of available batch video models.
 *
 * Usage:
 * ```kotlin
 * val model = VideoModels.LUCY_2_V2V
 * val result = client.queue.submitAndPoll(model, input)
 * ```
 */
object VideoModels {
    // Canonical models
    /** Video-to-video clip editing. Output: 1280x704, 25fps. */
    val LUCY_CLIP = VideoModel("lucy-clip", "/v1/jobs/lucy-clip", 25, 1280, 704, ModelInputType.VIDEO_EDIT)

    /** Video-to-video editing with optional reference image. Output: 1280x720, 20fps. */
    val LUCY_2 = VideoModel("lucy-2", "/v1/jobs/lucy-2", 20, 1280, 720, ModelInputType.VIDEO_EDIT)

    /** Lucy 2.1 video editing with optional reference image. Output: 1088x624, 20fps. */
    val LUCY_2_1 = VideoModel("lucy-2.1", "/v1/jobs/lucy-2.1", 20, 1088, 624, ModelInputType.VIDEO_EDIT)

    /** Video restyling with prompt or reference image. Output: 1280x704, 22fps. */
    val LUCY_RESTYLE_2 = VideoModel("lucy-restyle-2", "/v1/jobs/lucy-restyle-2", 22, 1280, 704, ModelInputType.VIDEO_RESTYLE)

    /** Image-to-motion-video with trajectory. Output: 1280x704, 25fps. */
    val LUCY_MOTION = VideoModel("lucy-motion", "/v1/jobs/lucy-motion", 25, 1280, 704, ModelInputType.MOTION_VIDEO)

    // Latest aliases (server-side resolution)
    val LUCY_LATEST = VideoModel("lucy-latest", "/v1/jobs/lucy-latest", 20, 1088, 624, ModelInputType.VIDEO_EDIT)
    val LUCY_RESTYLE_LATEST = VideoModel("lucy-restyle-latest", "/v1/jobs/lucy-restyle-latest", 22, 1280, 704, ModelInputType.VIDEO_RESTYLE)
    val LUCY_CLIP_LATEST = VideoModel("lucy-clip-latest", "/v1/jobs/lucy-clip-latest", 25, 1280, 704, ModelInputType.VIDEO_EDIT)
    val LUCY_MOTION_LATEST = VideoModel("lucy-motion-latest", "/v1/jobs/lucy-motion-latest", 25, 1280, 704, ModelInputType.MOTION_VIDEO)

    // Deprecated models (old names, still work on the API)
    @Deprecated("Use LUCY_2 instead", replaceWith = ReplaceWith("LUCY_2"))
    val LUCY_2_V2V = VideoModel("lucy-2-v2v", "/v1/jobs/lucy-2-v2v", 20, 1280, 720, ModelInputType.VIDEO_EDIT)

    @Deprecated("Use LUCY_CLIP instead", replaceWith = ReplaceWith("LUCY_CLIP"))
    val LUCY_PRO_V2V = VideoModel("lucy-pro-v2v", "/v1/jobs/lucy-pro-v2v", 25, 1280, 704, ModelInputType.VIDEO_EDIT)

    @Deprecated("Use LUCY_RESTYLE_2 instead", replaceWith = ReplaceWith("LUCY_RESTYLE_2"))
    val LUCY_RESTYLE_V2V = VideoModel("lucy-restyle-v2v", "/v1/jobs/lucy-restyle-v2v", 22, 1280, 704, ModelInputType.VIDEO_RESTYLE)

    /** Get model by name, or null if not found */
    fun fromName(name: String): VideoModel? = allIncludingDeprecated.find { it.name == name }

    /** All available video models (canonical + latest) */
    val all: List<VideoModel> = listOf(
        LUCY_CLIP,
        LUCY_2,
        LUCY_2_1,
        LUCY_RESTYLE_2,
        LUCY_MOTION,
        LUCY_LATEST,
        LUCY_RESTYLE_LATEST,
        LUCY_CLIP_LATEST,
        LUCY_MOTION_LATEST,
    )

    /** All models including deprecated names */
    @Suppress("DEPRECATION")
    val allIncludingDeprecated: List<VideoModel> = listOf(
        LUCY_CLIP, LUCY_2, LUCY_2_1, LUCY_RESTYLE_2, LUCY_MOTION,
        LUCY_LATEST, LUCY_RESTYLE_LATEST, LUCY_CLIP_LATEST, LUCY_MOTION_LATEST,
        LUCY_2_V2V, LUCY_PRO_V2V, LUCY_RESTYLE_V2V,
    )
}
