package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.RealtimeConfiguration
import ai.decart.sdk.realtime.RealtimeMediaStream
import android.content.Context
import io.livekit.android.LiveKit

/**
 * Builds a [RealtimeMediaStream] that the caller owns. The returned stream's
 * [RealtimeMediaStream.room] is the same LiveKit [io.livekit.android.room.Room]
 * that will later be passed to [LiveKitMediaChannel.connect], so the renderer
 * EGL context, the local capture pipeline and the publish path all share a
 * single Room — mirroring how iOS uses `LocalVideoTrack.createCameraTrack`
 * together with the LiveKit `Room` it later connects with.
 */
internal object LocalStreamFactory {

    fun createCameraStream(
        context: Context,
        configuration: RealtimeConfiguration,
        width: Int,
        height: Int,
        facing: FacingMode = FacingMode.FRONT,
        includeMicrophone: Boolean = false,
        logger: Logger = NoopLogger,
    ): RealtimeMediaStream {
        val room = LiveKit.create(
            appContext = context.applicationContext,
            options = configuration.roomOptions(),
        )
        val participant = room.localParticipant

        val videoTrack = participant.createVideoTrack(
            name = "local_video",
            options = configuration.media.video.captureOptions(
                width = width,
                height = height,
                facing = facing,
            ),
        )
        videoTrack.startCapture()

        val audioTrack = if (includeMicrophone) {
            participant.createAudioTrack(name = "local_audio")
        } else {
            null
        }

        logger.info(
            "Created local camera stream",
            mapOf(
                "width" to width,
                "height" to height,
                "facing" to facing.name,
                "audio" to includeMicrophone,
            ),
        )

        return RealtimeMediaStream(
            videoTrack = videoTrack,
            audioTrack = audioTrack,
            id = RealtimeMediaStream.LOCAL_STREAM_ID,
            room = room,
        )
    }
}
