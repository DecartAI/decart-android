package ai.decart.sdk

import ai.decart.sdk.queue.QueueClient
import ai.decart.sdk.realtime.RealTimeClient
import ai.decart.sdk.realtime.RealTimeClientConfig
import android.content.Context

/**
 * Configuration for the Decart client.
 *
 * @property apiKey Your Decart API key (required)
 * @property baseUrl WebSocket base URL for the realtime API
 * @property httpBaseUrl HTTP base URL for the batch queue API
 * @property logLevel Minimum log level for SDK logging
 */
data class DecartClientConfig(
    val apiKey: String,
    val baseUrl: String = "wss://api.decart.ai",
    val httpBaseUrl: String = "https://api.decart.ai",
    val logLevel: LogLevel = LogLevel.WARN
)

/**
 * Top-level entry point for the Decart SDK.
 *
 * Provides access to both the realtime streaming API and the batch video queue API.
 *
 * Usage:
 * ```kotlin
 * val client = DecartClient(context, DecartClientConfig(apiKey = "your-api-key"))
 *
 * // Realtime streaming
 * val realtime = client.realtime
 * realtime.connect(...)
 *
 * // Batch video generation
 * val result = client.queue.submitAndPoll(
 *     model = VideoModels.LUCY_2_V2V,
 *     input = VideoEditInput(
 *         prompt = "Transform to watercolor style",
 *         data = FileInput.fromUri(videoUri),
 *     )
 * )
 * ```
 */
class DecartClient(
    context: Context,
    config: DecartClientConfig
) {
    private val apiKey = config.apiKey.trim()

    init {
        require(apiKey.isNotBlank()) {
            "Missing API key. Pass apiKey to DecartClientConfig or set the DECART_API_KEY environment variable."
        }
    }

    private val logger: Logger = AndroidLogger(config.logLevel)

    /**
     * The realtime client for streaming camera video through AI models.
     */
    val realtime: RealTimeClient = RealTimeClient(
        context = context,
        config = RealTimeClientConfig(
            apiKey = apiKey,
            baseUrl = config.baseUrl,
            logger = logger
        )
    )

    /**
     * The queue client for batch video generation jobs.
     *
     * Submit video generation jobs and poll for results asynchronously.
     * Supports all batch video models (e.g., [VideoModels.LUCY_2_V2V]).
     */
    val queue: QueueClient = QueueClient(
        apiKey = apiKey,
        baseUrl = config.httpBaseUrl,
        logger = logger,
        contentResolver = context.applicationContext.contentResolver,
    )

    /**
     * Release all resources held by this client.
     */
    fun release() {
        try {
            realtime.release()
        } catch (_: Exception) {
            // Realtime may not have been initialized — safe to ignore
        }
        try {
            queue.release()
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }
}
