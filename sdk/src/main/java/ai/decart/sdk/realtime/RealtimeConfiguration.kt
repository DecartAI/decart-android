package ai.decart.sdk.realtime

import io.livekit.android.ConnectOptions as LiveKitConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.room.participant.BackupVideoCodec
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoPreset
import livekit.org.webrtc.RtpParameters

data class RealtimeConfiguration(
    val connection: ConnectionConfig = ConnectionConfig(),
    val media: MediaConfig = MediaConfig(),
) {
    data class ConnectionConfig(
        val connectionTimeoutMs: Long = 15_000L,
    ) {
        internal fun connectOptions(): LiveKitConnectOptions =
            LiveKitConnectOptions(autoSubscribe = true)
    }

    data class MediaConfig(
        val video: VideoConfig = VideoConfig(),
    )

    data class VideoConfig(
        val maxBitrate: Int = DEFAULT_MAX_BITRATE,
        val maxFramerate: Int = DEFAULT_MAX_FRAMERATE,
        val preferredCodec: String = DEFAULT_PREFERRED_CODEC,
        val backupCodec: String? = DEFAULT_BACKUP_CODEC,
        val simulcast: Boolean = DEFAULT_SIMULCAST,
        val degradationPreference: RtpParameters.DegradationPreference? = DEFAULT_DEGRADATION_PREFERENCE,
        val simulcastLayers: List<VideoPreset>? = null,
    ) {
        internal fun captureOptions(
            width: Int,
            height: Int,
            facing: FacingMode,
        ): LocalVideoTrackOptions =
            LocalVideoTrackOptions(
                position = when (facing) {
                    FacingMode.FRONT -> CameraPosition.FRONT
                    FacingMode.BACK -> CameraPosition.BACK
                },
                captureParams = VideoCaptureParameter(
                    width = width,
                    height = height,
                    maxFps = maxFramerate,
                ),
            )

        internal fun publishOptions(): VideoTrackPublishOptions =
            VideoTrackPublishOptions(
                videoEncoding = VideoEncoding(maxBitrate = maxBitrate, maxFps = maxFramerate),
                simulcast = simulcast,
                videoCodec = resolvedCodec(),
                backupCodec = backupCodec?.let { BackupVideoCodec(codec = it, simulcast = simulcast) },
                source = Track.Source.CAMERA,
                degradationPreference = degradationPreference,
                simulcastLayers = simulcastLayers,
            )

        internal fun resolvedCodec(): String =
            preferredCodec

        companion object {
            const val DEFAULT_MAX_BITRATE: Int = 2_000_000
            const val DEFAULT_MAX_FRAMERATE: Int = 30
            const val DEFAULT_SIMULCAST: Boolean = true
            val DEFAULT_PREFERRED_CODEC: String = VideoCodec.H264.codecName
            val DEFAULT_BACKUP_CODEC: String? = VideoCodec.VP9.codecName
            val DEFAULT_DEGRADATION_PREFERENCE: RtpParameters.DegradationPreference =
                RtpParameters.DegradationPreference.BALANCED
        }
    }

    internal fun roomOptions(): RoomOptions =
        RoomOptions(
            adaptiveStream = false,
            dynacast = false,
        )
}
