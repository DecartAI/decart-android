package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.MirrorMode
import ai.decart.sdk.realtime.RealtimeConfiguration
import ai.decart.sdk.realtime.RealtimeMediaStream
import ai.decart.sdk.realtime.SeqTracker
import ai.decart.sdk.realtime.shouldMirror
import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter

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
        debugQuality: Boolean = false,
    ): RealtimeMediaStream {
        val room = LiveKit.create(
            appContext = context.applicationContext,
            options = configuration.roomOptions(),
        )
        val participant = room.localParticipant
        val shouldMirror = mirror.shouldMirror(facing)
        // Glass-to-glass measurement stamps each outgoing frame; its tracker rides
        // on the stream so the media channel can wire the marker reader + snapshot.
        val tracker = if (debugQuality) SeqTracker() else null
        val processor = when {
            debugQuality -> StampingVideoProcessor(mirror = shouldMirror, tracker = tracker, logger = logger)
            shouldMirror -> LiveKitMirrorVideoProcessor(logger)
            else -> null
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
        ).also { it.seqTracker = tracker }
    }

    /**
     * Build a stream backed by a [SyntheticVideoCapturer] (no camera) with the
     * glass-to-glass stamp pipeline always on — used by the deep connectivity
     * probe. The caller owns the returned stream's Room and must [RealtimeMediaStream.dispose] it.
     */
    fun createSyntheticStream(
        context: Context,
        configuration: RealtimeConfiguration,
        width: Int,
        height: Int,
        fps: Int,
        logger: Logger = NoopLogger,
    ): RealtimeMediaStream {
        val room = LiveKit.create(
            appContext = context.applicationContext,
            options = configuration.roomOptions(),
        )
        val tracker = SeqTracker()
        val processor = StampingVideoProcessor(mirror = false, tracker = tracker, logger = logger)
        val capturer = SyntheticVideoCapturer(width, height, fps)
        val videoTrack = room.localParticipant.createVideoTrack(
            name = "synthetic_video",
            capturer = capturer,
            options = LocalVideoTrackOptions(captureParams = VideoCaptureParameter(width, height, fps)),
            videoProcessor = processor,
        )
        videoTrack.startCapture()

        return RealtimeMediaStream(
            videoTrack = videoTrack,
            id = RealtimeMediaStream.LOCAL_STREAM_ID,
            room = room,
        ).also { it.seqTracker = tracker }
    }
}
