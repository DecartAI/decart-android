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
 * Output resolution for a realtime session. Defaults to 720p server-side when unset.
 */
enum class Resolution(val value: String) {
    P720("720p"),
    P1080("1080p"),
}

/**
 * Options for connecting to a realtime model.
 */
data class ConnectOptions @JvmOverloads constructor(
    val model: RealtimeModel,
    val onRemoteVideoTrack: (VideoTrack) -> Unit,
    val onRemoteAudioTrack: ((AudioTrack) -> Unit)? = null,
    val initialPrompt: InitialPrompt? = null,
    val initialImage: String? = null,
    val resolution: Resolution? = null,
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
 * Holder for a camera-driven [VideoTrack] produced by [RealTimeClient.createCameraVideoTrack].
 * Call [stop] when finished — typically on disconnect — to release the capturer, source,
 * track, and texture helper in the correct order.
 */
class CameraVideoTrack internal constructor(
    val track: VideoTrack,
    val source: VideoSource,
    val capturer: VideoCapturer,
    val surfaceTextureHelper: SurfaceTextureHelper,
) {
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { capturer.stopCapture() } catch (_: Exception) {}
        capturer.dispose()
        track.dispose()
        source.dispose()
        surfaceTextureHelper.dispose()
    }
}

/**
 * The main entry point for the Decart Realtime SDK.
 *
 * Usage:
 * ```kotlin
 * val client = RealTimeClient(context, RealTimeClientConfig(apiKey = "..."))
 * client.connect(ConnectOptions(
 *     model = RealtimeModels.LUCY_RESTYLE_2,
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
     * @param localVideoTrack Local camera video track to send (null for subscribe mode)
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

        // Build WebSocket URL
        val url = buildWebrtcUrl(
            baseUrl = config.baseUrl,
            model = options.model,
            apiKey = config.apiKey,
            resolution = options.resolution,
        )

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
        manager.connect(localVideoTrack, localAudioTrack, factory)

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
     * One-line camera setup that produces a ready-to-send [VideoTrack].
     *
     * Picks the first device matching [facing] (falling back to the first available device),
     * wires up the standard Camera2Enumerator → VideoCapturer → VideoSource → VideoTrack
     * chain using the client's [PeerConnectionFactory] and EGL context, and optionally
     * pre-flips the input via [MirrorVideoProcessor] for natural selfie rendering.
     *
     * Call [initialize] first. Call [CameraVideoTrack.stop] when finished, *before*
     * [release] — the resulting capturer/source/track are tied to this client's factory.
     *
     * The caller must hold `android.permission.CAMERA` at the time of this call.
     *
     * @param facing Camera direction to pick from [Camera2Enumerator].
     * @param mirror Whether to pre-flip frames horizontally. [MirrorMode.AUTO] mirrors
     *   iff [facing] is [FacingMode.FRONT].
     * @param width Capture width.
     * @param height Capture height.
     * @param fps Capture frame rate.
     * @param trackId Stable id for the resulting [VideoTrack].
     */
    fun createCameraVideoTrack(
        facing: FacingMode = FacingMode.FRONT,
        mirror: MirrorMode = MirrorMode.AUTO,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        trackId: String = "local_video",
    ): CameraVideoTrack {
        val factory = peerConnectionFactory
            ?: throw IllegalStateException("RealTimeClient.initialize() must be called before createCameraVideoTrack()")
        val egl = eglBase
            ?: throw IllegalStateException("RealTimeClient.initialize() must be called before createCameraVideoTrack()")

        val enumerator = Camera2Enumerator(context)
        val wantsFront = facing == FacingMode.FRONT
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == wantsFront }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No camera devices available")

        val capturer = enumerator.createCapturer(deviceName, null)
            ?: throw IllegalStateException("Failed to create camera capturer for $deviceName")

        var source: VideoSource? = null
        var surfaceTextureHelper: SurfaceTextureHelper? = null
        var track: VideoTrack? = null
        var captureStarted = false
        try {
            source = factory.createVideoSource(capturer.isScreencast)
                ?: throw IllegalStateException("Failed to create video source")

            val shouldMirror = when (mirror) {
                MirrorMode.OFF -> false
                MirrorMode.ON -> true
                MirrorMode.AUTO -> enumerator.isFrontFacing(deviceName)
            }
            if (shouldMirror) {
                source.setVideoProcessor(MirrorVideoProcessor(logger))
            }

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
            capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
            capturer.startCapture(width, height, fps)
            captureStarted = true

            track = factory.createVideoTrack(trackId, source)
                ?: throw IllegalStateException("Failed to create video track")
            track.setEnabled(true)

            return CameraVideoTrack(
                track = track,
                source = source,
                capturer = capturer,
                surfaceTextureHelper = surfaceTextureHelper,
            )
        } catch (t: Throwable) {
            // Mirror the disposal order of CameraVideoTrack.stop():
            //   stopCapture → capturer → track → source → surfaceTextureHelper.
            // The capturer holds a reference to the SurfaceTextureHelper, so dispose it first.
            if (captureStarted) {
                try { capturer.stopCapture() } catch (_: Exception) {}
            }
            capturer.dispose()
            track?.dispose()
            source?.dispose()
            surfaceTextureHelper?.dispose()
            throw t
        }
    }

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
