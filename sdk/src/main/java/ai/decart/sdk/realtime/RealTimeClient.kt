package ai.decart.sdk.realtime

import ai.decart.sdk.*
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*

/**
 * Configuration for creating a [RealTimeClient].
 */
data class RealTimeClientConfig(
    val apiKey: String,
    val baseUrl: String = "wss://api.decart.ai",
    val logger: Logger = NoopLogger
)

/**
 * Options for connecting to a realtime model.
 */
data class ConnectOptions(
    val model: RealtimeModel,
    val onRemoteVideoTrack: (VideoTrack) -> Unit,
    val onRemoteAudioTrack: ((AudioTrack) -> Unit)? = null,
    val initialPrompt: InitialPrompt? = null,
    val initialImage: String? = null
)

/**
 * The main entry point for the Decart Realtime SDK.
 *
 * Usage:
 * ```kotlin
 * val client = RealTimeClient(context, RealTimeClientConfig(apiKey = "..."))
 * client.connect(ConnectOptions(
 *     model = RealtimeModels.LUCY_V2V_720P_RT,
 *     onRemoteVideoTrack = { track -> renderer.addSink(track) }
 * ))
 *
 * // Change the prompt during a session
 * client.setPrompt("a cyberpunk cityscape")
 *
 * // Observe state changes
 * client.connectionState.collect { state -> ... }
 *
 * // Disconnect
 * client.disconnect()
 * ```
 */
