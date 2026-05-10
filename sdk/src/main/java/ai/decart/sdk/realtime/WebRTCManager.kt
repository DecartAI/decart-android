package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoTrack

/**
 * Configuration for [WebRTCManager].
 */
data class WebRTCConfig(
    val webrtcUrl: String,
    val logger: Logger? = null,
    val onDiagnostic: DiagnosticEmitter? = null,
    val onRemoteVideoTrack: (VideoTrack) -> Unit,
    val onRemoteAudioTrack: ((AudioTrack) -> Unit)? = null,
    val onConnectionStateChange: ((ConnectionState) -> Unit)? = null,
    val onError: ((Exception) -> Unit)? = null,
    val vp8MinBitrate: Int? = null,
    val vp8StartBitrate: Int? = null,
    val initialImage: String? = null,
    val initialPrompt: InitialPrompt? = null
)

private val PERMANENT_ERRORS = listOf(
    "permission denied",
    "not allowed",
    "invalid session",
    "401",
    "invalid api key",
    "unauthorized"
)

private const val CONNECTION_TIMEOUT = 5L * 60 * 1000 // 5 minutes

private const val MAX_RETRIES = 5
private const val RETRY_FACTOR = 2
private const val RETRY_MIN_TIMEOUT = 1000L
private const val RETRY_MAX_TIMEOUT = 10000L

/**
 * Wraps [WebRTCConnection] with a state machine and automatic reconnection
 * using exponential backoff.
 *
 * Ported from the JS SDK's webrtc-manager.ts.
 */
class WebRTCManager(private val config: WebRTCConfig) {

    private val logger: Logger = config.logger ?: NoopLogger

    private val connection: WebRTCConnection = WebRTCConnection(
        ConnectionCallbacks(
            onRemoteVideoTrack = config.onRemoteVideoTrack,
            onRemoteAudioTrack = config.onRemoteAudioTrack,
            onStateChange = { state -> handleConnectionStateChange(state) },
            onError = config.onError,
            vp8MinBitrate = config.vp8MinBitrate,
            vp8StartBitrate = config.vp8StartBitrate,
            initialImage = config.initialImage,
            initialPrompt = config.initialPrompt,
            logger = logger,
            onDiagnostic = config.onDiagnostic
        )
    )

    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var subscribeMode = false

    private var managerState: ConnectionState = ConnectionState.DISCONNECTED
    private var hasConnected = false
    private var isReconnecting = false
    private var intentionalDisconnect = false
    private var reconnectGeneration = 0

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── State emission ─────────────────────────────────────────────────

    private fun emitState(state: ConnectionState) {
        if (managerState != state) {
            managerState = state
            if (state == ConnectionState.CONNECTED || state == ConnectionState.GENERATING) {
                hasConnected = true
            }
            config.onConnectionStateChange?.invoke(state)
        }
    }

    // ── Connection state change handling ────────────────────────────────

    private fun handleConnectionStateChange(state: ConnectionState) {
        if (intentionalDisconnect) {
            emitState(ConnectionState.DISCONNECTED)
            return
        }

        // During reconnection, intercept state changes from the connection layer
        if (isReconnecting) {
            if (state == ConnectionState.CONNECTED || state == ConnectionState.GENERATING) {
                isReconnecting = false
                emitState(state)
            }
            return
        }

        // Unexpected disconnect after having been connected -> trigger auto-reconnect
        // hasConnected guards against triggering during initial connect (which has its own retry loop)
        if (state == ConnectionState.DISCONNECTED && !intentionalDisconnect && hasConnected) {
            scope.launch { reconnect() }
            return
        }

        emitState(state)
    }

    // ── Exponential backoff helper ──────────────────────────────────────

    /**
     * Retries [block] up to [maxRetries] times with exponential backoff.
     * Throws immediately on permanent errors or intentional disconnect.
     */
    private suspend fun retryWithBackoff(
        maxRetries: Int,
        block: suspend (attempt: Int) -> Unit
    ) {
        var currentDelay = RETRY_MIN_TIMEOUT
        for (attempt in 1..maxRetries + 1) {
            try {
                block(attempt)
                return
            } catch (e: Exception) {
                if (attempt > maxRetries) throw e
                if (isPermanentError(e)) throw e
                if (intentionalDisconnect) throw Exception("Connect cancelled")
                delay(currentDelay)
                currentDelay = minOf(currentDelay * RETRY_FACTOR, RETRY_MAX_TIMEOUT)
            }
        }
    }

