package ai.decart.sdk.realtime.livekit

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import livekit.org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A CPU-generated animated video source used by the deep connectivity probe — no
 * camera permission needed; content is irrelevant to the pixel marker (the
 * [StampingVideoProcessor] stamps it). Produces I420 frames at the requested
 * size/fps so the encoder emits real frames at the target rate (content is
 * animated so it isn't deduplicated).
 */
internal class SyntheticVideoCapturer(
    private var width: Int,
    private var height: Int,
    private var fps: Int,
) : VideoCapturer {

    private var observer: CapturerObserver? = null
    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var frameCount = 0L

    override fun initialize(helper: SurfaceTextureHelper?, context: Context?, observer: CapturerObserver?) {
        this.observer = observer
    }

    override fun startCapture(width: Int, height: Int, frameRate: Int) {
        // Honor the dimensions/rate LiveKit requests (fall back to the constructor values).
        if (width > 0) this.width = width
        if (height > 0) this.height = height
        if (frameRate > 0) this.fps = frameRate
        if (!running.compareAndSet(false, true)) return
        val t = HandlerThread("decart-synthetic-capturer").apply { start() }
        thread = t
        handler = Handler(t.looper)
        observer?.onCapturerStarted(true)
        scheduleNext()
    }

    private fun scheduleNext() {
        val h = handler ?: return
        h.postDelayed({
            if (running.get()) {
                emitFrame()
                scheduleNext()
            }
        }, 1000L / fps)
    }

    private fun emitFrame() {
        val buffer = JavaI420Buffer.allocate(width, height)
        // Animate luma so the encoder produces fresh frames; chroma stays neutral.
        val luma = ((frameCount * 4) % 256).toByte()
        fillPlane(buffer.dataY, buffer.strideY, width, height, luma)
        fillPlane(buffer.dataU, buffer.strideU, (width + 1) / 2, (height + 1) / 2, 128.toByte())
        fillPlane(buffer.dataV, buffer.strideV, (width + 1) / 2, (height + 1) / 2, 128.toByte())
        frameCount++
        val frame = VideoFrame(buffer, 0, System.nanoTime())
        try {
            observer?.onFrameCaptured(frame)
        } finally {
            frame.release()
        }
    }

    override fun stopCapture() {
        if (!running.compareAndSet(true, false)) return
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, frameRate: Int) {}

    override fun dispose() {
        stopCapture()
        observer = null
    }

    override fun isScreencast(): Boolean = false

    private companion object {
        fun fillPlane(buffer: java.nio.ByteBuffer, stride: Int, width: Int, height: Int, value: Byte) {
            val view = buffer.duplicate()
            val row = ByteArray(width) { value }
            for (y in 0 until height) {
                view.position(y * stride)
                view.put(row, 0, width)
            }
        }
    }
}
