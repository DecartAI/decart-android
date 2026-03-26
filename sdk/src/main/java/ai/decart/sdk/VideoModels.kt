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
    /** Video-to-video editing with optional reference image. Output: 1280x720, 20fps. */
    val LUCY_2_V2V = VideoModel("lucy-2-v2v", "/v1/jobs/lucy-2-v2v", 20, 1280, 720, ModelInputType.VIDEO_EDIT)

    /** Video-to-video (Pro quality). Output: 1280x704, 25fps. */
    val LUCY_PRO_V2V = VideoModel("lucy-pro-v2v", "/v1/jobs/lucy-pro-v2v", 25, 1280, 704, ModelInputType.VIDEO_EDIT)

    /** Video restyling with prompt or reference image. Output: 1280x704, 25fps. */
    val LUCY_RESTYLE_V2V = VideoModel("lucy-restyle-v2v", "/v1/jobs/lucy-restyle-v2v", 25, 1280, 704, ModelInputType.VIDEO_RESTYLE)

    /** Image-to-motion-video with trajectory. Output: 1280x704, 25fps. */
    val LUCY_MOTION = VideoModel("lucy-motion", "/v1/jobs/lucy-motion", 25, 1280, 704, ModelInputType.MOTION_VIDEO)

    /** Get model by name, or null if not found */
    fun fromName(name: String): VideoModel? = all.find { it.name == name }

    /** All available video models */
    val all: List<VideoModel> = listOf(
        LUCY_2_V2V,
        LUCY_PRO_V2V,
        LUCY_RESTYLE_V2V,
        LUCY_MOTION,
    )
}
