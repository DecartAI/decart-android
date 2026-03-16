package ai.decart.sdk.queue

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Upload progress
// ---------------------------------------------------------------------------

/**
 * Listener for tracking upload progress during job submission.
 *
 * Called on the OkHttp I/O thread as bytes are written to the network.
 * Implementations should be lightweight — avoid blocking or heavy UI work directly.
 *
 * @see QueueClient.submit
 * @see QueueClient.submitAndPoll
 * @see QueueClient.submitAndObserve
 */
fun interface UploadProgressListener {
    /**
     * Called as bytes are written to the upload stream.
     *
     * @param bytesWritten Total bytes written so far
     * @param totalBytes Total content length, or -1 if unknown (e.g., [FileInput.FromInputStream])
     */
    fun onProgress(bytesWritten: Long, totalBytes: Long)
}

// ---------------------------------------------------------------------------
// Job status
// ---------------------------------------------------------------------------

/**
 * Status of a queue job.
 */
@Serializable
enum class JobStatus {
    @SerialName("pending") PENDING,
    @SerialName("processing") PROCESSING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED;
}

// ---------------------------------------------------------------------------
// API responses
// ---------------------------------------------------------------------------

/**
 * Response from submitting a job (`POST /v1/jobs/{model}`).
 */
@Serializable
data class JobSubmitResponse(
    @SerialName("job_id") val jobId: String,
    val status: JobStatus,
)

/**
 * Response from checking job status (`GET /v1/jobs/{job_id}`).
 */
@Serializable
data class JobStatusResponse(
    @SerialName("job_id") val jobId: String,
    val status: JobStatus,
)

// ---------------------------------------------------------------------------
// Job result (sealed — Kotlin's discriminated union)
// ---------------------------------------------------------------------------

/**
 * Result or progress update from a queue job.
 *
 * - [InProgress] — emitted by [QueueClient.submitAndObserve] on each poll while the job is pending/processing.
 * - [Completed] — terminal: contains the output video bytes.
 * - [Failed] — terminal: contains the error message.
 *
 * Returned by [QueueClient.submitAndPoll] (terminal only) and emitted by [QueueClient.submitAndObserve] (all).
 */
sealed class QueueJobResult {
    /** Job is still running. Emitted by [QueueClient.submitAndObserve] during polling. */
    data class InProgress(val jobId: String, val status: JobStatus) : QueueJobResult()

    /** Job completed successfully. [data] contains the output video bytes (MP4). */
    data class Completed(val jobId: String, val data: ByteArray) : QueueJobResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Completed) return false
            return jobId == other.jobId && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = jobId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /** Job failed on the server side. */
    data class Failed(val jobId: String, val error: String) : QueueJobResult()
}

// ---------------------------------------------------------------------------
// Job inputs — one sealed interface + data class per model category
// ---------------------------------------------------------------------------

/**
 * Marker interface for all queue job input types.
 *
 * Each concrete input class knows which form fields to produce via [toFormFields].
 * File fields return [FileInput] values; all others return [String].
 */
interface QueueJobInput {
    /**
     * Convert this input to a map of form field name → value.
     * - [FileInput] values are uploaded as binary multipart parts.
     * - [String] values are sent as text form fields.
     * - `null` values are omitted.
     */
    fun toFormFields(): Map<String, Any?>
}

/**
 * Input for `lucy-2-v2v` (video-to-video editing).
 *
 * [prompt] is required but may be an empty string (server accepts `""`).
 * [referenceImage] is optional and provides style guidance.
 *
 * @property prompt Text prompt (required, max 1000 chars, can be empty)
 * @property data Video file to transform (required)
 * @property referenceImage Optional reference image for style guidance
 * @property seed Optional seed for reproducible output (0–4294967295)
 * @property resolution Output resolution (default "720p")
 * @property enhancePrompt Whether to AI-enhance the prompt (default true)
 */
data class VideoEditInput(
    val prompt: String,
    val data: FileInput,
    val referenceImage: FileInput? = null,
    val seed: Int? = null,
    val resolution: String? = null,
    val enhancePrompt: Boolean? = null,
) : QueueJobInput {

    override fun toFormFields(): Map<String, Any?> = buildMap {
        put("prompt", prompt)
        put("data", data)
        referenceImage?.let { put("reference_image", it) }
        seed?.let { put("seed", it.toString()) }
        resolution?.let { put("resolution", it) }
        enhancePrompt?.let { put("enhance_prompt", it.toString()) }
    }
}

/**
 * Input for video restyle models (`lucy-restyle-v2v`).
 *
 * Must provide exactly ONE of [prompt] or [referenceImage] (mutually exclusive).
 * When using [referenceImage], [enhancePrompt] must not be `true`.
 *
 * @property data Video file to restyle (required)
 * @property prompt Text prompt describing the desired style
 * @property referenceImage Reference image to derive the style from
 * @property seed Optional seed for reproducible output (0–4294967295)
 * @property resolution Output resolution (default "720p")
 * @property enhancePrompt Whether to AI-enhance the prompt (only valid with [prompt])
 */
