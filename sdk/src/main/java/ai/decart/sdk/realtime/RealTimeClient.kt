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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 *
 * @param publishMicrophone Ignored. Audio publishing is not supported in the
 * Android LiveKit publisher yet; this property is retained for 0.7 source
 * compatibility.
 */
data class ConnectOptions @JvmOverloads constructor(
    val model: RealtimeModel,
    val initialPrompt: InitialPrompt? = null,
    val initialImage: String? = null,
    val resolution: Resolution? = null,
    val realtimeConfiguration: RealtimeConfiguration = RealtimeConfiguration(),
    val publishCamera: Boolean = true,
    @Deprecated("Audio publishing is not supported in the Android LiveKit publisher yet; this option is ignored.")
    val publishMicrophone: Boolean = false,
    val facing: FacingMode = FacingMode.FRONT,
    val mirror: MirrorMode = MirrorMode.AUTO,
    val onRemoteStream: ((RealtimeMediaStream) -> Unit)? = null,
) {
    constructor(
        model: RealtimeModel,
        initialPrompt: InitialPrompt? = null,
        initialImage: String? = null,
        resolution: Resolution? = null,
        realtimeConfiguration: RealtimeConfiguration = RealtimeConfiguration(),
        publishCamera: Boolean = true,
        publishMicrophone: Boolean = false,
        facing: FacingMode = FacingMode.FRONT,
        onRemoteStream: ((RealtimeMediaStream) -> Unit)? = null,
    ) : this(
        model = model,
        initialPrompt = initialPrompt,
        initialImage = initialImage,
        resolution = resolution,
        realtimeConfiguration = realtimeConfiguration,
        publishCamera = publishCamera,
        publishMicrophone = publishMicrophone,
        facing = facing,
        mirror = MirrorMode.AUTO,
        onRemoteStream = onRemoteStream,
    )
}

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
    val connectionChange: StateFlow<ConnectionState> = connectionState

    private val _errors = MutableSharedFlow<DecartError>(extraBufferCapacity = 10)
    val errors: SharedFlow<DecartError> = _errors.asSharedFlow()

    private val _generationTicks = MutableSharedFlow<GenerationTickMessage>(extraBufferCapacity = 10)
    val generationTicks: SharedFlow<GenerationTickMessage> = _generationTicks.asSharedFlow()
    val generationTick: SharedFlow<GenerationTickMessage> = generationTicks

    private val _generationEnded = MutableSharedFlow<GenerationEndedMessage>(extraBufferCapacity = 10)
    val generationEnded: SharedFlow<GenerationEndedMessage> = _generationEnded.asSharedFlow()

    private val _queuePositionUpdates = MutableSharedFlow<QueuePositionMessage>(replay = 1, extraBufferCapacity = 10)
    val queuePositionUpdates: SharedFlow<QueuePositionMessage> = _queuePositionUpdates.asSharedFlow()
    val queuePosition: SharedFlow<QueuePositionMessage> = queuePositionUpdates

    private val _diagnostics = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 50)
    val diagnostics: SharedFlow<DiagnosticEvent> = _diagnostics.asSharedFlow()
    val diagnostic: SharedFlow<DiagnosticEvent> = diagnostics

    private val _stats = MutableSharedFlow<PublishStatsEvent>(extraBufferCapacity = 50)
    val stats: SharedFlow<PublishStatsEvent> = _stats.asSharedFlow()

    private val _remoteStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1, extraBufferCapacity = 10)
    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream> = _remoteStreamUpdates.asSharedFlow()
    val remoteStream: SharedFlow<RealtimeMediaStream> = remoteStreamUpdates

    private val _localStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1, extraBufferCapacity = 10)
    val localStreamUpdates: SharedFlow<RealtimeMediaStream> = _localStreamUpdates.asSharedFlow()
    val localStream: SharedFlow<RealtimeMediaStream> = localStreamUpdates

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
     *
     * @param includeMicrophone Ignored. Audio publishing is not supported in
     * the Android LiveKit publisher yet; this parameter is retained for 0.7
     * source compatibility.
     * @param mirror Pre-flip captured video before sending it. Defaults to
     * [MirrorMode.AUTO], which mirrors the front camera only.
     */
    @JvmOverloads
    fun createLocalVideoStream(
        width: Int,
        height: Int,
        facing: FacingMode = FacingMode.FRONT,
        includeMicrophone: Boolean = false,
        configuration: RealtimeConfiguration = RealtimeConfiguration(),
        mirror: MirrorMode = MirrorMode.AUTO,
    ): RealtimeMediaStream {
        val stream = createLocalVideoStream(
            context = context,
            width = width,
            height = height,
            facing = facing,
            configuration = configuration,
            logger = logger,
            mirror = mirror,
        )
        _localStreamUpdates.tryEmit(stream)
        return stream
    }

    /**
     * Build a preview-ready [RealtimeMediaStream] sized for [model].
     *
     * @param includeMicrophone Ignored. Audio publishing is not supported in
     * the Android LiveKit publisher yet; this parameter is retained for 0.7
     * source compatibility.
     * @param mirror Pre-flip captured video before sending it. Defaults to
     * [MirrorMode.AUTO], which mirrors the front camera only.
     */
    @JvmOverloads
    fun createLocalVideoStream(
        model: RealtimeModel,
        facing: FacingMode = FacingMode.FRONT,
        includeMicrophone: Boolean = false,
        configuration: RealtimeConfiguration = RealtimeConfiguration(),
        mirror: MirrorMode = MirrorMode.AUTO,
    ): RealtimeMediaStream = createLocalVideoStream(
        width = model.width,
        height = model.height,
        facing = facing,
        configuration = configuration,
        mirror = mirror,
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
                facing = options.facing,
                mirror = options.mirror,
                onDiagnostic = { event ->
                    _diagnostics.tryEmit(event)
                    if (event is DiagnosticEvent.PublishStats) {
                        _stats.tryEmit(event.data)
                    }
                },
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
                onError = { error, source ->
                    logger.error("Realtime error", mapOf("error" to error.message))
                    _errors.tryEmit(ErrorClassifier.classifyWebrtcError(error, source))
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
        val manager = requirePromptSessionManager()
        manager.setPrompt(prompt = prompt, enhance = enhance, timeoutMs = timeoutMs)
    }

    /**
     * Start a prompt update immediately and return a wait handle for the server
     * ack. Call `await()` on the returned [Deferred] to observe ack failure,
     * timeout, send failure, or websocket disconnect.
     */
    fun setPromptAsync(
        prompt: String,
        enhance: Boolean = true,
        timeoutMs: Long = 15_000L,
    ): Deferred<Unit> {
        val manager = requirePromptSessionManager()
        return scope.async {
            manager.setPrompt(prompt = prompt, enhance = enhance, timeoutMs = timeoutMs)
        }
    }

    @Deprecated("Use setPrompt(...); it now suspends until the server ack, matching the 0.7 API.", ReplaceWith("setPrompt(prompt, enhance, timeoutMs)"))
    suspend fun setPromptAndAwaitAck(
        prompt: String,
        enhance: Boolean = true,
        timeoutMs: Long = 15_000L,
    ) {
        setPrompt(prompt = prompt, enhance = enhance, timeoutMs = timeoutMs)
    }

    suspend fun setImage(
        imageBase64: String?,
        prompt: String? = null,
        enhance: Boolean? = null,
        timeout: Long = 30_000L,
    ) {
        val manager = requireSessionManager()
        manager.setImage(
            imageBase64,
            setImageOptions(prompt = prompt, enhance = enhance, timeout = timeout),
        )
    }

    /**
     * Start a reference image update immediately and return a wait handle for
     * the server ack. This also covers the optional [prompt] and [enhance]
     * values carried by the set-image request. Call `await()` on the returned
     * [Deferred] to observe ack failure, timeout, send failure, or websocket
     * disconnect.
     */
    fun setImageAsync(
        imageBase64: String?,
        prompt: String? = null,
        enhance: Boolean? = null,
        timeout: Long = 30_000L,
    ): Deferred<Unit> {
        val manager = requireSessionManager()
        val options = setImageOptions(prompt = prompt, enhance = enhance, timeout = timeout)
        return scope.async {
            manager.setImage(imageBase64, options)
        }
    }

    private fun requirePromptSessionManager(): RealtimeSessionManager {
        val manager = sessionManager ?: throw IllegalStateException("Not connected")
        val state = manager.getConnectionState()
        if (state != ConnectionState.CONNECTED && state != ConnectionState.GENERATING) {
            throw IllegalStateException("Cannot send message: connection is $state")
        }
        return manager
    }

    private fun requireSessionManager(): RealtimeSessionManager =
        sessionManager ?: throw IllegalStateException("Not connected")

    private fun setImageOptions(
        prompt: String?,
        enhance: Boolean?,
        timeout: Long,
    ): SignalingChannel.SetImageOptions = SignalingChannel.SetImageOptions(
        prompt = prompt,
        enhance = enhance,
        timeout = timeout,
    )

    fun isConnected(): Boolean = sessionManager?.isConnected() ?: false

    fun release() {
        disconnect()
        scope.cancel()
    }

    companion object {
        /**
         * Stateless preview-track factory: callers can build a preview
         * before they've decided which `apiKey` to use.
         *
         * @param includeMicrophone Ignored. Audio publishing is not supported
         * in the Android LiveKit publisher yet; this parameter is retained for
         * 0.7 source compatibility.
         * @param mirror Pre-flip captured video before sending it. Defaults to
         * [MirrorMode.AUTO], which mirrors the front camera only.
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
            mirror: MirrorMode = MirrorMode.AUTO,
        ): RealtimeMediaStream = LocalStreamFactory.createCameraStream(
            context = context,
            configuration = configuration,
            width = width,
            height = height,
            facing = facing,
            logger = logger,
            mirror = mirror,
        )

        /**
         * Stateless preview-track factory sized for [model].
         *
         * @param includeMicrophone Ignored. Audio publishing is not supported
         * in the Android LiveKit publisher yet; this parameter is retained for
         * 0.7 source compatibility.
         * @param mirror Pre-flip captured video before sending it. Defaults to
         * [MirrorMode.AUTO], which mirrors the front camera only.
         */
        @JvmStatic
        @JvmOverloads
        fun createLocalVideoStream(
            context: android.content.Context,
            model: RealtimeModel,
            facing: FacingMode = FacingMode.FRONT,
            includeMicrophone: Boolean = false,
            configuration: RealtimeConfiguration = RealtimeConfiguration(),
            logger: Logger = NoopLogger,
            mirror: MirrorMode = MirrorMode.AUTO,
        ): RealtimeMediaStream = createLocalVideoStream(
            context = context,
            width = model.width,
            height = model.height,
            facing = facing,
            configuration = configuration,
            logger = logger,
            mirror = mirror,
        )
    }
}
