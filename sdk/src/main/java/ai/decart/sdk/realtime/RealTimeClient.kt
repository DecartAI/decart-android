package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.DecartError
import ai.decart.sdk.ErrorClassifier
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.RealtimeModel
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Configuration for creating a [RealTimeClient].
 */
data class RealTimeClientConfig(
    val apiKey: String,
    val baseUrl: String = "wss://api.decart.ai",
    val logger: Logger = NoopLogger,
)

/**
 * Output resolution for a realtime session. Defaults to 720p server-side when unset.
 */
enum class Resolution(val value: String) {
    P720("720p"),
    P1080("1080p"),
}

/**
 * Options for connecting to a realtime model through LiveKit media transport.
 */
data class ConnectOptions @JvmOverloads constructor(
    val model: RealtimeModel,
    val initialPrompt: InitialPrompt? = null,
    val initialImage: String? = null,
    val resolution: Resolution? = null,
    val realtimeConfiguration: RealtimeConfiguration = RealtimeConfiguration(),
    val publishCamera: Boolean = true,
    val publishMicrophone: Boolean = false,
    val facing: FacingMode = FacingMode.FRONT,
    val onLocalStream: ((RealtimeMediaStream) -> Unit)? = null,
    val onRemoteStream: ((RealtimeMediaStream) -> Unit)? = null,
)

internal fun buildWebrtcUrl(
    baseUrl: String,
    model: RealtimeModel,
    apiKey: String,
    resolution: Resolution?,
): String {
    val encodedKey = java.net.URLEncoder.encode(apiKey, "UTF-8")
    val encodedName = java.net.URLEncoder.encode(model.name, "UTF-8")
    val resolutionQs = resolution?.let {
        "&resolution=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
    } ?: ""
    return "$baseUrl${model.urlPath}?api_key=$encodedKey&model=$encodedName$resolutionQs"
}

/**
 * Main entry point for Decart realtime sessions.
 *
 * WebSocket signaling remains Decart-owned, while media is transported through
 * LiveKit rooms returned by the realtime signaling handshake.
 */
class RealTimeClient(
    private val context: Context,
    private val config: RealTimeClientConfig,
) {
    private val logger: Logger = config.logger
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var sessionManager: RealtimeSessionManager? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errors = MutableSharedFlow<DecartError>(extraBufferCapacity = 10)
    val errors: SharedFlow<DecartError> = _errors.asSharedFlow()

    private val _generationTicks = MutableSharedFlow<GenerationTickMessage>(extraBufferCapacity = 10)
    val generationTicks: SharedFlow<GenerationTickMessage> = _generationTicks.asSharedFlow()

    private val _diagnostics = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 50)
    val diagnostics: SharedFlow<DiagnosticEvent> = _diagnostics.asSharedFlow()

    private val _remoteStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1, extraBufferCapacity = 10)
    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream> = _remoteStreamUpdates.asSharedFlow()

    private val _localStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1, extraBufferCapacity = 10)
    val localStreamUpdates: SharedFlow<RealtimeMediaStream> = _localStreamUpdates.asSharedFlow()

    // Kept for source compatibility with observers. LiveKit stats are not surfaced yet.
    private val _stats = MutableSharedFlow<WebRTCStats>(extraBufferCapacity = 10)
    val stats: SharedFlow<WebRTCStats> = _stats.asSharedFlow()

    var sessionId: String? = null
        private set

    suspend fun connect(options: ConnectOptions): RealtimeMediaStream {
        disconnect()

        val url = buildWebrtcUrl(
            baseUrl = config.baseUrl,
            model = options.model,
            apiKey = config.apiKey,
            resolution = options.resolution,
        )

        val manager = RealtimeSessionManager(
            RealtimeSessionConfig(
                context = context,
                signalingUrl = url,
                model = options.model,
                logger = logger,
                realtimeConfiguration = options.realtimeConfiguration,
                initialImage = options.initialImage,
                initialPrompt = options.initialPrompt,
                publishCamera = options.publishCamera,
                publishMicrophone = options.publishMicrophone,
                facing = options.facing,
                onDiagnostic = { event -> _diagnostics.tryEmit(event) },
                onLocalStream = { stream ->
                    _localStreamUpdates.tryEmit(stream)
                    options.onLocalStream?.invoke(stream)
                },
                onRemoteStream = { stream ->
                    _remoteStreamUpdates.tryEmit(stream)
                    options.onRemoteStream?.invoke(stream)
                },
                onConnectionStateChange = { state -> _connectionState.value = state },
                onSessionId = { id -> sessionId = id },
                onGenerationTick = { tick -> _generationTicks.tryEmit(tick) },
                onError = { error ->
                    logger.error("Realtime error", mapOf("error" to error.message))
                    _errors.tryEmit(ErrorClassifier.classifyWebrtcError(error))
                },
            ),
        )
        sessionManager = manager
        return manager.connect()
    }

    fun disconnect() {
        sessionManager?.cleanup()
        sessionManager = null
        sessionId = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun setPrompt(prompt: String, enhance: Boolean = true) {
        val manager = sessionManager ?: throw IllegalStateException("Not connected")
        val state = manager.getConnectionState()
        if (state != ConnectionState.CONNECTED && state != ConnectionState.GENERATING) {
            throw IllegalStateException("Cannot send message: connection is $state")
        }
        val sent = manager.sendMessage(PromptMessage(prompt = prompt, enhancePrompt = enhance))
        if (!sent) {
            throw IllegalStateException("WebSocket is not open")
        }
    }

    suspend fun setImage(
        imageBase64: String?,
        prompt: String? = null,
        enhance: Boolean? = null,
        timeout: Long = 30_000L,
    ) {
        val manager = sessionManager ?: throw IllegalStateException("Not connected")
        manager.setImage(
            imageBase64,
            SignalingChannel.SetImageOptions(
                prompt = prompt,
                enhance = enhance,
                timeout = timeout,
            ),
        )
    }

    fun isConnected(): Boolean = sessionManager?.isConnected() ?: false

    fun release() {
        disconnect()
        scope.cancel()
    }
}
