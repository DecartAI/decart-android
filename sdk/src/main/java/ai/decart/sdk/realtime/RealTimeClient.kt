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

/**
 * Configuration for creating a [RealTimeClient].
 */
data class RealTimeClientConfig(
    val apiKey: String,
    val baseUrl: String = "wss://api.stage-decart.com",
    val logger: Logger = NoopLogger,
    /**
     * When true, enables LiveKit's internal verbose logging AND the
     * underlying libwebrtc native log lines. Very noisy — only use for
     * diagnosing transport issues (codec negotiation, ICE, RTP, BWE).
     */
    val verboseTransport: Boolean = false,
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
 *
 * Callers are encouraged to create a local stream with
 * [RealTimeClient.createLocalVideoStream] first (so the same LiveKit Room is
 * used for preview and publish, matching the iOS / JS SDKs) and pass it to
 * [RealTimeClient.connect]. If no local stream is provided and [publishCamera]
 * is true the SDK will create one internally and dispose it on disconnect.
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
 * Main entry point for Decart realtime sessions.
 *
 * WebSocket signaling remains Decart-owned, while media is transported through
 * LiveKit rooms returned by the realtime signaling handshake. The local video
 * track is created (and owned) by the caller through [createLocalVideoStream]
 * so previewing, capturing and publishing all share a single LiveKit Room —
 * the same design as the iOS SDK.
 */
class RealTimeClient(
    private val context: Context,
    private val config: RealTimeClientConfig,
) {
    private val logger: Logger = config.logger
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        if (config.verboseTransport) {
            io.livekit.android.LiveKit.loggingLevel = io.livekit.android.util.LoggingLevel.VERBOSE
            io.livekit.android.LiveKit.enableWebRTCLogging = true
            logger.info(
                "LiveKit verbose transport logging enabled",
                mapOf("webrtcLogs" to true),
            )
        }
    }

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

    /**
     * Create a LocalVideoTrack-backed [RealtimeMediaStream] that the caller can
     * render as a preview and later hand to [connect]. The returned stream
     * owns its LiveKit [io.livekit.android.room.Room]; the caller is
     * responsible for disposing it (call [RealtimeMediaStream.dispose] or stop
     * the tracks manually) when the preview is no longer needed.
     *
     * Delegates to the static [RealTimeClient.createLocalVideoStream] so the
     * preview lifecycle does not depend on any per-instance state of this
     * client — callers can build a preview before they have decided which
     * `apiKey` / [RealTimeClient] to use.
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

    /**
     * Convenience overload that derives capture dimensions from the model.
     */
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

    /**
     * Connect a realtime session. When [localStream] is provided the SDK
     * publishes its tracks against the Room that owns them. When null, and
     * [ConnectOptions.publishCamera] is true, the SDK creates an internal
     * stream and disposes it on [disconnect].
     */
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
                onSessionId = { id -> sessionId = id },
                onGenerationTick = { tick -> _generationTicks.tryEmit(tick) },
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

    companion object {
        /**
         * Stateless preview-track factory. Mirrors iOS's
         * `LocalVideoTrack.createCameraTrack(...)` so the local preview can be
         * built before a [RealTimeClient] exists — e.g. while the user is
         * still typing their API key.
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

        /** Convenience overload that derives capture dimensions from the model. */
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
