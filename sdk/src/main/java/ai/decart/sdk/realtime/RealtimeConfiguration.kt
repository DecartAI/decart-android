package ai.decart.sdk.realtime

import io.livekit.android.ConnectOptions as LiveKitConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec
import io.livekit.android.room.track.VideoEncoding

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
        val maxBitrate: Int = 3_500_000,
        val maxFramerate: Int = 30,
        val preferredCodec: String = VideoCodec.H264.codecName,
        val simulcast: Boolean = true,
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
                videoCodec = preferredCodec,
                source = Track.Source.CAMERA,
            )
    }

    internal fun roomOptions(): RoomOptions =
        RoomOptions(
            adaptiveStream = false,
            dynacast = false,
        )
}
