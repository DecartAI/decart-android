package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.realtime.livekit.LiveKitMediaChannel
import ai.decart.sdk.realtime.livekit.LocalStreamFactory
import android.content.Context
import io.livekit.android.room.track.LocalVideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

internal data class ConnectionLossCause(
    val source: String,
    val reason: String? = null,
    val code: Int? = null,
)

private class StaleAttemptException : CancellationException("Stale connect attempt")

internal data class RealtimeSessionConfig(
    val context: Context,
    val signalingUrl: String,
    val model: RealtimeModel,
    val logger: Logger? = null,
    val realtimeConfiguration: RealtimeConfiguration = RealtimeConfiguration(),
    val initialImage: String? = null,
    val initialPrompt: InitialPrompt? = null,
    val publishCamera: Boolean = true,
    val facing: FacingMode = FacingMode.FRONT,
    val onDiagnostic: DiagnosticEmitter? = null,
    val onLocalStream: (RealtimeMediaStream) -> Unit,
    val onRemoteStream: (RealtimeMediaStream) -> Unit,
    val onConnectionStateChange: (ConnectionState) -> Unit,
    val onSessionStarted: (SessionStarted) -> Unit,
    val onGenerationTick: (GenerationTickMessage) -> Unit,
    val onGenerationEnded: (GenerationEndedMessage) -> Unit,
    val onQueuePosition: (QueuePositionMessage) -> Unit,
    val onError: (Exception, String?) -> Unit,
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

    private var reconnectJob: Job? = null
    private var disposed: Boolean = false
    private var permanentError: Boolean = false
    private var hasEstablishedSession: Boolean = false
    private var currentAttempt: Int = 0

    // Holds back remote-stream forwarding while the initial-state ack is in
    // flight — otherwise callers would see frames from the previous prompt.
    private val initialStateGate = InitialStateGate()
    private var pendingRemoteStream: RealtimeMediaStream? = null
    private var remoteStreamGateOpen: Boolean = true
    private var remoteStreamCollectorJob: Job? = null

    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream>?
        get() = mediaChannel?.remoteStreamUpdates

    suspend fun connect(localStream: RealtimeMediaStream?): RealtimeMediaStream {
        disposed = false
        permanentError = false
        hasEstablishedSession = false
        providedLocalStream = localStream

        emitState(ConnectionState.CONNECTING)
        val attemptCycle = ++currentAttempt
        logger.debug("realtime connect: starting", mapOf("attemptCycle" to attemptCycle))

        val totalStart = System.nanoTime()
        return try {
            val remote = runWithRetry(attemptCycle) { attempt -> runOneConnect(attempt) }
            emitPhase(ConnectionPhase.TOTAL, totalStart, success = true)
            remote
        } catch (error: Exception) {
            emitPhase(ConnectionPhase.TOTAL, totalStart, success = false, error = error.message)
            if (!disposed) {
                logger.error(
                    "realtime connect: exhausted all retries",
                    mapOf("error" to error.message),
                )
                tearDownTransports()
                emitState(ConnectionState.DISCONNECTED)
            }
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

    suspend fun setPrompt(prompt: String, enhance: Boolean, timeoutMs: Long) {
        signalingChannel?.sendPrompt(prompt, enhance, timeoutMs)
            ?: throw IllegalStateException("Not connected")
    }

    fun disconnect() {
        disposed = true
        hasEstablishedSession = false
        reconnectJob?.cancel()
        reconnectJob = null
        tearDownTransports()
        initialStateGate.reset()
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

    private suspend fun <T> runWithRetry(
        attemptCycle: Int,
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < RealtimeRetryPolicy.MAX_ATTEMPTS && !disposed && !permanentError) {
            if (currentAttempt != attemptCycle) {
                throw StaleAttemptException()
            }
            if (attempt > 0) {
                val delayMs = RealtimeRetryPolicy.delayMsFor(attempt - 1)
                delay(delayMs)
                if (disposed || permanentError || currentAttempt != attemptCycle) {
                    throw StaleAttemptException()
                }
                tearDownTransports()
                resetLocalStreamForFreshLiveKitRoom()
            }

            val attemptStart = System.nanoTime()
            try {
                val result = block(attempt)
                if (attempt > 0) {
                    config.onDiagnostic?.invoke(
                        DiagnosticEvent.Reconnect(
                            ReconnectEvent(
                                attempt = attempt,
                                maxAttempts = RealtimeRetryPolicy.MAX_ATTEMPTS,
                                durationMs = (System.nanoTime() - attemptStart) / 1_000_000.0,
                                success = true,
                            ),
                        ),
                    )
                }
                return result
            } catch (e: StaleAttemptException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt > 0) {
                    config.onDiagnostic?.invoke(
                        DiagnosticEvent.Reconnect(
                            ReconnectEvent(
                                attempt = attempt,
                                maxAttempts = RealtimeRetryPolicy.MAX_ATTEMPTS,
                                durationMs = (System.nanoTime() - attemptStart) / 1_000_000.0,
                                success = false,
                                error = e.message,
                            ),
                        ),
                    )
                }
                if (RealtimeRetryPolicy.isPermanentError(e.message)) {
                    permanentError = true
                    logger.error(
                        "realtime connect: permanent error, not retrying",
                        mapOf("error" to e.message),
                    )
                    throw e
                }
                attempt += 1
            }
        }
        throw lastError ?: IllegalStateException("Connect retries exhausted")
    }

    private suspend fun runOneConnect(attempt: Int): RealtimeMediaStream {
        if (disposed) throw StaleAttemptException()

        val signaling = SignalingChannel(
            logger = logger,
            onStateChange = ::emitState,
            onError = ::handleSignalingError,
            onClosed = { code, reason ->
                handleConnectionLoss(
                    ConnectionLossCause(source = "signaling", reason = reason, code = code),
                )
            },
        )
        signalingChannel = signaling

        val media = LiveKitMediaChannel(
            context = config.context,
            connectOptions = config.realtimeConfiguration.connection.connectOptions(),
            roomOptions = config.realtimeConfiguration.roomOptions(),
            videoConfig = config.realtimeConfiguration.media.video,
            logger = logger,
            diagnostics = config.onDiagnostic,
        )
        mediaChannel = media
        listenToMedia(media)

        val localStream = ensureLocalStream()

        val initialState = getInitialState()
        val gateAttempt = initialStateGate.startAttempt(initialState)
        remoteStreamGateOpen = !gateAttempt.shouldWait
        pendingRemoteStream = null

        val wsStart = System.nanoTime()
        signaling.connect(
            config.signalingUrl,
            config.realtimeConfiguration.connection.connectionTimeoutMs,
        )
        emitPhase(ConnectionPhase.WEBSOCKET, wsStart, success = true)
        listenToSignaling(signaling)

        if (disposed) throw StaleAttemptException()

        val roomInfo = signaling.sendLiveKitJoin(
            config.realtimeConfiguration.connection.connectionTimeoutMs,
        )

        if (disposed) {
            tearDownTransports()
            throw StaleAttemptException()
        }

        // Initial-state ack and LiveKit room connect run in parallel,
        // gated by waitForReadiness before publishing local tracks.
        val initialStateAckDeferred = scope.async { sendInitialState(signaling, initialState) }
        val connectStart = System.nanoTime()
        coroutineScope {
            val mediaConnect = async {
                media.connect(roomInfo, localStream?.room)
                emitPhase(ConnectionPhase.LIVEKIT_CONNECT, connectStart, success = true)
            }
            awaitAll(mediaConnect, initialStateAckDeferred)
        }

        val isCurrent = gateAttempt.waitForReadiness(initialStateAckDeferred)
        if (!isCurrent || disposed) {
            tearDownTransports()
            throw StaleAttemptException()
        }
        remoteStreamGateOpen = true
        pendingRemoteStream?.let { config.onRemoteStream(it) }
        pendingRemoteStream = null

        if (localStream != null) {
            val publishStart = System.nanoTime()
            media.publishLocalTracks(localStream)
            emitPhase(ConnectionPhase.LIVEKIT_PUBLISH, publishStart, success = true)
        }

        if (disposed) {
            tearDownTransports()
            throw StaleAttemptException()
        }

        if (managerState != ConnectionState.GENERATING) {
            emitState(ConnectionState.CONNECTED)
        }
        hasEstablishedSession = true
        config.onSessionStarted(
            SessionStarted(
                sessionId = roomInfo.sessionId,
                subscribeToken = encodeSubscribeToken(roomInfo.roomName),
            ),
        )
        return media.currentRemoteStream
    }

    private fun ensureLocalStream(): RealtimeMediaStream? {
        providedLocalStream?.let { return it }
        if (!config.publishCamera) return null

        val stream = LocalStreamFactory.createCameraStream(
            context = config.context,
            configuration = config.realtimeConfiguration,
            width = config.model.width,
            height = config.model.height,
            facing = config.facing,
            logger = logger,
        )
        sdkOwnedLocalStream = stream
        config.onLocalStream(stream)
        return stream
    }

    private fun getInitialState(): InitialState? {
        if (config.initialImage != null) {
            return InitialState(
                image = config.initialImage,
                prompt = config.initialPrompt?.text,
                enhance = config.initialPrompt?.enhance,
            )
        }
        if (config.initialPrompt != null) {
            return InitialState(
                prompt = config.initialPrompt.text,
                enhance = config.initialPrompt.enhance,
            )
        }
        if (providedLocalStream != null || sdkOwnedLocalStream != null || config.publishCamera) {
            return InitialState(image = null, prompt = null)
        }
        return null
    }

    private suspend fun sendInitialState(
        signaling: SignalingChannel,
        initialState: InitialState?,
    ) {
        if (initialState == null) return
        val timeoutMs = config.realtimeConfiguration.connection.connectionTimeoutMs
        when {
            initialState.image != null -> {
                val imageStart = System.nanoTime()
                signaling.setImage(
                    initialState.image,
                    SignalingChannel.SetImageOptions(
                        prompt = initialState.prompt,
                        enhance = initialState.enhance,
                        timeout = timeoutMs,
                    ),
                )
                emitPhase(ConnectionPhase.AVATAR_IMAGE, imageStart, success = true)
            }
            initialState.prompt != null -> {
                val promptStart = System.nanoTime()
                signaling.sendPrompt(
                    initialState.prompt,
                    initialState.enhance ?: true,
                    timeoutMs,
                )
                emitPhase(ConnectionPhase.INITIAL_PROMPT, promptStart, success = true)
            }
            else -> {
                val passthroughStart = System.nanoTime()
                signaling.setImage(null, SignalingChannel.SetImageOptions(timeout = timeoutMs))
                emitPhase(ConnectionPhase.INITIAL_PROMPT, passthroughStart, success = true)
            }
        }
    }

    private fun listenToSignaling(signaling: SignalingChannel) {
        scope.launch {
            signaling.generationTickFlow.collect { config.onGenerationTick(it) }
        }
        scope.launch {
            signaling.generationEndedFlow.collect { config.onGenerationEnded(it) }
        }
        scope.launch {
            signaling.queuePositionFlow.collect { config.onQueuePosition(it) }
        }
    }

    private fun listenToMedia(media: LiveKitMediaChannel) {
        remoteStreamCollectorJob?.cancel()
        remoteStreamCollectorJob = scope.launch {
            media.remoteStreamUpdates.collect { stream ->
                if (remoteStreamGateOpen) {
                    config.onRemoteStream(stream)
                } else {
                    pendingRemoteStream = stream
                }
            }
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
                handleConnectionLoss(
                    ConnectionLossCause(source = "media", reason = info.reason),
                )
            }
        }
        scope.launch {
            media.firstFrameEvents.collect { event ->
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
        config.onError(error, source)
        if (RealtimeRetryPolicy.isPermanentError(error.message)) {
            permanentError = true
            logger.error(
                "realtime connect: permanent error, not retrying",
                mapOf("error" to error.message, "source" to source),
            )
            emitState(ConnectionState.DISCONNECTED)
            return
        }
        if (managerState == ConnectionState.CONNECTED || managerState == ConnectionState.GENERATING) {
            handleConnectionLoss(
                ConnectionLossCause(source = source ?: "signaling", reason = error.message),
            )
        } else {
            emitState(ConnectionState.DISCONNECTED)
        }
    }

    private fun handleConnectionLoss(cause: ConnectionLossCause) {
        if (disposed || permanentError) return
        if (managerState != ConnectionState.CONNECTED &&
            managerState != ConnectionState.GENERATING &&
            !hasEstablishedSession
        ) {
            logger.debug(
                "connection loss ignored (not connected)",
                mapOf("state" to managerState.name, "source" to cause.source, "reason" to cause.reason),
            )
            return
        }
        logger.warn(
            "realtime connection lost; scheduling reconnect",
            mapOf("state" to managerState.name, "source" to cause.source, "reason" to cause.reason),
        )
        scheduleReconnect(cause)
    }

    private fun scheduleReconnect(cause: ConnectionLossCause) {
        if (reconnectJob?.isActive == true) return
        if (providedLocalStream?.room != null) {
            logger.warn(
                "realtime reconnect skipped for caller-owned LiveKit room",
                mapOf("source" to cause.source, "reason" to cause.reason),
            )
            hasEstablishedSession = false
            tearDownTransports()
            emitState(ConnectionState.DISCONNECTED)
            config.onError(
                IllegalStateException(
                    "Cannot auto-reconnect a caller-provided LiveKit Room; create a new local stream and connect again",
                ),
                "livekit",
            )
            return
        }
        emitState(ConnectionState.RECONNECTING)
        val attemptCycle = ++currentAttempt
        reconnectJob = scope.launch {
            try {
                tearDownTransports()
                resetLocalStreamForFreshLiveKitRoom()
                runWithRetry(attemptCycle) { attempt -> runOneConnect(attempt) }
                logger.debug("realtime reconnect: succeeded")
            } catch (_: StaleAttemptException) {
                // Newer attempt superseded us; leave state to the winner.
            } catch (e: Exception) {
                logger.error(
                    "realtime reconnect: failed permanently",
                    mapOf("error" to e.message, "source" to cause.source),
                )
                hasEstablishedSession = false
                tearDownTransports()
                emitState(ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun tearDownTransports() {
        mediaChannel?.disconnect()
        mediaChannel?.cleanup()
        mediaChannel = null
        signalingChannel?.close()
        signalingChannel = null
        remoteStreamCollectorJob?.cancel()
        remoteStreamCollectorJob = null
        pendingRemoteStream = null
        remoteStreamGateOpen = true
    }

    private fun resetLocalStreamForFreshLiveKitRoom() {
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
        try { stream.room?.release() } catch (_: Exception) {}
    }

    private fun emitState(state: ConnectionState) {
        if (managerState == state) return
        logger.debug(
            "realtime state change",
            mapOf("from" to managerState.name, "to" to state.name),
        )
        managerState = state
        config.onConnectionStateChange(state)
    }

    private fun emitPhase(
        phase: ConnectionPhase,
        startNs: Long,
        success: Boolean,
        error: String? = null,
    ) {
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
