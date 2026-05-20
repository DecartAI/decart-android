package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.LiveKitRoomInfoMessage
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
 * Owns the LiveKit [Room] for one realtime session. The Room itself is normally
 * provided by the caller (via [LocalStreamFactory.createCameraStream]) so the
 * same Room is used for camera preview, capture, publish and remote rendering —
 * see the architecture notes in [LocalStreamFactory]. If no Room is provided the
 * channel will create one itself (used when the caller does not need a preview
 * track).
 */
internal class LiveKitMediaChannel(
    private val context: Context,
    private val connectOptions: LiveKitConnectOptions,
    private val roomOptions: RoomOptions,
    private val videoConfig: RealtimeConfiguration.VideoConfig,
    private val logger: Logger = NoopLogger,
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

    /**
     * Connect to LiveKit using a Room from the caller. The Room must have been
     * created (e.g. by [LocalStreamFactory.createCameraStream]) but not yet
     * connected — calling `room.connect` happens here.
     */
    suspend fun connect(roomInfo: LiveKitRoomInfoMessage, providedRoom: Room?) {
        val nextRoom = providedRoom ?: createOwnedRoom()
        adoptRoom(nextRoom, owns = providedRoom == null)
        remoteVideoTrack = null
        remoteAudioTrack = null
        detachFirstFrameSink()

        logger.info(
            "Connecting LiveKit room",
            mapOf(
                "url" to roomInfo.liveKitUrl,
                "room" to roomInfo.roomName,
                "session" to roomInfo.sessionId,
                "callerOwnedRoom" to (providedRoom != null),
            ),
        )

        nextRoom.connect(roomInfo.liveKitUrl, roomInfo.token, connectOptions)
        roomConnectedAtNs = System.nanoTime()

        logger.info(
            "Connected to LiveKit room",
            mapOf(
                "remoteParticipants" to nextRoom.remoteParticipants.size,
                "identities" to nextRoom.remoteParticipants.values
                    .map { it.identity?.value }
                    .toString(),
            ),
        )
        emitExistingRemoteTracks(nextRoom)
    }

    /**
     * Publish the local tracks contained in [stream] to the connected Room.
     * The track must have been created by the same Room that is now connected.
     */
    suspend fun publishLocalTracks(stream: RealtimeMediaStream) {
        val participant = requireNotNull(room?.localParticipant) { "LiveKit room is not connected" }
        (stream.videoTrack as? LocalVideoTrack)?.let { track ->
            // LocalStreamFactory starts capture before connect so the preview can render.
            // Keep the capturer running while publishing; LiveKit's track source is already
            // fed by the same capturer, and stopping it here can freeze Camera2 on restart.
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
     * Polls outbound RTP stats every few seconds. If we are publishing but
     * `bytesSent` / `framesEncoded` stay at zero, the encoder isn't producing
     * data and the inference server cannot generate output — this is the
     * single most useful signal we have when the AI Output pane stays blank.
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
                } catch (e: Exception) {
                    logger.warn("Failed to read publish stats", mapOf("error" to e.message))
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

                logger.info(
                    "LiveKit publish stats",
                    mapOf(
                        "bytesSent" to bytesSent,
                        "deltaBytes" to deltaBytes,
                        "framesEncoded" to framesEncoded,
                        "deltaFrames" to deltaFrames,
                        "frameWidth" to frameWidth,
                        "frameHeight" to frameHeight,
                        "encoder" to encoderImplementation,
                        "qualityLimit" to qualityLimitationReason,
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
     * Disconnect from LiveKit. LiveKit Android treats Room.disconnect() as a
     * terminal teardown: the local participant and published local tracks are
     * disposed, so callers must use a fresh Room/track for a later reconnect.
     */
    fun disconnect() {
        val currentRoom = room
        val wasOwned = ownsRoom
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
            } catch (e: Exception) {
                logger.warn("LiveKit room disconnect threw", mapOf("error" to e.message))
            }
            if (wasOwned) {
                // Owned Rooms are throwaway, no further reference will be exposed.
                logger.debug("Disposed channel-owned LiveKit Room")
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
        logger.debug(
            "LiveKit room event",
            mapOf("type" to event::class.java.simpleName),
        )
        when (event) {
            is RoomEvent.Connected -> {
                logger.info(
                    "LiveKit room connected",
                    mapOf(
                        "remoteParticipants" to room.remoteParticipants.size,
                        "identities" to room.remoteParticipants.values
                            .map { it.identity?.value }
                            .toString(),
                    ),
                )
                _connectionStateUpdates.tryEmit(ConnectionState.CONNECTED)
            }
            is RoomEvent.Reconnecting -> _connectionStateUpdates.tryEmit(ConnectionState.RECONNECTING)
            is RoomEvent.Reconnected -> _connectionStateUpdates.tryEmit(ConnectionState.CONNECTED)
            is RoomEvent.Disconnected -> {
                logger.info(
                    "LiveKit room disconnected",
                    mapOf(
                        "reason" to event.reason.name,
                        "error" to event.error?.message,
                    ),
                )
                _connectionStateUpdates.tryEmit(ConnectionState.DISCONNECTED)
                _disconnectUpdates.tryEmit(DisconnectInfo(event.error?.message ?: event.reason.name))
            }
            is RoomEvent.ParticipantConnected -> {
                logger.info(
                    "LiveKit participant connected",
                    mapOf(
                        "identity" to event.participant.identity?.value,
                        "trackPubs" to event.participant.trackPublications.size,
                    ),
                )
                emitExistingRemoteTracks(room)
            }
            is RoomEvent.TrackSubscribed -> {
                logger.info(
                    "LiveKit track subscribed",
                    mapOf(
                        "identity" to event.participant.identity?.value,
                        "trackType" to event.track::class.java.simpleName,
                        "trackId" to event.track.sid?.toString(),
                    ),
                )
                handleRemoteTrack(event.participant, event.track)
            }
            is RoomEvent.TrackPublished -> {
                logger.info(
                    "LiveKit track published",
                    mapOf(
                        "identity" to event.participant.identity?.value,
                        "publicationType" to event.publication::class.java.simpleName,
                        "trackType" to event.publication.track?.let { it::class.java.simpleName },
                        "subscribed" to (event.publication as? RemoteTrackPublication)?.subscribed,
                    ),
                )
                (event.publication as? RemoteTrackPublication)?.setSubscribed(true)
                emitExistingRemoteTracks(room)
            }
            is RoomEvent.TrackSubscriptionFailed -> {
                logger.error(
                    "LiveKit track subscription failed",
                    mapOf(
                        "identity" to event.participant.identity?.value,
                        "sid" to event.sid,
                        "error" to event.exception.message,
                    ),
                )
            }
            is RoomEvent.FailedToConnect -> {
                logger.error(
                    "LiveKit failed to connect",
                    mapOf("error" to event.error.message),
                )
            }
            else -> Unit
        }
    }

    private fun emitExistingRemoteTracks(room: Room) {
        logger.debug(
            "Scanning existing LiveKit remote participants",
            mapOf("participants" to room.remoteParticipants.size),
        )
        room.remoteParticipants.values.forEach { participant ->
            logger.debug(
                "Inspecting LiveKit remote participant",
                mapOf(
                    "identity" to participant.identity?.value,
                    "publications" to participant.trackPublications.size,
                ),
            )
            participant.trackPublications.values.forEach { publication ->
                val remotePub = publication as? RemoteTrackPublication
                logger.debug(
                    "Inspecting LiveKit remote publication",
                    mapOf(
                        "identity" to participant.identity?.value,
                        "kind" to publication.kind.name,
                        "source" to publication.source.name,
                        "subscribed" to remotePub?.subscribed,
                        "hasTrack" to (publication.track != null),
                    ),
                )
                remotePub?.setSubscribed(true)
                publication.track?.let { track -> handleRemoteTrack(participant, track) }
            }
        }
    }

    private fun handleRemoteTrack(participant: RemoteParticipant, track: Track) {
        val identity = participant.identity?.value
        if (!shouldAcceptTrack(identity)) {
            // Use INFO (not DEBUG) so prefix mismatches show up in normal logs.
            logger.info(
                "Ignoring non-inference LiveKit track",
                mapOf(
                    "identity" to identity,
                    "trackType" to track::class.java.simpleName,
                    "expectedPrefix" to INFERENCE_SERVER_PREFIX,
                ),
            )
            return
        }
        logger.info(
            "Accepting inference LiveKit track",
            mapOf("identity" to identity, "trackType" to track::class.java.simpleName),
        )
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
            // Detach off the render thread.
            scope.launch { detachFirstFrameSink() }
        }
        try {
            track.addRenderer(sink)
            firstFrameSink = FirstFrameSinkRef(track, sink)
        } catch (e: Exception) {
            logger.warn("Failed to attach firstFrame sink", mapOf("error" to e.message))
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

    /** Snapshot of a first-frame observation; the public Diagnostics event is built from this. */
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
        private const val STATS_INTERVAL_MS = 3_000L
    }
}
