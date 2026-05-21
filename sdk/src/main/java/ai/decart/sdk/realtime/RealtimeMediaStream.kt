package ai.decart.sdk.realtime

import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.VideoTrack

/**
 * Local or remote media stream. For caller-owned streams [room] is the
 * LiveKit Room that owns the tracks and is reused for the session connect;
 * call [dispose] when done.
 */
data class RealtimeMediaStream(
    val videoTrack: VideoTrack? = null,
    val audioTrack: AudioTrack? = null,
    val id: String,
    val room: Room? = null,
) {
    /** Safe to call multiple times; best-effort on each underlying resource. */
    fun dispose() {
        (videoTrack as? LocalVideoTrack)?.let { track ->
            try { track.stopCapture() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
            try { track.dispose() } catch (_: Exception) {}
        }
        (audioTrack as? LocalAudioTrack)?.let { track ->
            try { track.stop() } catch (_: Exception) {}
            try { track.dispose() } catch (_: Exception) {}
        }
        try { room?.release() } catch (_: Exception) {}
    }

    companion object {
        const val LOCAL_STREAM_ID = "stream-local"
        const val REMOTE_STREAM_ID = "stream-remote"
    }
}
