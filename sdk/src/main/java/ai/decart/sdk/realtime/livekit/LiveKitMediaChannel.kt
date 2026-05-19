package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.ConnectionState
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
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
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
) {
    data class DisconnectInfo(val reason: String?)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var room: Room? = null
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
        val nextRoom = LiveKit.create(
            appContext = context.applicationContext,
            options = roomOptions,
        )
        room = nextRoom
        remoteVideoTrack = null
        remoteAudioTrack = null
        listenToRoomEvents(nextRoom)
        nextRoom.connect(roomInfo.liveKitUrl, roomInfo.token, connectOptions)
    }

    fun createCameraStream(
        width: Int,
        height: Int,
        facing: FacingMode,
        includeMicrophone: Boolean,
    ): RealtimeMediaStream {
        val participant = requireNotNull(room?.localParticipant) { "LiveKit room is not connected" }
        val videoTrack = participant.createVideoTrack(
            name = "local_video",
            options = videoConfig.captureOptions(width = width, height = height, facing = facing),
        )
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
        )
    }

    suspend fun publishLocalTracks(stream: RealtimeMediaStream) {
        val participant = requireNotNull(room?.localParticipant) { "LiveKit room is not connected" }
        (stream.videoTrack as? LocalVideoTrack)?.let { track ->
            track.startCapture()
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
        )

    fun disconnect() {
        val currentRoom = room
        room = null
        localVideoTrack?.stopCapture()
        localVideoTrack = null
        localAudioTrack = null
        remoteVideoTrack = null
        remoteAudioTrack = null
        currentRoom?.disconnect()
    }

    fun cleanup() {
        scope.cancel()
    }

    private fun listenToRoomEvents(room: Room) {
        scope.launch {
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
                    else -> Unit
                }
            }
        }
    }

    private fun handleTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        if (!shouldAcceptTrack(event.participant.identity?.value)) return
        when (val track = event.track) {
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
