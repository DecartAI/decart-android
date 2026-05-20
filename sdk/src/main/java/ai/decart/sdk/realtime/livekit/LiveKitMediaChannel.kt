package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.DiagnosticEmitter
import ai.decart.sdk.realtime.DiagnosticEvent
import ai.decart.sdk.realtime.LiveKitRoomInfoMessage
import ai.decart.sdk.realtime.PublishStatsEvent
import ai.decart.sdk.realtime.RealtimeConfiguration
import ai.decart.sdk.realtime.RealtimeMediaStream
import android.content.Context
import io.livekit.android.ConnectOptions as LiveKitConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/**
 * Owns the LiveKit [Room] for one realtime session. The Room is normally
 * supplied by [LocalStreamFactory.createCameraStream] so preview, capture
 * and publish share a single Room; if none is provided we create our own.
 */
internal class LiveKitMediaChannel(
    private val context: Context,
    private val connectOptions: LiveKitConnectOptions,
    private val roomOptions: RoomOptions,
    private val videoConfig: RealtimeConfiguration.VideoConfig,
    private val logger: Logger = NoopLogger,
    private val diagnostics: DiagnosticEmitter? = null,
) {
    data class DisconnectInfo(val reason: String?)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var room: Room? = null
    private var ownsRoom: Boolean = false
    private var roomEventsJob: Job? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var firstFrameSink: FirstFrameSinkRef? = null
    private var roomConnectedAtNs: Long = 0L
    private var publishStatsJob: Job? = null

    private val _remoteStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1)
    private val _connectionStateUpdates = MutableSharedFlow<ConnectionState>(replay = 1)
    private val _disconnectUpdates = MutableSharedFlow<DisconnectInfo>(replay = 1)
    private val _firstFrameEvents = MutableSharedFlow<FirstFrameEventInternal>(replay = 1)

    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream> = _remoteStreamUpdates
    val connectionStateUpdates: SharedFlow<ConnectionState> = _connectionStateUpdates
    val disconnectUpdates: SharedFlow<DisconnectInfo> = _disconnectUpdates
    val firstFrameEvents: SharedFlow<FirstFrameEventInternal> = _firstFrameEvents

    suspend fun connect(roomInfo: LiveKitRoomInfoMessage, providedRoom: Room?) {
        roomConnectedAtNs = System.nanoTime()
        val nextRoom = providedRoom ?: createOwnedRoom()
        adoptRoom(nextRoom, owns = providedRoom == null)
        remoteVideoTrack = null
        remoteAudioTrack = null
        detachFirstFrameSink()

        nextRoom.connect(roomInfo.liveKitUrl, roomInfo.token, connectOptions)
        emitExistingRemoteTracks(nextRoom)
    }

    suspend fun publishLocalTracks(stream: RealtimeMediaStream) {
        val participant = requireNotNull(room?.localParticipant) { "LiveKit room is not connected" }
        (stream.videoTrack as? LocalVideoTrack)?.let { track ->
            // Capture was started by LocalStreamFactory; do not stop it on
            // re-publish — Camera2 can freeze on restart.
            track.start()
            val published = participant.publishVideoTrack(track, videoConfig.publishOptions())
            if (!published) {
                throw IllegalStateException("Failed to publish local video track")
            }
            startPublishStatsLoop(track)
        }
        (stream.audioTrack as? LocalAudioTrack)?.let { track ->
            track.start()
            val published = participant.publishAudioTrack(track)
            if (!published) {
                throw IllegalStateException("Failed to publish local audio track")
            }
        }
    }

    /**
     * Polls outbound RTP stats and emits [DiagnosticEvent.PublishStats]. If
     * bytesSent / framesEncoded stay at zero we're publishing nothing and
     * the server has nothing to generate from.
     */
    private fun startPublishStatsLoop(track: LocalVideoTrack) {
        publishStatsJob?.cancel()
        publishStatsJob = scope.launch {
            var lastBytesSent: Long = 0
            var lastFramesEncoded: Long = 0
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val report = try {
                    track.getRTCStats()
                } catch (_: Exception) {
                    null
                } ?: continue

                var bytesSent: Long = 0
                var framesEncoded: Long = 0
                var frameWidth: Long = 0
                var frameHeight: Long = 0
                var encoderImplementation: String? = null
                var qualityLimitationReason: String? = null

                report.statsMap.values.forEach { stat ->
                    if (stat.type != "outbound-rtp") return@forEach
                    val members = stat.members
                    (members["kind"] as? String)?.let { if (it != "video") return@forEach }
                    (members["bytesSent"] as? Number)?.toLong()?.let { bytesSent += it }
                    (members["framesEncoded"] as? Number)?.toLong()?.let { framesEncoded += it }
                    (members["frameWidth"] as? Number)?.toLong()?.let { if (it > frameWidth) frameWidth = it }
                    (members["frameHeight"] as? Number)?.toLong()?.let { if (it > frameHeight) frameHeight = it }
                    encoderImplementation = encoderImplementation
                        ?: members["encoderImplementation"] as? String
                    qualityLimitationReason = qualityLimitationReason
                        ?: members["qualityLimitationReason"] as? String
                }

                val deltaBytes = bytesSent - lastBytesSent
                val deltaFrames = framesEncoded - lastFramesEncoded
                lastBytesSent = bytesSent
                lastFramesEncoded = framesEncoded

                diagnostics?.invoke(
                    DiagnosticEvent.PublishStats(
                        PublishStatsEvent(
                            bytesSent = bytesSent,
                            deltaBytes = deltaBytes,
                            framesEncoded = framesEncoded,
                            deltaFrames = deltaFrames,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                            encoderImplementation = encoderImplementation,
                            qualityLimitationReason = qualityLimitationReason,
                        ),
                    ),
                )
            }
        }
    }

    private fun stopPublishStatsLoop() {
        publishStatsJob?.cancel()
        publishStatsJob = null
    }

    val currentRemoteStream: RealtimeMediaStream
        get() = RealtimeMediaStream(
            videoTrack = remoteVideoTrack,
            audioTrack = remoteAudioTrack,
            id = RealtimeMediaStream.REMOTE_STREAM_ID,
            room = room,
        )

    /**
     * `Room.disconnect()` is terminal on LiveKit Android — local tracks are
     * disposed, so callers must build a fresh Room for the next reconnect.
     */
    fun disconnect() {
        val currentRoom = room
        room = null
        ownsRoom = false
        roomEventsJob?.cancel()
        roomEventsJob = null
        stopPublishStatsLoop()
        detachFirstFrameSink()
        remoteVideoTrack = null
        remoteAudioTrack = null
        if (currentRoom != null) {
            try {
                currentRoom.disconnect()
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    fun cleanup() {
        scope.cancel()
    }

    private fun adoptRoom(newRoom: Room, owns: Boolean) {
        if (room === newRoom) return
        room = newRoom
        ownsRoom = owns
        roomEventsJob?.cancel()
        roomEventsJob = scope.launch {
            newRoom.events.collect { event -> handleRoomEvent(newRoom, event) }
        }
    }

    private fun createOwnedRoom(): Room =
        LiveKit.create(
            appContext = context.applicationContext,
            options = roomOptions,
        )

    private fun handleRoomEvent(room: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected -> {
                logTiming("ROOM_CONNECTED")
                _connectionStateUpdates.tryEmit(ConnectionState.CONNECTED)
            }
            is RoomEvent.Reconnecting -> _connectionStateUpdates.tryEmit(ConnectionState.RECONNECTING)
            is RoomEvent.Reconnected -> _connectionStateUpdates.tryEmit(ConnectionState.CONNECTED)
            is RoomEvent.Disconnected -> {
                logger.warn(
                    "livekit: room disconnected",
                    mapOf("reason" to event.reason.name),
                )
                _connectionStateUpdates.tryEmit(ConnectionState.DISCONNECTED)
                _disconnectUpdates.tryEmit(DisconnectInfo(event.error?.message ?: event.reason.name))
            }
            is RoomEvent.ParticipantConnected -> {
                logTiming("PARTICIPANT_CONNECTED identity=${event.participant.identity?.value}")
                emitExistingRemoteTracks(room)
            }
            is RoomEvent.TrackPublished -> {
                logTiming(
                    "REMOTE_TRACK_PUBLISHED identity=${event.participant.identity?.value} " +
                        "kind=${event.publication.kind.name} source=${event.publication.source.name} sid=${event.publication.sid}",
                )
                (event.publication as? RemoteTrackPublication)?.setSubscribed(true)
                emitExistingRemoteTracks(room)
            }
            is RoomEvent.TrackSubscribed -> {
                logTiming(
                    "REMOTE_TRACK_SUBSCRIBED identity=${event.participant.identity?.value} " +
                        "kind=${event.track.kind.name} sid=${event.publication.sid}",
                )
                handleRemoteTrack(event.participant, event.track)
            }
            is RoomEvent.LocalTrackSubscribed -> {
                logTiming(
                    "LOCAL_TRACK_SUBSCRIBED sid=${event.publication.sid} source=${event.publication.source.name}",
                )
            }
            is RoomEvent.TrackSubscriptionFailed -> {
                logger.warn(
                    "livekit: track subscription failed",
                    mapOf(
                        "identity" to event.participant.identity?.value,
                        "sid" to event.sid,
                        "error" to event.exception.message,
                    ),
                )
            }
            is RoomEvent.FailedToConnect -> {
                logger.error(
                    "livekit: failed to connect",
                    mapOf("error" to event.error.message),
                )
            }
            else -> Unit
        }
    }

    private fun logTiming(message: String) {
        val ref = roomConnectedAtNs
        val deltaMs = if (ref == 0L) 0.0 else (System.nanoTime() - ref) / 1_000_000.0
        android.util.Log.i("DecartTiming", "lk-room t+${"%.0f".format(deltaMs)}ms $message")
    }

    private fun emitExistingRemoteTracks(room: Room) {
        room.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach { publication ->
                val remotePub = publication as? RemoteTrackPublication
                remotePub?.setSubscribed(true)
                publication.track?.let { track -> handleRemoteTrack(participant, track) }
            }
        }
    }

    private fun handleRemoteTrack(participant: RemoteParticipant, track: Track) {
        val identity = participant.identity?.value
        if (!shouldAcceptTrack(identity)) return
        when (track) {
            is VideoTrack -> {
                if (remoteVideoTrack !== track) {
                    remoteVideoTrack = track
                    attachFirstFrameSink(track)
                }
                emitRemoteStreamIfAvailable()
            }
            is AudioTrack -> {
                remoteAudioTrack = track
                emitRemoteStreamIfAvailable()
            }
            else -> Unit
        }
    }

    private fun attachFirstFrameSink(track: VideoTrack) {
        detachFirstFrameSink()
        val startedAtNs = roomConnectedAtNs.takeIf { it != 0L } ?: System.nanoTime()
        val sink = FirstFrameSink { frame ->
            val deltaMs = (System.nanoTime() - startedAtNs) / 1_000_000.0
            val event = FirstFrameEventInternal(
                timeSinceConnectMs = deltaMs,
                width = frame?.rotatedWidth,
                height = frame?.rotatedHeight,
            )
            _firstFrameEvents.tryEmit(event)
            scope.launch { detachFirstFrameSink() }
        }
        try {
            track.addRenderer(sink)
            firstFrameSink = FirstFrameSinkRef(track, sink)
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun detachFirstFrameSink() {
        val ref = firstFrameSink ?: return
        firstFrameSink = null
        try {
            ref.track.removeRenderer(ref.sink)
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun emitRemoteStreamIfAvailable() {
        if (remoteVideoTrack != null) {
            _remoteStreamUpdates.tryEmit(currentRemoteStream)
        }
    }

    private fun shouldAcceptTrack(identity: String?): Boolean =
        identity?.startsWith(INFERENCE_SERVER_PREFIX) == true

    internal data class FirstFrameEventInternal(
        val timeSinceConnectMs: Double,
        val width: Int?,
        val height: Int?,
    )

    private class FirstFrameSink(private val onFirstFrame: (VideoFrame?) -> Unit) : VideoSink {
        @Volatile private var fired: Boolean = false
        override fun onFrame(frame: VideoFrame?) {
            if (fired) return
            fired = true
            onFirstFrame(frame)
        }
    }

    private data class FirstFrameSinkRef(val track: VideoTrack, val sink: FirstFrameSink)

    companion object {
        private const val INFERENCE_SERVER_PREFIX = "inference-server-"
        private const val STATS_INTERVAL_MS = 500L
    }
}
