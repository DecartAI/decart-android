package ai.decart.sdk.queue

import ai.decart.sdk.Logger
import ai.decart.sdk.VideoModel
import android.content.ContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the Decart batch video queue API.
 *
 * Submits video generation jobs and polls for results asynchronously.
 * All methods are suspend functions safe to call from any coroutine context.
 *
 * Usage:
 * ```kotlin
 * val client = DecartClient(context, DecartClientConfig(apiKey = "..."))
 *
 * // Option 1: Submit and auto-poll
 * val result = client.queue.submitAndPoll(
 *     model = VideoModels.LUCY_2_V2V,
 *     input = VideoEditInput(
 *         prompt = "Transform to anime style",
 *         data = FileInput.fromUri(videoUri),
 *     ),
 *     onStatusChange = { status -> Log.d("Queue", "Status: ${status.status}") }
 * )
 * when (result) {
 *     is QueueJobResult.Completed -> saveVideo(result.data)
 *     is QueueJobResult.Failed -> showError(result.error)
 * }
 *
 * // Option 2: Observe as Flow (emits progress + terminal result)
 * client.queue.submitAndObserve(VideoModels.LUCY_2_V2V, input)
 *     .collect { update ->
 *         when (update) {
 *             is QueueJobResult.InProgress -> showProgress(update.status)
 *             is QueueJobResult.Completed  -> saveVideo(update.data)
 *             is QueueJobResult.Failed     -> showError(update.error)
 *         }
 *     }
 *
 * // Option 3: Manual control
 * val job = client.queue.submit(VideoModels.LUCY_2_V2V, input)
 * // ... poll manually ...
 * val status = client.queue.status(job.jobId)
 * val bytes = client.queue.result(job.jobId)
 * ```
 */
