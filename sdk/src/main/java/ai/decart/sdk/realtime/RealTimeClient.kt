package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.DecartError
import ai.decart.sdk.ErrorClassifier
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.realtime.livekit.LocalStreamFactory
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
import kotlinx.coroutines.flow.update

data class RealTimeClientConfig(
    val apiKey: String,
    val baseUrl: String = "wss://api.decart.ai",
    val logger: Logger = NoopLogger,
)

/** Server-side output resolution. Defaults to 720p when unset. */
enum class Resolution(val value: String) {
    P720("720p"),
    P1080("1080p"),
}

/**
 * Prefer creating a local stream with [RealTimeClient.createLocalVideoStream]
 * and passing it in, so preview and publish share a single LiveKit Room.
 * If [localStream] is null and [publishCamera] is true the SDK opens one
 * internally and disposes it on disconnect.
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
 * Entry point for Decart realtime sessions. WebSocket signaling is
 * Decart-owned; media is transported through a LiveKit room returned by
 * the signaling handshake.
 */
class RealTimeClient(
    private val context: Context,
    private val config: RealTimeClientConfig,
) {
    private val logger: Logger = config.logger
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // Silence LiveKit's internal logger and the libwebrtc native logger;
        // events surface through the injected [Logger] and [diagnostics] only.
        io.livekit.android.LiveKit.loggingLevel = io.livekit.android.util.LoggingLevel.OFF
        io.livekit.android.LiveKit.enableWebRTCLogging = false
    }

    private var sessionManager: RealtimeSessionManager? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errors = MutableSharedFlow<DecartError>(extraBufferCapacity = 10)
    val errors: SharedFlow<DecartError> = _errors.asSharedFlow()

    private val _generationTicks = MutableSharedFlow<GenerationTickMessage>(extraBufferCapacity = 10)
    val generationTicks: SharedFlow<GenerationTickMessage> = _generationTicks.asSharedFlow()

    private val _generationEnded = MutableSharedFlow<GenerationEndedMessage>(extraBufferCapacity = 10)
    val generationEnded: SharedFlow<GenerationEndedMessage> = _generationEnded.asSharedFlow()

    private val _queuePositionUpdates = MutableSharedFlow<QueuePositionMessage>(replay = 1, extraBufferCapacity = 10)
    val queuePositionUpdates: SharedFlow<QueuePositionMessage> = _queuePositionUpdates.asSharedFlow()

    private val _diagnostics = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 50)
    val diagnostics: SharedFlow<DiagnosticEvent> = _diagnostics.asSharedFlow()

    private val _remoteStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1, extraBufferCapacity = 10)
    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream> = _remoteStreamUpdates.asSharedFlow()

    private val _localStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1, extraBufferCapacity = 10)
    val localStreamUpdates: SharedFlow<RealtimeMediaStream> = _localStreamUpdates.asSharedFlow()

    private val _sessionStarted = MutableStateFlow<SessionStarted?>(null)
    val sessionStarted: StateFlow<SessionStarted?> = _sessionStarted.asStateFlow()

    val sessionId: String?
        get() = _sessionStarted.value?.sessionId

    val subscribeToken: String?
        get() = _sessionStarted.value?.subscribeToken

    /**
     * Build a preview-ready [RealtimeMediaStream]. The caller owns the
     * returned stream's LiveKit Room and must [RealtimeMediaStream.dispose]
     * it. Delegates to the static factory so preview can be built before
     * a [RealTimeClient] exists.
     */
    @JvmOverloads
    fun createLocalVideoStream(
        width: Int,
        height: Int,
        facing: FacingMode = FacingMode.FRONT,
        includeMicrophone: Boolean = false,
        configuration: RealtimeConfiguration = RealtimeConfiguration(),
    ): RealtimeMediaStream {
        val stream = createLocalVideoStream(
            context = context,
            width = width,
            height = height,
            facing = facing,
            includeMicrophone = includeMicrophone,
            configuration = configuration,
            logger = logger,
        )
        _localStreamUpdates.tryEmit(stream)
        return stream
    }

    @JvmOverloads
    fun createLocalVideoStream(
        model: RealtimeModel,
        facing: FacingMode = FacingMode.FRONT,
        includeMicrophone: Boolean = false,
        configuration: RealtimeConfiguration = RealtimeConfiguration(),
    ): RealtimeMediaStream = createLocalVideoStream(
        width = model.width,
        height = model.height,
        facing = facing,
        includeMicrophone = includeMicrophone,
        configuration = configuration,
    )

    @JvmOverloads
    suspend fun connect(
        options: ConnectOptions,
        localStream: RealtimeMediaStream? = null,
    ): RealtimeMediaStream {
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
                },
                onRemoteStream = { stream ->
                    _remoteStreamUpdates.tryEmit(stream)
                    options.onRemoteStream?.invoke(stream)
                },
                onConnectionStateChange = { state -> _connectionState.value = state },
                onSessionStarted = { started -> _sessionStarted.update { started } },
                onGenerationTick = { tick -> _generationTicks.tryEmit(tick) },
                onGenerationEnded = { ended -> _generationEnded.tryEmit(ended) },
                onQueuePosition = { qp -> _queuePositionUpdates.tryEmit(qp) },
                onError = { error ->
                    logger.error("Realtime error", mapOf("error" to error.message))
                    _errors.tryEmit(ErrorClassifier.classifyWebrtcError(error))
                },
            ),
        )
        sessionManager = manager
        return manager.connect(localStream)
    }

    fun disconnect() {
        sessionManager?.cleanup()
        sessionManager = null
        _sessionStarted.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Update the prompt and suspend until the server acks. Throws on ack
     * failure, timeout, send failure, or websocket disconnect.
     */
    suspend fun setPrompt(
        prompt: String,
        enhance: Boolean = true,
        timeoutMs: Long = 15_000L,
    ) {
        val manager = sessionManager ?: throw IllegalStateException("Not connected")
        val state = manager.getConnectionState()
        if (state != ConnectionState.CONNECTED && state != ConnectionState.GENERATING) {
            throw IllegalStateException("Cannot send message: connection is $state")
        }
        manager.setPrompt(prompt = prompt, enhance = enhance, timeoutMs = timeoutMs)
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

    companion object {
        /**
         * Stateless preview-track factory: callers can build a preview
         * before they've decided which `apiKey` to use.
         */
        @JvmStatic
        @JvmOverloads
        fun createLocalVideoStream(
            context: android.content.Context,
            width: Int,
            height: Int,
            facing: FacingMode = FacingMode.FRONT,
            includeMicrophone: Boolean = false,
            configuration: RealtimeConfiguration = RealtimeConfiguration(),
            logger: Logger = NoopLogger,
        ): RealtimeMediaStream = LocalStreamFactory.createCameraStream(
            context = context,
            configuration = configuration,
            width = width,
            height = height,
            facing = facing,
            includeMicrophone = includeMicrophone,
            logger = logger,
        )

        @JvmStatic
        @JvmOverloads
        fun createLocalVideoStream(
            context: android.content.Context,
            model: RealtimeModel,
            facing: FacingMode = FacingMode.FRONT,
            includeMicrophone: Boolean = false,
            configuration: RealtimeConfiguration = RealtimeConfiguration(),
            logger: Logger = NoopLogger,
        ): RealtimeMediaStream = createLocalVideoStream(
            context = context,
            width = model.width,
            height = model.height,
            facing = facing,
            includeMicrophone = includeMicrophone,
            configuration = configuration,
            logger = logger,
        )
    }
}
