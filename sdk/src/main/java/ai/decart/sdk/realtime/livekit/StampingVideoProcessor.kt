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
import livekit.org.webrtc.YuvHelper
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rotate an I420 buffer so the image is upright (consumes the frame's rotation).
 * Camera buffers arrive in sensor orientation with rotation metadata; the marker
 * protocol operates in *display* space, so stamping/reading must happen on the
 * upright image. Uses libyuv via [YuvHelper.I420Rotate] (single packed dst).
 */
internal fun rotateI420ToUpright(src: VideoFrame.I420Buffer, rotation: Int): VideoFrame.I420Buffer {
    val rw = if (rotation % 180 == 0) src.width else src.height
    val rh = if (rotation % 180 == 0) src.height else src.width
    val cw = (rw + 1) / 2
    val ch = (rh + 1) / 2
    val ySize = rw * rh
    val cSize = cw * ch
    // Packed layout YuvHelper writes: Y, then U, then V — strides rw / cw / cw.
    val packed = ByteBuffer.allocateDirect(ySize + 2 * cSize)
    YuvHelper.I420Rotate(
        src.dataY, src.strideY,
        src.dataU, src.strideU,
        src.dataV, src.strideV,
        packed, src.width, src.height,
        rotation,
    )
    packed.position(0)
    packed.limit(ySize)
    val y = packed.slice()
    packed.position(ySize)
    packed.limit(ySize + cSize)
    val u = packed.slice()
    packed.position(ySize + cSize)
    packed.limit(ySize + 2 * cSize)
    val v = packed.slice()
    return JavaI420Buffer.wrap(rw, rh, y, rw, u, cw, v, cw, null)
}

/**
 * Outgoing-frame processor for the opt-in glass-to-glass measurement. Uprights
 * each frame (consuming camera rotation), optionally mirrors (front camera) and,
 * when a [SeqTracker] is set, stamps a monotonic sequence marker into the
 * bottom-left of the **display-oriented** luma (Y) plane — the same place the
 * server's `pixel_latency` mode reads it. Verified against the live server:
 * rotation-0 sources round-trip; rotated camera buffers must be uprighted first
 * or the server never finds the marker.
 *
 * Stamping needs CPU pixel access, so frames always go through an I420
 * copy/rotate here (the texture fast-path is skipped). Used only when
 * `debugQuality` is on, so the normal mirror/no-processor paths are unaffected.
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
            // Upright first: the marker must sit at the *display* bottom-left,
            // and after uprighting a display-horizontal mirror is simply a
            // buffer-horizontal flip.
            val rotation = ((frame.rotation % 360) + 360) % 360
            val upright = if (rotation == 0) copyI420(i420) else rotateI420ToUpright(i420, rotation)
            val base: VideoFrame.I420Buffer = if (mirror) {
                val flipped = flipI420Horizontal(upright)
                upright.release()
                flipped
            } else {
                upright
            }
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
            // Rotation was consumed by the upright step.
            VideoFrame(base, 0, frame.timestampNs)
        } finally {
            i420.release()
        }
    }.getOrNull()

    private companion object {
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
    }
}
