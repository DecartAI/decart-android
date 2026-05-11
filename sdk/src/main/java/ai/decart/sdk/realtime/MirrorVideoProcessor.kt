package ai.decart.sdk.realtime

import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import android.graphics.Matrix
import org.webrtc.JavaI420Buffer
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [VideoProcessor] that mirrors every captured frame across its displayed
 * horizontal axis before it reaches the [org.webrtc.VideoSource]. Attach it
 * via [org.webrtc.VideoSource.setVideoProcessor] before passing the resulting
 * [org.webrtc.VideoTrack] to [RealTimeClient.connect].
 *
 * The flip axis in *buffer* space depends on `frame.rotation`, since the renderer
 * applies that rotation downstream. For portrait-mode cameras (rotation 90/270),
 * a display-horizontal mirror corresponds to a vertical flip of the unrotated
 * buffer; for 0/180 it's a buffer-horizontal flip.
 *
 * For texture-backed frames (the common case for [org.webrtc.Camera2Enumerator])
 * the flip is a metadata-only matrix composition — no pixel copy, no per-frame
 * GPU work. For non-texture frames the processor falls back to a software flip
 * via [JavaI420Buffer].
 *
 * The processor is stateless and reusable across sources.
 */
class MirrorVideoProcessor(private val logger: Logger = NoopLogger) : VideoProcessor {

    @Volatile
    private var sink: VideoSink? = null
    private val warnedOnce = AtomicBoolean(false)

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val target = sink ?: return
        val mirrored = mirror(frame)
        if (mirrored == null) {
            if (warnedOnce.compareAndSet(false, true)) {
                logger.warn(
                    "MirrorVideoProcessor: failed to mirror a frame; forwarding un-flipped frames " +
                        "until the next successful flip. Server-baked overlays may render reversed.",
                    null,
                )
            }
            target.onFrame(frame)
            return
        }
        try {
            target.onFrame(mirrored)
        } finally {
            mirrored.release()
        }
    }

    private fun mirror(frame: VideoFrame): VideoFrame? {
        val verticalAxis = isPerpendicularRotation(frame.rotation)
        val buffer = frame.buffer
        if (buffer is TextureBufferImpl) {
            val textureFrame = runCatching {
                val matrix = if (verticalAxis) verticalFlipMatrix() else horizontalFlipMatrix()
                val newBuffer = buffer.applyTransformMatrix(matrix, buffer.width, buffer.height)
                VideoFrame(newBuffer, frame.rotation, frame.timestampNs)
            }.getOrNull()
            if (textureFrame != null) return textureFrame
            // Texture matrix failed — fall through to the I420 software path so the
            // mirror invariant is preserved whenever readback is physically possible.
        }
        return runCatching {
            val i420 = buffer.toI420() ?: return@runCatching null
            try {
                val flipped = if (verticalAxis) flipI420Vertical(i420) else flipI420Horizontal(i420)
                VideoFrame(flipped, frame.rotation, frame.timestampNs)
            } finally {
                i420.release()
            }
        }.getOrNull()
    }

    companion object {
        /**
         * Rotation values of 90 and 270 (modulo 360) lay the buffer out perpendicular
         * to the displayed axis, so a display-horizontal mirror is a buffer-vertical flip.
         */
        internal fun isPerpendicularRotation(rotationDegrees: Int): Boolean {
            val normalized = ((rotationDegrees % 360) + 360) % 360
            return normalized % 180 != 0
        }

        private fun horizontalFlipMatrix(): Matrix = Matrix().apply {
            postScale(-1f, 1f)
            postTranslate(1f, 0f)
        }

        private fun verticalFlipMatrix(): Matrix = Matrix().apply {
            postScale(1f, -1f)
            postTranslate(0f, 1f)
        }

        internal fun flipI420Horizontal(src: VideoFrame.I420Buffer): VideoFrame.I420Buffer {
            val width = src.width
            val height = src.height
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val dst = JavaI420Buffer.allocate(width, height)
            flipPlaneHorizontal(src.dataY, src.strideY, dst.dataY, dst.strideY, width, height)
            flipPlaneHorizontal(src.dataU, src.strideU, dst.dataU, dst.strideU, chromaWidth, chromaHeight)
            flipPlaneHorizontal(src.dataV, src.strideV, dst.dataV, dst.strideV, chromaWidth, chromaHeight)
            return dst
        }

        internal fun flipI420Vertical(src: VideoFrame.I420Buffer): VideoFrame.I420Buffer {
            val width = src.width
            val height = src.height
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val dst = JavaI420Buffer.allocate(width, height)
            flipPlaneVertical(src.dataY, src.strideY, dst.dataY, dst.strideY, width, height)
            flipPlaneVertical(src.dataU, src.strideU, dst.dataU, dst.strideU, chromaWidth, chromaHeight)
            flipPlaneVertical(src.dataV, src.strideV, dst.dataV, dst.strideV, chromaWidth, chromaHeight)
            return dst
        }

        internal fun flipPlaneHorizontal(
            src: ByteBuffer,
            srcStride: Int,
            dst: ByteBuffer,
            dstStride: Int,
            width: Int,
            height: Int,
        ) {
            val srcView = src.duplicate()
            val dstView = dst.duplicate()
            val row = ByteArray(width)
            for (y in 0 until height) {
                srcView.position(y * srcStride)
                srcView.get(row, 0, width)
                var i = 0
                var j = width - 1
                while (i < j) {
                    val tmp = row[i]
                    row[i] = row[j]
                    row[j] = tmp
                    i++
                    j--
                }
                dstView.position(y * dstStride)
                dstView.put(row, 0, width)
            }
        }

        internal fun flipPlaneVertical(
            src: ByteBuffer,
            srcStride: Int,
            dst: ByteBuffer,
            dstStride: Int,
            width: Int,
            height: Int,
        ) {
            val srcView = src.duplicate()
            val dstView = dst.duplicate()
            val row = ByteArray(width)
            for (y in 0 until height) {
                srcView.position(y * srcStride)
                srcView.get(row, 0, width)
                dstView.position((height - 1 - y) * dstStride)
                dstView.put(row, 0, width)
            }
        }
    }
}