    private fun isPermanentError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return PERMANENT_ERRORS.any { msg.contains(it) }
    }

    // ── Connect ─────────────────────────────────────────────────────────

    /**
     * Establishes a WebRTC connection with retry logic.
     *
     * @param localVideoTrack local video track to send, or null for subscribe mode
     * @param localAudioTrack local audio track to send, or null
     * @param peerConnectionFactory factory for creating the peer connection
     * @return true on successful connection
     */
    suspend fun connect(
        localVideoTrack: VideoTrack?,
        localAudioTrack: AudioTrack?,
        peerConnectionFactory: PeerConnectionFactory
    ): Boolean {
        this.localVideoTrack = localVideoTrack
        this.localAudioTrack = localAudioTrack
        this.peerConnectionFactory = peerConnectionFactory
        this.subscribeMode = localVideoTrack == null
        this.intentionalDisconnect = false
        this.hasConnected = false
        this.isReconnecting = false
        this.reconnectGeneration += 1
        emitState(ConnectionState.CONNECTING)

        retryWithBackoff(MAX_RETRIES) { attempt ->
            if (intentionalDisconnect) {
                throw Exception("Connect cancelled")
            }

            try {
                connection.connect(
                    config.webrtcUrl,
                    localVideoTrack,
                    localAudioTrack,
                    peerConnectionFactory,
                    CONNECTION_TIMEOUT
                )
            } catch (e: Exception) {
                logger.warn(
                    "Connection attempt failed",
                    mapOf("error" to e.message, "attempt" to attempt)
                )
                connection.cleanup()
                throw e
            }
        }

        return true
    }

    // ── Reconnect ───────────────────────────────────────────────────────

    /**
     * Automatically reconnects after an unexpected disconnection, using
     * exponential backoff. Emits [ConnectionState.RECONNECTING] while active.
     */
    private suspend fun reconnect() {
        if (isReconnecting || intentionalDisconnect) return
        if (!subscribeMode && localVideoTrack == null) return

        val factory = peerConnectionFactory ?: return
        val reconnectGen = ++reconnectGeneration
        isReconnecting = true
        emitState(ConnectionState.RECONNECTING)
        val reconnectStartNs = System.nanoTime()

        try {
            var attemptCount = 0

            retryWithBackoff(MAX_RETRIES) { attempt ->
                attemptCount = attempt

                if (intentionalDisconnect || reconnectGen != reconnectGeneration) {
                    throw Exception("Reconnect cancelled")
                }

                if (!subscribeMode && localVideoTrack == null) {
                    throw Exception("Reconnect cancelled: no local video track")
                }

                connection.cleanup()

                try {
                    connection.connect(
                        config.webrtcUrl,
                        localVideoTrack,
                        localAudioTrack,
                        factory,
                        CONNECTION_TIMEOUT
                    )
                } catch (e: Exception) {
                    if (intentionalDisconnect || reconnectGen != reconnectGeneration) {
                        connection.cleanup()
                        throw Exception("Reconnect cancelled")
                    }

                    logger.warn(
                        "Reconnect attempt failed",
                        mapOf("error" to e.message, "attempt" to attempt)
                    )
                    config.onDiagnostic?.invoke(
                        DiagnosticEvent.Reconnect(
                            ReconnectEvent(
                                attempt = attempt,
                                maxAttempts = MAX_RETRIES + 1,
                                durationMs = (System.nanoTime() - reconnectStartNs) / 1_000_000.0,
                                success = false,
                                error = e.message
                            )
                        )
                    )
                    connection.cleanup()
                    throw e
                }

                if (intentionalDisconnect || reconnectGen != reconnectGeneration) {
                    connection.cleanup()
                    throw Exception("Reconnect cancelled")
                }
            }

            // Success
            config.onDiagnostic?.invoke(
                DiagnosticEvent.Reconnect(
                    ReconnectEvent(
                        attempt = attemptCount,
                        maxAttempts = MAX_RETRIES + 1,
                        durationMs = (System.nanoTime() - reconnectStartNs) / 1_000_000.0,
                        success = true
                    )
                )
            )
            // "connected" state is emitted by handleConnectionStateChange
        } catch (error: Exception) {
            isReconnecting = false
            if (intentionalDisconnect || reconnectGen != reconnectGeneration) {
                return
            }
            emitState(ConnectionState.DISCONNECTED)
            config.onError?.invoke(error)
        }
    }

    // ── Message sending ─────────────────────────────────────────────────

    /**
     * Sends a signaling message through the underlying connection.
     * @return true if the message was enqueued, false if the socket is closed
     */
    fun sendMessage(message: ClientMessage): Boolean {
        return connection.send(message)
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    /**
     * Tears down the connection and prevents any further reconnection attempts.
     */
    fun cleanup() {
        intentionalDisconnect = true
        isReconnecting = false
        reconnectGeneration += 1
        connection.cleanup()
        localVideoTrack = null
        localAudioTrack = null
        emitState(ConnectionState.DISCONNECTED)
    }

    // ── Accessors ───────────────────────────────────────────────────────

    /**
     * Returns true if the manager is in [ConnectionState.CONNECTED] or
     * [ConnectionState.GENERATING].
     */
    fun isConnected(): Boolean {
        return managerState == ConnectionState.CONNECTED ||
                managerState == ConnectionState.GENERATING
    }

    /**
     * Returns the current public-facing connection state.
     */
    fun getConnectionState(): ConnectionState = managerState

    /**
     * Returns the underlying [PeerConnection], or null if not connected.
     */
    fun getPeerConnection(): PeerConnection? = connection.getPeerConnection()

    /**
     * Exposes the connection's shared flows for receiving server messages.
     */
    fun getWebsocketMessageFlows(): WebsocketMessageFlows = WebsocketMessageFlows(
        promptAckFlow = connection.promptAckFlow,
        setImageAckFlow = connection.setImageAckFlow,
        sessionIdFlow = connection.sessionIdFlow,
        generationTickFlow = connection.generationTickFlow
    )

    // ── Image ───────────────────────────────────────────────────────────

    /**
     * Sends an image (or null to clear) to the server and suspends until
     * acknowledged.
     */
    suspend fun setImage(
        imageBase64: String?,
        options: WebRTCConnection.SetImageOptions = WebRTCConnection.SetImageOptions()
    ) {
        connection.setImageBase64(imageBase64, options)
    }
}

/**
 * Groups the shared flows exposed by [WebRTCConnection] for convenient access.
 */
data class WebsocketMessageFlows(
    val promptAckFlow: SharedFlow<PromptAckMessage>,
    val setImageAckFlow: SharedFlow<SetImageAckMessage>,
    val sessionIdFlow: SharedFlow<SessionIdMessage>,
    val generationTickFlow: SharedFlow<GenerationTickMessage>
)
