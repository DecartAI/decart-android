package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.MirrorMode
import ai.decart.sdk.realtime.RealtimeConfiguration
import ai.decart.sdk.realtime.RealtimeMediaStream
import ai.decart.sdk.realtime.shouldMirror
import android.content.Context
import io.livekit.android.LiveKit

/**
 * The returned stream's LiveKit Room is the one later passed to
 * [LiveKitMediaChannel.connect], so renderer EGL, capture and publish all
 * share one Room.
 */
internal object LocalStreamFactory {

    fun createCameraStream(
        context: Context,
        configuration: RealtimeConfiguration,
        width: Int,
        height: Int,
        facing: FacingMode = FacingMode.FRONT,
        logger: Logger = NoopLogger,
        mirror: MirrorMode = MirrorMode.AUTO,
    ): RealtimeMediaStream {
        val room = LiveKit.create(
            appContext = context.applicationContext,
            options = configuration.roomOptions(),
        )
        val participant = room.localParticipant
        val processor = if (mirror.shouldMirror(facing)) {
            LiveKitMirrorVideoProcessor(logger)
        } else {
            null
        }

        val videoTrack = participant.createVideoTrack(
            name = "local_video",
            options = configuration.media.video.captureOptions(
                width = width,
                height = height,
                facing = facing,
            ),
            videoProcessor = processor,
        )
        videoTrack.startCapture()

        return RealtimeMediaStream(
            videoTrack = videoTrack,
            id = RealtimeMediaStream.LOCAL_STREAM_ID,
            room = room,
        )
    }
}
