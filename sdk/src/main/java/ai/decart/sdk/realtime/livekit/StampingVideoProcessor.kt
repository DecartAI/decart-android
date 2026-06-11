package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.PixelMarker
import ai.decart.sdk.realtime.SeqTracker
import ai.decart.sdk.realtime.monotonicMs
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outgoing-frame processor for the opt-in glass-to-glass measurement. Optionally
 * mirrors (front camera) and, when a [SeqTracker] is set, stamps a monotonic
 * sequence marker into the bottom-left of each frame's luma (Y) plane — the same
 * marker the server re-stamps onto its output so the client can read true
 * camera→display latency back off the rendered frames.
 *
 * Stamping needs CPU pixel access, so frames always go through an I420 copy here
 * (the texture fast-path is skipped). Used only when `debugQuality` is on, so the
 * normal mirror/no-processor paths are unaffected. The marker is stamped in raw
 * I420 **buffer space** (bottom-left of the sensor buffer, pre-rotation) — see the
 * SDK notes; verify placement on-device against the server's `pixel_latency` reader.
 */
internal class StampingVideoProcessor(
    private val mirror: Boolean,
    private val tracker: SeqTracker?,
    private val logger: Logger = NoopLogger,
) : VideoProcessor {

    @Volatile
    private var sink: VideoSink? = null
    private val warnedOnce = AtomicBoolean(false)
    private val warnedTooSmall = AtomicBoolean(false)

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val target = sink ?: return
        val out = transform(frame)
        if (out == null) {
            if (warnedOnce.compareAndSet(false, true)) {
                logger.warn(
                    "StampingVideoProcessor: frame transform failed; forwarding original frames " +
                        "until the next success. Glass-to-glass latency may not populate.",
                    null,
                )
            }
            target.onFrame(frame)
            return
        }
        try {
            target.onFrame(out)
        } finally {
            out.release()
        }
    }

    private fun transform(frame: VideoFrame): VideoFrame? = runCatching {
        val i420 = frame.buffer.toI420() ?: return@runCatching null
        try {
            val verticalAxis = isPerpendicularRotation(frame.rotation)
            val base: VideoFrame.I420Buffer = when {
                mirror && verticalAxis -> flipI420Vertical(i420)
                mirror -> flipI420Horizontal(i420)
                else -> copyI420(i420)
            }
            // Stamp the marker into the bottom-left of the (post-mirror) Y plane so
            // the server reads it from the frame it actually receives.
            tracker?.let { t ->
                val seq = t.stampNext(monotonicMs())
                val dataY = base.dataY
                val strideY = base.strideY
                val stamped = PixelMarker.stamp(base.width, base.height, seq) { x, y, v ->
                    dataY.put(y * strideY + x, v.toByte())
                }
                if (!stamped && warnedTooSmall.compareAndSet(false, true)) {
                    logger.warn(
                        "StampingVideoProcessor: frame ${base.width}x${base.height} is too small for the " +
                            "glass-to-glass marker (needs ≥ ${PixelMarker.MIN_MARKER_WIDTH}x" +
                            "${PixelMarker.MIN_MARKER_HEIGHT}); latency won't be measured",
                        null,
                    )
                }
            }
            VideoFrame(base, frame.rotation, frame.timestampNs)
        } finally {
            i420.release()
        }
    }.getOrNull()

    private companion object {
        fun isPerpendicularRotation(rotationDegrees: Int): Boolean {
            val normalized = ((rotationDegrees % 360) + 360) % 360
            return normalized % 180 != 0
        }

        fun copyI420(src: VideoFrame.I420Buffer): VideoFrame.I420Buffer {
            val width = src.width
            val height = src.height
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val dst = JavaI420Buffer.allocate(width, height)
            copyPlane(src.dataY, src.strideY, dst.dataY, dst.strideY, width, height)
            copyPlane(src.dataU, src.strideU, dst.dataU, dst.strideU, chromaWidth, chromaHeight)
            copyPlane(src.dataV, src.strideV, dst.dataV, dst.strideV, chromaWidth, chromaHeight)
            return dst
        }

        fun copyPlane(src: ByteBuffer, srcStride: Int, dst: ByteBuffer, dstStride: Int, width: Int, height: Int) {
            val srcView = src.duplicate()
            val dstView = dst.duplicate()
            val row = ByteArray(width)
            for (y in 0 until height) {
                srcView.position(y * srcStride)
                srcView.get(row, 0, width)
                dstView.position(y * dstStride)
                dstView.put(row, 0, width)
            }
        }

        fun flipI420Horizontal(src: VideoFrame.I420Buffer): VideoFrame.I420Buffer {
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

        fun flipI420Vertical(src: VideoFrame.I420Buffer): VideoFrame.I420Buffer {
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

        fun flipPlaneHorizontal(src: ByteBuffer, srcStride: Int, dst: ByteBuffer, dstStride: Int, width: Int, height: Int) {
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

        fun flipPlaneVertical(src: ByteBuffer, srcStride: Int, dst: ByteBuffer, dstStride: Int, width: Int, height: Int) {
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
