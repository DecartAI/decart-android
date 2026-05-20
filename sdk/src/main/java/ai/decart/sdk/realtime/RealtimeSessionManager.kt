package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.realtime.livekit.LiveKitMediaChannel
import ai.decart.sdk.realtime.livekit.LocalStreamFactory
import android.content.Context
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

internal data class RealtimeSessionConfig(
    val context: Context,
    val signalingUrl: String,
    val model: RealtimeModel,
    val logger: Logger? = null,
    val realtimeConfiguration: RealtimeConfiguration = RealtimeConfiguration(),
    val initialImage: String? = null,
    val initialPrompt: InitialPrompt? = null,
    val publishCamera: Boolean = true,
    val publishMicrophone: Boolean = false,
    val facing: FacingMode = FacingMode.FRONT,
    val onDiagnostic: DiagnosticEmitter? = null,
    val onLocalStream: (RealtimeMediaStream) -> Unit,
    val onRemoteStream: (RealtimeMediaStream) -> Unit,
    val onConnectionStateChange: (ConnectionState) -> Unit,
    val onSessionId: (String) -> Unit,
    val onGenerationTick: (GenerationTickMessage) -> Unit,
    val onError: (Exception) -> Unit,
)

internal class RealtimeSessionManager(
    private val config: RealtimeSessionConfig,
) {
    private val logger: Logger = config.logger ?: NoopLogger
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var signalingChannel: SignalingChannel? = null
    private var mediaChannel: LiveKitMediaChannel? = null
    private var managerState: ConnectionState = ConnectionState.DISCONNECTED

    private var providedLocalStream: RealtimeMediaStream? = null
    private var sdkOwnedLocalStream: RealtimeMediaStream? = null
    private val effectiveLocalStream: RealtimeMediaStream?
        get() = providedLocalStream ?: sdkOwnedLocalStream

    private var reconnectJob: Job? = null
    private var userInitiatedDisconnect: Boolean = false
    private var permanentError: Boolean = false
    private var hasEstablishedSession: Boolean = false

    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream>?
        get() = mediaChannel?.remoteStreamUpdates

    /**
     * Connect with an optional caller-provided local stream.
     *
     * When [localStream] is non-null, its Room is reused for the LiveKit
     * connection and the channel does not create or dispose anything. When
     * null, the SDK falls back to creating its own Room + camera track and
     * disposing them on [disconnect].
     */
    suspend fun connect(localStream: RealtimeMediaStream?): RealtimeMediaStream {
        userInitiatedDisconnect = false
        permanentError = false
        hasEstablishedSession = false
        providedLocalStream = localStream

        emitState(ConnectionState.CONNECTING)

        val totalStart = System.nanoTime()
        try {
            val remote = runOneConnect(totalStart)
            emitPhase(ConnectionPhase.TOTAL, totalStart, success = true)
            return remote
        } catch (error: Exception) {
            emitPhase(ConnectionPhase.TOTAL, totalStart, success = false, error = error.message)
            tearDownTransports()
            emitState(ConnectionState.DISCONNECTED)
            throw error
        }
    }

    fun sendMessage(message: ClientMessage): Boolean =
        signalingChannel?.send(message) ?: false

    suspend fun setImage(
        imageBase64: String?,
        options: SignalingChannel.SetImageOptions = SignalingChannel.SetImageOptions(),
    ) {
        signalingChannel?.setImage(imageBase64, options)
            ?: throw IllegalStateException("Not connected")
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        hasEstablishedSession = false
        reconnectJob?.cancel()
        reconnectJob = null
        tearDownTransports()
        disposeSdkOwnedLocalStream()
        providedLocalStream = null
        emitState(ConnectionState.DISCONNECTED)
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }

    fun isConnected(): Boolean =
        managerState == ConnectionState.CONNECTED || managerState == ConnectionState.GENERATING

    fun getConnectionState(): ConnectionState = managerState

    // -------------------------------------------------------------------------
    // Connect / reconnect implementation
    // -------------------------------------------------------------------------

    private suspend fun runOneConnect(totalStart: Long): RealtimeMediaStream {
        val signaling = SignalingChannel(
            logger = logger,
            onStateChange = ::emitState,
            onError = ::handleSignalingError,
        )
        signalingChannel = signaling

        val media = LiveKitMediaChannel(
            context = config.context,
            connectOptions = config.realtimeConfiguration.connection.connectOptions(),
            roomOptions = config.realtimeConfiguration.roomOptions(),
            videoConfig = config.realtimeConfiguration.media.video,
            logger = logger,
        )
        mediaChannel = media
        listenToMedia(media)

        // Ensure we have a local stream to publish.
        val localStream = ensureLocalStream()

        val wsStart = System.nanoTime()
        signaling.connect(
            config.signalingUrl,
            config.realtimeConfiguration.connection.connectionTimeoutMs,
        )
        emitPhase(ConnectionPhase.WEBSOCKET, wsStart, success = true)
        listenToSignaling(signaling)

        val roomInfo = signaling.sendLiveKitJoin(
            config.realtimeConfiguration.connection.connectionTimeoutMs,
        )
        config.onSessionId(roomInfo.sessionId)

        // Mirror JS stream-session.ts: run the initial-state ack and the
        // LiveKit Room connect in parallel, then publish local tracks only
        // after both have resolved.
        val connectStart = System.nanoTime()
        coroutineScope {
            val initialStateAck = async { sendInitialState(signaling) }
            val mediaConnect = async {
                media.connect(roomInfo, localStream?.room)
                emitPhase(ConnectionPhase.LIVEKIT_CONNECT, connectStart, success = true)
            }
            awaitAll(initialStateAck, mediaConnect)
        }

        if (localStream != null) {
            val publishStart = System.nanoTime()
            media.publishLocalTracks(localStream)
            emitPhase(ConnectionPhase.LIVEKIT_PUBLISH, publishStart, success = true)
        }

        if (managerState != ConnectionState.GENERATING) {
            emitState(ConnectionState.CONNECTED)
        }
        hasEstablishedSession = true
        return media.currentRemoteStream
    }

    private fun ensureLocalStream(): RealtimeMediaStream? {
        providedLocalStream?.let {
            return it
        }
        if (!config.publishCamera) return null

        val stream = LocalStreamFactory.createCameraStream(
            context = config.context,
            configuration = config.realtimeConfiguration,
            width = config.model.width,
            height = config.model.height,
            facing = config.facing,
            includeMicrophone = config.publishMicrophone,
            logger = logger,
        )
        sdkOwnedLocalStream = stream
        config.onLocalStream(stream)
        return stream
    }

    private suspend fun sendInitialState(signaling: SignalingChannel) {
        val timeoutMs = config.realtimeConfiguration.connection.connectionTimeoutMs
        if (config.initialImage != null) {
            val imageStart = System.nanoTime()
            signaling.setImage(
                config.initialImage,
                SignalingChannel.SetImageOptions(
                    prompt = config.initialPrompt?.text,
                    enhance = config.initialPrompt?.enhance,
                    timeout = timeoutMs,
                ),
            )
            emitPhase(ConnectionPhase.AVATAR_IMAGE, imageStart, success = true)
        } else if (config.initialPrompt != null) {
            val promptStart = System.nanoTime()
            signaling.sendInitialPrompt(config.initialPrompt, timeoutMs)
            emitPhase(ConnectionPhase.INITIAL_PROMPT, promptStart, success = true)
        } else if (config.publishCamera) {
            val passthroughStart = System.nanoTime()
            signaling.setImage(null, SignalingChannel.SetImageOptions(timeout = timeoutMs))
            emitPhase(ConnectionPhase.INITIAL_PROMPT, passthroughStart, success = true)
        }
    }

    private fun listenToSignaling(signaling: SignalingChannel) {
        scope.launch {
            signaling.sessionIdFlow.collect { config.onSessionId(it) }
        }
        scope.launch {
            signaling.generationTickFlow.collect { config.onGenerationTick(it) }
        }
    }

    private fun listenToMedia(media: LiveKitMediaChannel) {
        scope.launch {
            media.remoteStreamUpdates.collect { config.onRemoteStream(it) }
        }
        scope.launch {
            media.connectionStateUpdates.collect { state ->
                if (managerState == ConnectionState.GENERATING && state == ConnectionState.CONNECTED) {
                    return@collect
                }
                emitState(state)
            }
        }
        scope.launch {
            media.disconnectUpdates.collect { info ->
                info.reason?.let { logger.warn("LiveKit disconnected", mapOf("reason" to it)) }
                handleUnexpectedDisconnect(info.reason)
            }
        }
        scope.launch {
            media.firstFrameEvents.collect { event ->
                logger.info(
                    "Realtime first frame",
                    mapOf(
                        "ms" to event.timeSinceConnectMs,
                        "width" to event.width,
                        "height" to event.height,
                    ),
                )
                config.onDiagnostic?.invoke(
                    DiagnosticEvent.FirstFrame(
                        FirstFrameEvent(
                            timeSinceConnectMs = event.timeSinceConnectMs,
                            width = event.width,
                            height = event.height,
                        ),
                    ),
                )
                if (managerState == ConnectionState.CONNECTED) {
                    emitState(ConnectionState.GENERATING)
                }
            }
        }
    }

    private fun handleSignalingError(error: Exception, source: String?) {
        config.onError(error)
        if (RealtimeRetryPolicy.isPermanentError(error.message)) {
            permanentError = true
            logger.error(
                "Permanent signaling error, will not reconnect",
                mapOf("error" to error.message, "source" to source),
            )
            emitState(ConnectionState.DISCONNECTED)
            return
        }
        // Transient signaling error during steady state — schedule reconnect.
        if (managerState == ConnectionState.CONNECTED || managerState == ConnectionState.GENERATING) {
            handleUnexpectedDisconnect(error.message)
        } else {
            emitState(ConnectionState.DISCONNECTED)
        }
    }

    private fun handleUnexpectedDisconnect(reason: String?) {
        if (userInitiatedDisconnect || permanentError) return
        val shouldRetry = managerState == ConnectionState.CONNECTED ||
                managerState == ConnectionState.GENERATING ||
                hasEstablishedSession
        if (!shouldRetry) {
            return
        }
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String?) {
        if (reconnectJob?.isActive == true) return
        emitState(ConnectionState.RECONNECTING)
        reconnectJob = scope.launch {
            var attempt = 0
            while (attempt < RealtimeRetryPolicy.MAX_ATTEMPTS && !userInitiatedDisconnect && !permanentError) {
                val delayMs = RealtimeRetryPolicy.delayMsFor(attempt)
                logger.warn(
                    "Realtime reconnect: scheduling attempt",
                    mapOf("attempt" to attempt + 1, "delayMs" to delayMs, "reason" to reason),
                )
                delay(delayMs)
                if (userInitiatedDisconnect || permanentError) return@launch

                tearDownTransports()
                resetLocalStreamForFreshLiveKitRoom()
                val start = System.nanoTime()
                try {
                    runOneConnect(start)
                    config.onDiagnostic?.invoke(
                        DiagnosticEvent.Reconnect(
                            ReconnectEvent(
                                attempt = attempt + 1,
                                maxAttempts = RealtimeRetryPolicy.MAX_ATTEMPTS,
                                durationMs = (System.nanoTime() - start) / 1_000_000.0,
                                success = true,
                            ),
                        ),
                    )
                    logger.info("Realtime reconnect: succeeded", mapOf("attempt" to attempt + 1))
                    return@launch
                } catch (error: Exception) {
                    attempt += 1
                    config.onDiagnostic?.invoke(
                        DiagnosticEvent.Reconnect(
                            ReconnectEvent(
                                attempt = attempt,
                                maxAttempts = RealtimeRetryPolicy.MAX_ATTEMPTS,
                                durationMs = (System.nanoTime() - start) / 1_000_000.0,
                                success = false,
                                error = error.message,
                            ),
                        ),
                    )
                    if (RealtimeRetryPolicy.isPermanentError(error.message)) {
                        permanentError = true
                        logger.error(
                            "Realtime reconnect: permanent error, stopping",
                            mapOf("error" to error.message),
                        )
                        break
                    }
                    logger.warn(
                        "Realtime reconnect: attempt failed",
                        mapOf("attempt" to attempt, "error" to error.message),
                    )
                }
            }
            hasEstablishedSession = false
            emitState(ConnectionState.DISCONNECTED)
        }
    }

    private fun tearDownTransports() {
        mediaChannel?.disconnect()
        mediaChannel?.cleanup()
        mediaChannel = null
        signalingChannel?.close()
        signalingChannel = null
    }

    private fun resetLocalStreamForFreshLiveKitRoom() {
        if (providedLocalStream != null || sdkOwnedLocalStream != null) {
            logger.info("Realtime reconnect: replacing local LiveKit stream")
        }
        providedLocalStream = null
        disposeSdkOwnedLocalStream()
    }

    private fun disposeSdkOwnedLocalStream() {
        val stream = sdkOwnedLocalStream ?: return
        sdkOwnedLocalStream = null
        (stream.videoTrack as? LocalVideoTrack)?.let { track ->
            try { track.stopCapture() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
            try { track.dispose() } catch (_: Exception) {}
        }
        (stream.audioTrack as? LocalAudioTrack)?.let { track ->
            try { track.stop() } catch (_: Exception) {}
            try { track.dispose() } catch (_: Exception) {}
        }
        // The Room we created here is throwaway; disconnect it so its
        // EglBase / PeerConnectionFactory are freed.
        try { stream.room?.disconnect() } catch (_: Exception) {}
    }

    private fun emitState(state: ConnectionState) {
        if (managerState == state) return
        managerState = state
        config.onConnectionStateChange(state)
    }

    private fun emitPhase(phase: ConnectionPhase, startNs: Long, success: Boolean, error: String? = null) {
        config.onDiagnostic?.invoke(
            DiagnosticEvent.PhaseTiming(
                PhaseTimingEvent(
                    phase = phase,
                    durationMs = (System.nanoTime() - startNs) / 1_000_000.0,
                    success = success,
                    error = error,
                ),
            ),
        )
    }
}