data class VideoRestyleInput(
    val data: FileInput,
    val prompt: String? = null,
    val referenceImage: FileInput? = null,
    val seed: Int? = null,
    val resolution: String? = null,
    val enhancePrompt: Boolean? = null,
) : QueueJobInput {

    init {
        require(prompt != null || referenceImage != null) {
            "Must provide either prompt or referenceImage"
        }
        require(!(prompt != null && referenceImage != null)) {
            "Cannot provide both prompt and referenceImage — they are mutually exclusive"
        }
        if (referenceImage != null) {
            require(enhancePrompt != true) {
                "enhancePrompt is not supported when using referenceImage"
            }
        }
    }

    override fun toFormFields(): Map<String, Any?> = buildMap {
        put("data", data)
        prompt?.let { put("prompt", it) }
        referenceImage?.let { put("reference_image", it) }
        seed?.let { put("seed", it.toString()) }
        resolution?.let { put("resolution", it) }
        enhancePrompt?.let { put("enhance_prompt", it.toString()) }
    }
}

/**
 * Input for text-to-video models (`lucy-pro-t2v`).
 *
 * Generates video purely from a text prompt — no media file required.
 *
 * @property prompt Text prompt describing the video to generate (required, max 1000 chars)
 * @property seed Optional seed for reproducible output (0–4294967295)
 * @property resolution Output resolution ("720p" or "480p", default "720p")
 * @property orientation Video orientation ("landscape" or "portrait", default "landscape")
 * @property enhancePrompt Whether to AI-enhance the prompt (default true)
 */
data class TextToVideoInput(
    val prompt: String,
    val seed: Int? = null,
    val resolution: String? = null,
    val orientation: String? = null,
    val enhancePrompt: Boolean? = null,
) : QueueJobInput {

    override fun toFormFields(): Map<String, Any?> = buildMap {
        put("prompt", prompt)
        seed?.let { put("seed", it.toString()) }
        resolution?.let { put("resolution", it) }
        orientation?.let { put("orientation", it) }
        enhancePrompt?.let { put("enhance_prompt", it.toString()) }
    }
}

/**
 * Input for image-to-video models (`lucy-pro-i2v`, `lucy-dev-i2v`).
 *
 * Generates a video from a still image guided by a text prompt.
 *
 * @property prompt Text prompt describing the desired video (required, max 1000 chars)
 * @property data Image file to animate (required)
 * @property seed Optional seed for reproducible output (0–4294967295)
 * @property resolution Output resolution ("720p" or "480p", default "720p")
 * @property enhancePrompt Whether to AI-enhance the prompt (default true)
 */
data class ImageToVideoInput(
    val prompt: String,
    val data: FileInput,
    val seed: Int? = null,
    val resolution: String? = null,
    val enhancePrompt: Boolean? = null,
) : QueueJobInput {

    override fun toFormFields(): Map<String, Any?> = buildMap {
        put("prompt", prompt)
        put("data", data)
        seed?.let { put("seed", it.toString()) }
        resolution?.let { put("resolution", it) }
        enhancePrompt?.let { put("enhance_prompt", it.toString()) }
    }
}

/**
 * A point in a motion trajectory for [MotionVideoInput].
 *
 * Coordinates are normalized to 0–1 range relative to the image dimensions.
 *
 * @property frame Frame number (≥ 0)
 * @property x Normalized x coordinate (0.0–1.0)
 * @property y Normalized y coordinate (0.0–1.0)
 */
data class TrajectoryPoint(
    val frame: Int,
    val x: Float,
    val y: Float,
)

/**
 * Input for motion video models (`lucy-motion`).
 *
 * Animates a detected object in the image along the specified trajectory.
 * The object at the first trajectory point is auto-detected — no text prompt is needed.
 *
 * @property data Image file containing the object to animate (required)
 * @property trajectory Path for the object to follow (2–1000 points)
 * @property seed Optional seed for reproducible output (0–4294967295)
 * @property resolution Output resolution (default "720p")
 */
data class MotionVideoInput(
    val data: FileInput,
    val trajectory: List<TrajectoryPoint>,
    val seed: Int? = null,
    val resolution: String? = null,
) : QueueJobInput {

    init {
        require(trajectory.size >= 2) { "Trajectory must have at least 2 points" }
        require(trajectory.size <= 1000) { "Trajectory must have at most 1000 points" }
    }

    override fun toFormFields(): Map<String, Any?> = buildMap {
        put("data", data)
        val json = trajectory.joinToString(",", "[", "]") { pt ->
            """{"frame":${pt.frame},"x":${pt.x},"y":${pt.y}}"""
        }
        put("trajectory", json)
        seed?.let { put("seed", it.toString()) }
        resolution?.let { put("resolution", it) }
    }
}

// ---------------------------------------------------------------------------
// Exceptions
// ---------------------------------------------------------------------------

/**
 * Base exception for queue API errors.
 */
open class QueueException(
    message: String,
    /** HTTP status code, if the error originated from an HTTP response. */
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Thrown when job submission fails (POST /v1/jobs/{model}). */
class QueueSubmitException(
    message: String,
    statusCode: Int? = null,
    cause: Throwable? = null,
) : QueueException(message, statusCode, cause)

/** Thrown when checking job status fails (GET /v1/jobs/{job_id}). */
class QueueStatusException(
    message: String,
    statusCode: Int? = null,
    cause: Throwable? = null,
) : QueueException(message, statusCode, cause)

/** Thrown when downloading the job result fails (GET /v1/jobs/{job_id}/content). */
class QueueResultException(
    message: String,
    statusCode: Int? = null,
    cause: Throwable? = null,
) : QueueException(message, statusCode, cause)

/** Thrown when job inputs fail validation before submission. */
class InvalidInputException(message: String) : QueueException(message)
