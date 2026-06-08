package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.MirrorMode
import ai.decart.sdk.realtime.RealtimeConfiguration
import ai.decart.sdk.realtime.RealtimeMediaStream
import ai.decart.sdk.realtime.shouldMirror
import android.content.Context
import android.content.res.Configuration
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

        // Match capture dimensions to the device's current orientation. The
        // server's realtime inference pipeline eagerly publishes its output
        // video track at the dimensions the input track advertised in its
        // publication metadata (LiveKit TrackInfo populated from the
        // publisher's AddTrackRequest) before any media is decoded; if
        // those advertised dims don't match the actual orientation of the
        // delivered frames, the bridge republishes the output track when
        // the first input frame arrives. The republish (unpublish-old +
        // publish-new on the same participant) triggers a transceiver-reuse
        // race in the LiveKit Android subscriber path where TrackSubscribed
        // for the new track is never delivered to the client — the renderer
        // is never attached and the app shows a black screen until session
        // timeout (~37 % rate on portrait-locked Android sessions in
        // production sampling).
        //
        // RealtimeModel definitions specify the model's natural landscape
        // (W,H); on a portrait device this would force the publication
        // metadata to landscape while CVO rotation carries the frames to
        // portrait. By transposing here we make the publication metadata
        // carry the rotated (i.e. displayed) dims, so the server's
        // eager-publish prediction lands at the correct orientation on the
        // first try and no republish happens.
        val (captureW, captureH) = orientCaptureDims(context, width, height)
        if (captureW != width || captureH != height) {
            logger.info(
                "livekit: oriented capture dims for device",
                mapOf(
                    "requested_width" to width,
                    "requested_height" to height,
                    "capture_width" to captureW,
                    "capture_height" to captureH,
                ),
            )
        }

        val videoTrack = participant.createVideoTrack(
            name = "local_video",
            options = configuration.media.video.captureOptions(
                width = captureW,
                height = captureH,
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

    /**
     * Returns (width, height) oriented to match the device's current
     * orientation: longer side along the device's longer axis. Accepts dims
     * in either orientation and normalizes; callers (and the
     * RealtimeModel defs) can keep passing the model's natural landscape
     * (W,H) and we'll transpose when the device is portrait.
     *   - ORIENTATION_PORTRAIT  → (short, long)  e.g. (624, 1088)
     *   - ORIENTATION_LANDSCAPE → (long, short)  e.g. (1088, 624)
     *   - undefined/square      → pass through unchanged
     */
    private fun orientCaptureDims(context: Context, width: Int, height: Int): Pair<Int, Int> {
        val short = minOf(width, height)
        val long = maxOf(width, height)
        return when (context.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> Pair(short, long)
            Configuration.ORIENTATION_LANDSCAPE -> Pair(long, short)
            else -> Pair(width, height)
        }
    }
}