class QueueClient internal constructor(
    private val apiKey: String,
    private val baseUrl: String,
    private val logger: Logger,
    private val contentResolver: ContentResolver?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Submit a video generation job. Returns immediately with the job ID.
     *
     * @param model The video model to use (e.g., [ai.decart.sdk.VideoModels.LUCY_2_V2V])
     * @param input The job input (e.g., [VideoEditInput])
     * @param onUploadProgress Optional listener invoked as the request body is written to the network.
     *   Useful for showing upload progress on large video files.
     * @return [JobSubmitResponse] with `jobId` and initial status
     * @throws InvalidInputException if inputs fail validation
     * @throws QueueSubmitException if the HTTP request fails
     */
    suspend fun submit(
        model: VideoModel,
        input: QueueJobInput,
        onUploadProgress: UploadProgressListener? = null,
    ): JobSubmitResponse = withContext(Dispatchers.IO) {
        var body: RequestBody = buildMultipartBody(input)
        if (onUploadProgress != null) {
            body = ProgressRequestBody(body, onUploadProgress)
        }
        val url = "$baseUrl${model.jobsUrlPath}"

        logger.debug("Queue: submitting job to $url")

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("X-API-KEY", apiKey)
            .addHeader("User-Agent", buildUserAgent())
            .build()

        executeRequest(request) { responseBody ->
            try {
                json.decodeFromString<JobSubmitResponse>(responseBody)
            } catch (e: Exception) {
                throw QueueSubmitException("Failed to parse submit response: ${e.message}", cause = e)
            }
        }.also {
            logger.info("Queue: job submitted", mapOf("jobId" to it.jobId, "status" to it.status.name))
        }
    }

    /**
     * Check the current status of a job.
     *
     * @param jobId The job ID from [submit]
     * @return [JobStatusResponse] with the current status
     * @throws QueueStatusException if the HTTP request fails
     */
    suspend fun status(jobId: String): JobStatusResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v1/jobs/$jobId"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-API-KEY", apiKey)
            .addHeader("User-Agent", buildUserAgent())
            .build()

        executeRequest(request) { responseBody ->
            try {
                json.decodeFromString<JobStatusResponse>(responseBody)
            } catch (e: Exception) {
                throw QueueStatusException("Failed to parse status response: ${e.message}", cause = e)
            }
        }
    }

    /**
     * Download the result of a completed job.
     * Only call when [status] returns [JobStatus.COMPLETED].
     *
     * @param jobId The job ID from [submit]
     * @return The output video as raw bytes (MP4)
     * @throws QueueResultException if the HTTP request fails
     */
    suspend fun result(jobId: String): ByteArray = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v1/jobs/$jobId/content"

        logger.debug("Queue: downloading result for job $jobId")

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("X-API-KEY", apiKey)
            .addHeader("User-Agent", buildUserAgent())
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No response body"
                    throw QueueResultException(
                        "Failed to get job result: ${response.code} - $errorBody",
                        statusCode = response.code,
                    )
                }
                response.body?.bytes()
                    ?: throw QueueResultException("Empty response body")
            }
        } catch (e: QueueResultException) {
            throw e
        } catch (e: IOException) {
            throw QueueResultException("Network error downloading result: ${e.message}", cause = e)
        }
    }

    /**
     * Submit a job and automatically poll until it completes or fails.
     *
     * This method does **not** throw on job failure — it returns [QueueJobResult.Failed] instead.
     * It **will** throw [QueueSubmitException], [QueueStatusException], or [QueueResultException]
     * on network/API errors.
     *
     * @param model The video model
     * @param input The job input
     * @param onStatusChange Optional callback invoked on each status change
     * @param onUploadProgress Optional listener invoked as the request body is uploaded
     * @return [QueueJobResult.Completed] with video bytes, or [QueueJobResult.Failed] with error
     */
    suspend fun submitAndPoll(
        model: VideoModel,
        input: QueueJobInput,
        onStatusChange: ((JobStatusResponse) -> Unit)? = null,
        onUploadProgress: UploadProgressListener? = null,
    ): QueueJobResult {
        val job = submit(model, input, onUploadProgress)

        // Notify initial status
        onStatusChange?.invoke(JobStatusResponse(jobId = job.jobId, status = job.status))

        // Initial delay before polling
        delay(INITIAL_DELAY_MS)

        // Poll until terminal state
        while (true) {
            val current = status(job.jobId)
            onStatusChange?.invoke(current)

            when (current.status) {
                JobStatus.COMPLETED -> {
                    logger.info("Queue: job completed", mapOf("jobId" to job.jobId))
                    val data = result(job.jobId)
                    return QueueJobResult.Completed(jobId = job.jobId, data = data)
                }
                JobStatus.FAILED -> {
                    logger.warn("Queue: job failed", mapOf("jobId" to job.jobId))
                    return QueueJobResult.Failed(jobId = job.jobId, error = "Job failed")
                }
                else -> {
                    // Still pending or processing — wait and retry
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Submit a job and return a [Flow] that emits progress and the terminal result.
     *
     * Emits [QueueJobResult.InProgress] on each poll while pending/processing,
     * then a single [QueueJobResult.Completed] or [QueueJobResult.Failed] before completing.
     *
     * ```kotlin
     * client.queue.submitAndObserve(model, input) { bytesWritten, totalBytes ->
     *     showUploadProgress(bytesWritten, totalBytes)
     * }.collect { update ->
     *     when (update) {
     *         is QueueJobResult.InProgress -> showProgress(update.status)
     *         is QueueJobResult.Completed  -> saveVideo(update.data)
     *         is QueueJobResult.Failed     -> showError(update.error)
     *     }
     * }
     * ```
     *
     * @param model The video model
     * @param input The job input
     * @param onUploadProgress Optional listener invoked as the request body is uploaded
     */
    fun submitAndObserve(
        model: VideoModel,
        input: QueueJobInput,
        onUploadProgress: UploadProgressListener? = null,
    ): Flow<QueueJobResult> = flow {
        val job = submit(model, input, onUploadProgress)
        emit(QueueJobResult.InProgress(jobId = job.jobId, status = job.status))

        delay(INITIAL_DELAY_MS)

        while (true) {
            val current = status(job.jobId)
            when (current.status) {
                JobStatus.COMPLETED -> {
                    logger.info("Queue: job completed", mapOf("jobId" to job.jobId))
                    val data = result(job.jobId)
                    emit(QueueJobResult.Completed(jobId = job.jobId, data = data))
                    return@flow
                }
                JobStatus.FAILED -> {
                    logger.warn("Queue: job failed", mapOf("jobId" to job.jobId))
                    emit(QueueJobResult.Failed(jobId = job.jobId, error = "Job failed"))
                    return@flow
                }
                else -> {
                    emit(QueueJobResult.InProgress(jobId = job.jobId, status = current.status))
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Release resources held by this client.
     * Call when the client is no longer needed.
     */
    fun release() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private fun buildMultipartBody(input: QueueJobInput): MultipartBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)

        for ((key, value) in input.toFormFields()) {
            when (value) {
                is FileInput -> {
                    builder.addFormDataPart(key, guessFilename(key), createFileRequestBody(value))
                }
                is String -> builder.addFormDataPart(key, value)
                null -> { /* skip */ }
                else -> builder.addFormDataPart(key, value.toString())
            }
        }

        return builder.build()
    }

    /**
     * Create a streaming [RequestBody] from a [FileInput].
     *
     * File and Uri inputs stream directly from disk/provider into the HTTP body
     * without buffering the entire payload in memory, avoiding OOM on large videos.
     */
    private fun createFileRequestBody(input: FileInput): RequestBody = when (input) {
        is FileInput.FromUri -> {
            val resolver = contentResolver
                ?: throw InvalidInputException(
                    "ContentResolver is required for Uri file inputs. " +
                        "Ensure DecartClient was created with a valid Context."
                )
            val mimeType = try {
                resolver.getType(input.uri) ?: "application/octet-stream"
            } catch (_: SecurityException) {
                "application/octet-stream"
            }
            val mediaType = mimeType.toMediaType()
            object : RequestBody() {
                override fun contentType() = mediaType

                override fun contentLength(): Long = try {
                    resolver.openFileDescriptor(input.uri, "r")?.use { it.statSize } ?: -1L
                } catch (_: SecurityException) {
                    -1L
                }

                override fun writeTo(sink: BufferedSink) {
                    try {
                        val stream = resolver.openInputStream(input.uri)
                            ?: throw InvalidInputException(
                                "Failed to open input stream for Uri: ${input.uri}"
                            )
                        stream.use { sink.writeAll(it.source()) }
                    } catch (e: SecurityException) {
                        throw InvalidInputException(
                            "Permission denied reading Uri: ${input.uri}. " +
                                "Ensure the URI has read permission granted.",
                        )
                    }
                }
            }
        }

        is FileInput.FromFile -> {
            if (!input.file.exists()) {
                throw InvalidInputException("File does not exist: ${input.file.absolutePath}")
            }
            input.file.asRequestBody("application/octet-stream".toMediaType())
        }

        is FileInput.FromBytes -> {
            input.bytes.toRequestBody(input.mimeType.toMediaType())
        }

        is FileInput.FromInputStream -> {
            val mediaType = input.mimeType.toMediaType()
            object : RequestBody() {
                override fun contentType() = mediaType
                override fun contentLength() = -1L
                override fun writeTo(sink: BufferedSink) {
                    input.stream.use { sink.writeAll(it.source()) }
                }
            }
        }
    }

    private inline fun <T> executeRequest(
        request: Request,
        parse: (String) -> T,
    ): T {
        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val msg = "Queue API error: ${request.method} ${request.url} → ${response.code} - $body"
                    throw operationException(request, msg, statusCode = response.code)
                }

                return parse(body)
            }
        } catch (e: QueueException) {
            throw e
        } catch (e: IOException) {
            throw operationException(request, "Network error: ${e.message}", cause = e)
        }
    }

    private fun operationException(
        request: Request,
        message: String,
        statusCode: Int? = null,
        cause: Throwable? = null,
    ): QueueException = when {
        request.method == "POST" -> QueueSubmitException(message, statusCode, cause)
        request.url.encodedPath.endsWith("/content") -> QueueResultException(message, statusCode, cause)
        else -> QueueStatusException(message, statusCode, cause)
    }

    /**
     * Guess a filename for a multipart form field based on the field name.
     */
    private fun guessFilename(fieldName: String): String = when (fieldName) {
        "data" -> "video.mp4"
        "reference_image" -> "reference.png"
        "start" -> "start.png"
        "end" -> "end.png"
        else -> "file"
    }

    private fun buildUserAgent(): String = "decart-android-sdk/$SDK_VERSION lang/kotlin"

    /**
     * [RequestBody] wrapper that reports write progress to an [UploadProgressListener].
     *
     * Intercepts all writes through a [ForwardingSink], counting bytes as they pass
     * through to the underlying sink (and ultimately the network).
     */
    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val listener: UploadProgressListener,
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isOneShot() = delegate.isOneShot()

        override fun writeTo(sink: BufferedSink) {
            val totalBytes = contentLength()
            val countingSink = object : ForwardingSink(sink) {
                private var bytesWritten = 0L

                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    bytesWritten += byteCount
                    listener.onProgress(bytesWritten, totalBytes)
                }
            }
            val bufferedSink = countingSink.buffer()
            delegate.writeTo(bufferedSink)
            bufferedSink.flush()
        }
    }

    companion object {
        /** Polling interval between status checks (matches reference SDKs). */
        private const val POLL_INTERVAL_MS = 1_500L

        /** Initial delay before first poll (matches reference SDKs). */
        private const val INITIAL_DELAY_MS = 500L

        /** SDK version reported in User-Agent header. */
        internal const val SDK_VERSION = "0.3.0"
    }
}
