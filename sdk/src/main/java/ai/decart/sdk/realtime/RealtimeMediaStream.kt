package ai.decart.sdk.realtime

import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack

data class RealtimeMediaStream(
    val videoTrack: VideoTrack? = null,
    val audioTrack: AudioTrack? = null,
    val id: String,
    val room: Room? = null,
) {
    companion object {
        const val LOCAL_STREAM_ID = "stream-local"
        const val REMOTE_STREAM_ID = "stream-remote"
    }
}
