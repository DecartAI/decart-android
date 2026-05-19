package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.realtime.livekit.LiveKitMediaChannel
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream>?
        get() = mediaChannel?.remoteStreamUpdates

    suspend fun connect(): RealtimeMediaStream {
        emitState(ConnectionState.CONNECTING)
        val signaling = SignalingChannel(
            logger = logger,
            onStateChange = ::emitState,
            onError = { error, _ ->
                config.onError(error)
                emitState(ConnectionState.DISCONNECTED)
            },
        )
        signalingChannel = signaling

        val totalStart = System.nanoTime()
        val media = LiveKitMediaChannel(
            context = config.context,
            connectOptions = config.realtimeConfiguration.connection.connectOptions(),
            roomOptions = config.realtimeConfiguration.roomOptions(),
            videoConfig = config.realtimeConfiguration.media.video,
                logger = logger,
        )
        mediaChannel = media
        listenToMedia(media)

        val localStream = if (config.publishCamera) {
            media.createCameraStream(
                width = config.model.width,
                height = config.model.height,
                facing = config.facing,
                includeMicrophone = config.publishMicrophone,
            ).also(config.onLocalStream)
        } else {
            null
        }

        try {
            val wsStart = System.nanoTime()
            signaling.connect(config.signalingUrl, config.realtimeConfiguration.connection.connectionTimeoutMs)
            emitPhase(ConnectionPhase.WEBSOCKET, wsStart, success = true)
            listenToSignaling(signaling)

            val roomInfo = signaling.sendLiveKitJoin(config.realtimeConfiguration.connection.connectionTimeoutMs)
            config.onSessionId(roomInfo.sessionId)

            val connectStart = System.nanoTime()
            media.connect(roomInfo)
            emitPhase(ConnectionPhase.LIVEKIT_CONNECT, connectStart, success = true)

            sendInitialState(signaling)

            if (localStream != null) {
                val publishStart = System.nanoTime()
                media.publishLocalTracks(localStream)
                emitPhase(ConnectionPhase.LIVEKIT_PUBLISH, publishStart, success = true)
            }

            emitPhase(ConnectionPhase.TOTAL, totalStart, success = true)
            if (managerState != ConnectionState.GENERATING) {
                emitState(ConnectionState.CONNECTED)
            }
            return media.currentRemoteStream
        } catch (error: Exception) {
            emitPhase(ConnectionPhase.TOTAL, totalStart, success = false, error = error.message)
            emitState(ConnectionState.DISCONNECTED)
            mediaChannel?.disconnect()
            mediaChannel?.cleanup()
            mediaChannel = null
            signalingChannel?.close()
            signalingChannel = null
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
        mediaChannel?.disconnect()
        mediaChannel?.cleanup()
        mediaChannel = null
        signalingChannel?.close()
        signalingChannel = null
        scope.cancel()
        emitState(ConnectionState.DISCONNECTED)
    }

    fun cleanup() {
        disconnect()
    }

    fun isConnected(): Boolean =
        managerState == ConnectionState.CONNECTED || managerState == ConnectionState.GENERATING

    fun getConnectionState(): ConnectionState = managerState

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
            }
        }
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