class RealTimeClient(
    private val context: Context,
    private val config: RealTimeClientConfig
) {
    private val logger: Logger = config.logger
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // PeerConnectionFactory -- created once, reused across connections
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    private var webrtcManager: WebRTCManager? = null
    private var audioStreamManager: AudioStreamManager? = null
    private var statsCollector: WebRTCStatsCollector? = null

    // Public state flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errors = MutableSharedFlow<DecartError>(extraBufferCapacity = 10)
    val errors: SharedFlow<DecartError> = _errors.asSharedFlow()

    private val _generationTicks = MutableSharedFlow<GenerationTickMessage>(extraBufferCapacity = 10)
    val generationTicks: SharedFlow<GenerationTickMessage> = _generationTicks.asSharedFlow()

    private val _diagnostics = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 50)
    val diagnostics: SharedFlow<DiagnosticEvent> = _diagnostics.asSharedFlow()

    private val _stats = MutableSharedFlow<WebRTCStats>(extraBufferCapacity = 10)
    val stats: SharedFlow<WebRTCStats> = _stats.asSharedFlow()

    // Session info
    var sessionId: String? = null
        private set

    /**
     * Initialize the WebRTC peer connection factory.
     * Call this once, typically in Application.onCreate() or before first connect.
     * If not called explicitly, connect() will call it automatically.
     */
    fun initialize(eglBase: EglBase? = null) {
        if (peerConnectionFactory != null) return

        this.eglBase = eglBase ?: EglBase.create()
        val eglContext = this.eglBase!!.eglBaseContext

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Connect to a realtime model and start streaming.
     *
     * @param localVideoTrack Local camera video track to send (null for live_avatar or subscribe mode)
     * @param localAudioTrack Local microphone audio track to send (null to omit)
     * @param options Connection options including model and callbacks
     */
    suspend fun connect(
        localVideoTrack: VideoTrack? = null,
        localAudioTrack: AudioTrack? = null,
        options: ConnectOptions
    ) {
        // Ensure factory is initialized
        if (peerConnectionFactory == null) {
            initialize()
        }
        val factory = peerConnectionFactory!!

        val isAvatarLive = options.model.name == "live-avatar" || options.model.name == "live_avatar"

        // For live_avatar without user-provided audio: create AudioStreamManager
        var inputAudioTrack = localAudioTrack
        if (isAvatarLive && localAudioTrack == null) {
            audioStreamManager = AudioStreamManager(context, factory)
            inputAudioTrack = audioStreamManager!!.getAudioTrack()
        }

        // Build WebSocket URL
        val url = "${config.baseUrl}${options.model.urlPath}?api_key=${
            java.net.URLEncoder.encode(config.apiKey, "UTF-8")
        }&model=${java.net.URLEncoder.encode(options.model.name, "UTF-8")}"

        val manager = WebRTCManager(WebRTCConfig(
            webrtcUrl = url,
            logger = logger,
            onDiagnostic = { event -> _diagnostics.tryEmit(event) },
            onRemoteVideoTrack = options.onRemoteVideoTrack,
            onRemoteAudioTrack = options.onRemoteAudioTrack,
            onConnectionStateChange = { state ->
                _connectionState.value = state
            },
            onError = { error ->
                logger.error("WebRTC error", mapOf("error" to error.message))
                _errors.tryEmit(ErrorClassifier.classifyWebrtcError(error))
            },
            vp8MinBitrate = 300,
            vp8StartBitrate = 600,
            modelName = options.model.name,
            initialImage = options.initialImage,
            initialPrompt = options.initialPrompt
        ))

        webrtcManager = manager

        // Listen for session ID and generation ticks
        scope.launch {
            manager.getWebsocketMessageFlows().sessionIdFlow.collect { msg ->
                sessionId = msg.sessionId
            }
        }
        scope.launch {
            manager.getWebsocketMessageFlows().generationTickFlow.collect { msg ->
                _generationTicks.tryEmit(msg)
            }
        }

        // Connect
        manager.connect(localVideoTrack, inputAudioTrack, factory)

        // Start stats collection
        statsCollector = WebRTCStatsCollector()
        manager.getPeerConnection()?.let { pc ->
            statsCollector?.start(pc) { stats ->
                _stats.tryEmit(stats)
            }
        }
    }

    /**
     * Disconnect from the current session.
     */
    fun disconnect() {
        statsCollector?.stop()
        statsCollector = null
        webrtcManager?.cleanup()
        webrtcManager = null
        audioStreamManager?.cleanup()
        audioStreamManager = null
        sessionId = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Send a prompt to change the generation style.
     * The connection must be in CONNECTED or GENERATING state.
     *
     * @param prompt The text prompt
     * @param enhance Whether to enhance the prompt (default true)
     */
    fun setPrompt(prompt: String, enhance: Boolean = true) {
        val manager = webrtcManager ?: throw IllegalStateException("Not connected")
        val state = manager.getConnectionState()
        if (state != ConnectionState.CONNECTED && state != ConnectionState.GENERATING) {
            throw IllegalStateException("Cannot send message: connection is $state")
        }

        val sent = manager.sendMessage(PromptMessage(
            prompt = prompt,
            enhancePrompt = enhance
        ))
        if (!sent) {
            throw IllegalStateException("WebSocket is not open")
        }
    }

    /**
     * Send an image (and optionally a prompt) to the server.
     * Used for avatar/image-based models.
     *
     * @param imageBase64 Base64-encoded image, or null to clear
     * @param prompt Optional prompt to send with the image
     * @param enhance Whether to enhance the prompt
     * @param timeout Timeout in ms for the ack (default 30s)
     */
    suspend fun setImage(
        imageBase64: String?,
        prompt: String? = null,
        enhance: Boolean? = null,
        timeout: Long = 30_000L
    ) {
        val manager = webrtcManager ?: throw IllegalStateException("Not connected")
        manager.setImage(imageBase64, WebRTCConnection.SetImageOptions(
            prompt = prompt,
            enhance = enhance,
            timeout = timeout
        ))
    }

    /**
     * Play audio through the live_avatar audio stream.
     * Only available when connected to the live_avatar model without a user-provided audio track.
     *
     * @param audioData Raw audio data (WAV, MP3, etc.)
     */
    suspend fun playAudio(audioData: ByteArray) {
        val manager = audioStreamManager
            ?: throw IllegalStateException("playAudio is only available for live_avatar model")
        manager.playAudio(audioData)
    }

    /**
     * Check if playAudio is available (live_avatar mode with internal audio).
     */
    fun isPlayAudioAvailable(): Boolean = audioStreamManager != null

    /**
     * Check if currently connected.
     */
    fun isConnected(): Boolean = webrtcManager?.isConnected() ?: false

    /**
     * Get the EGL base context for initializing SurfaceViewRenderers.
     * Call [initialize] first.
     */
    fun getEglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * Create a video source for camera capture.
     * Call [initialize] first.
     */
    fun createVideoSource(isScreencast: Boolean = false): VideoSource? =
        peerConnectionFactory?.createVideoSource(isScreencast)

    /**
     * Create a video track from a video source.
     * Call [initialize] first.
     */
    fun createVideoTrack(id: String, source: VideoSource): VideoTrack? =
        peerConnectionFactory?.createVideoTrack(id, source)

    /**
     * Release all resources. Call when done with the client.
     */
    fun release() {
        disconnect()
        scope.cancel()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
    }
}
