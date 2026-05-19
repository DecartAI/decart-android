package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.FacingMode
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
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

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
    private var roomEventsJob: Job? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var localAudioTrack: LocalAudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    private val _remoteStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1)
    private val _connectionStateUpdates = MutableSharedFlow<ConnectionState>(replay = 1)
    private val _disconnectUpdates = MutableSharedFlow<DisconnectInfo>(replay = 1)

    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream> = _remoteStreamUpdates
    val connectionStateUpdates: SharedFlow<ConnectionState> = _connectionStateUpdates
    val disconnectUpdates: SharedFlow<DisconnectInfo> = _disconnectUpdates

    suspend fun connect(roomInfo: LiveKitRoomInfoMessage) {
        val nextRoom = ensureRoom()
        remoteVideoTrack = null
        remoteAudioTrack = null
        nextRoom.connect(roomInfo.liveKitUrl, roomInfo.token, connectOptions)
        emitExistingRemoteTracks(nextRoom)
    }

    fun createCameraStream(
        width: Int,
        height: Int,
        facing: FacingMode,
        includeMicrophone: Boolean,
    ): RealtimeMediaStream {
        val participant = ensureRoom().localParticipant
        val videoTrack = participant.createVideoTrack(
            name = "local_video",
            options = videoConfig.captureOptions(width = width, height = height, facing = facing),
        )
        videoTrack.startCapture()
        localVideoTrack = videoTrack

        val audioTrack = if (includeMicrophone) {
            participant.createAudioTrack(name = "local_audio").also { localAudioTrack = it }
        } else {
            null
        }

        return RealtimeMediaStream(
            videoTrack = videoTrack,
            audioTrack = audioTrack,
            id = RealtimeMediaStream.LOCAL_STREAM_ID,
            room = room,
        )
    }

    suspend fun publishLocalTracks(stream: RealtimeMediaStream) {
        val participant = requireNotNull(room?.localParticipant) { "LiveKit room is not connected" }
        (stream.videoTrack as? LocalVideoTrack)?.let { track ->
            val published = participant.publishVideoTrack(track, videoConfig.publishOptions())
            if (!published) {
                track.stopCapture()
                throw IllegalStateException("Failed to publish local video track")
            }
        }
        (stream.audioTrack as? LocalAudioTrack)?.let { track ->
            val published = participant.publishAudioTrack(track)
            if (!published) {
                throw IllegalStateException("Failed to publish local audio track")
            }
        }
    }

    val currentRemoteStream: RealtimeMediaStream
        get() = RealtimeMediaStream(
            videoTrack = remoteVideoTrack,
            audioTrack = remoteAudioTrack,
            id = RealtimeMediaStream.REMOTE_STREAM_ID,
            room = room,
        )

    fun disconnect() {
        val currentRoom = room
        room = null
        cleanupLocalTracks()
        remoteVideoTrack = null
        remoteAudioTrack = null
        currentRoom?.disconnect()
    }

    private fun cleanupLocalTracks() {
        localVideoTrack?.let { track ->
            try { track.stopCapture() } catch (_: Exception) {}
            track.stop()
            track.dispose()
        }
        localVideoTrack = null
        localAudioTrack?.let { track ->
            track.stop()
            track.dispose()
        }
        localAudioTrack = null
    }

    fun cleanup() {
        scope.cancel()
    }

    private fun listenToRoomEvents(room: Room) {
        if (roomEventsJob != null) return
        roomEventsJob = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> _connectionStateUpdates.tryEmit(ConnectionState.CONNECTED)
                    is RoomEvent.Reconnecting -> _connectionStateUpdates.tryEmit(ConnectionState.RECONNECTING)
                    is RoomEvent.Reconnected -> _connectionStateUpdates.tryEmit(ConnectionState.CONNECTED)
                    is RoomEvent.Disconnected -> {
                        _connectionStateUpdates.tryEmit(ConnectionState.DISCONNECTED)
                        _disconnectUpdates.tryEmit(DisconnectInfo(event.error?.message ?: event.reason.name))
                    }
                    is RoomEvent.TrackSubscribed -> handleTrackSubscribed(event)
                    is RoomEvent.TrackPublished -> emitExistingRemoteTracks(room)
                    else -> Unit
                }
            }
        }
    }

    private fun ensureRoom(): Room {
        val existingRoom = room
        if (existingRoom != null) return existingRoom

        val newRoom = LiveKit.create(
            appContext = context.applicationContext,
            options = roomOptions,
        )
        room = newRoom
        listenToRoomEvents(newRoom)
        return newRoom
    }

    private fun handleTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        handleRemoteTrack(event.participant, event.track)
    }

    private fun emitExistingRemoteTracks(room: Room) {
        room.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach { publication ->
                publication.track?.let { track -> handleRemoteTrack(participant, track) }
            }
        }
    }

    private fun handleRemoteTrack(participant: RemoteParticipant, track: Track) {
        val identity = participant.identity?.value
        if (!shouldAcceptTrack(identity)) {
            logger.debug(
                "Ignoring non-inference LiveKit track",
                mapOf("identity" to identity, "trackType" to track::class.java.simpleName),
            )
            return
        }
        logger.debug(
            "Accepting inference LiveKit track",
            mapOf("identity" to identity, "trackType" to track::class.java.simpleName),
        )
        when (track) {
            is VideoTrack -> {
                remoteVideoTrack = track
                emitRemoteStreamIfAvailable()
            }
            is AudioTrack -> {
                remoteAudioTrack = track
                emitRemoteStreamIfAvailable()
            }
            else -> Unit
        }
    }

    private fun emitRemoteStreamIfAvailable() {
        if (remoteVideoTrack != null) {
            _remoteStreamUpdates.tryEmit(currentRemoteStream)
        }
    }

    private fun shouldAcceptTrack(identity: String?): Boolean =
        identity?.startsWith(INFERENCE_SERVER_PREFIX) == true

    companion object {
        private const val INFERENCE_SERVER_PREFIX = "inference-server-"
    }
}
