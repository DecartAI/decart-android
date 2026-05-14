package ai.decart.sdk.realtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests for [MirrorVideoProcessor]. We can only cover the pure-JVM pieces here
 * — the full `onFrameCaptured` path constructs `org.webrtc.VideoFrame` and allocates
 * `JavaI420Buffer`s, both of which require WebRTC native libs. Those paths are
 * exercised via the sample app instead (see plan: "Verification").
 */
class MirrorVideoProcessorTest {

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Test
    fun `onCapturerStarted and onCapturerStopped are no-ops`() {
        val processor = MirrorVideoProcessor()
        processor.onCapturerStarted(true)
        processor.onCapturerStarted(false)
        processor.onCapturerStopped()
        // No assertion — just shouldn't throw.
    }

    @Test
    fun `setSink null then unset then null is safe`() {
        val processor = MirrorVideoProcessor()
        processor.setSink(null)
        processor.setSink { /* no-op */ }
        processor.setSink(null)
    }

    // ── Row-flip correctness ────────────────────────────────────────────

    @Test
    fun `flipPlaneHorizontal reverses each row`() {
        val width = 6
        val height = 3
        val src = ramp(width, height, stride = width)
        val dst = ByteBuffer.allocate(width * height)

        MirrorVideoProcessor.flipPlaneHorizontal(src, width, dst, width, width, height)

        for (row in 0 until height) {
            val srcRow = readRow(src, width, row, width)
            val dstRow = readRow(dst, width, row, width)
            assertEquals("row $row", srcRow.reversed(), dstRow)
        }
    }

    @Test
    fun `isPerpendicularRotation distinguishes 0,180 from 90,270 including negatives`() {
        assertEquals(false, MirrorVideoProcessor.isPerpendicularRotation(0))
        assertEquals(true, MirrorVideoProcessor.isPerpendicularRotation(90))
        assertEquals(false, MirrorVideoProcessor.isPerpendicularRotation(180))
        assertEquals(true, MirrorVideoProcessor.isPerpendicularRotation(270))
        assertEquals(false, MirrorVideoProcessor.isPerpendicularRotation(360))
        assertEquals(true, MirrorVideoProcessor.isPerpendicularRotation(-90))
        assertEquals(false, MirrorVideoProcessor.isPerpendicularRotation(-180))
    }

    @Test
    fun `flipPlaneHorizontal leaves padding bytes untouched`() {
        val width = 4
        val height = 3
        val dstStride = 8
        val src = ramp(width, height, stride = width)
        val dst = java.nio.ByteBuffer.allocate(dstStride * height)
        // Pre-fill with a sentinel so we can detect any spurious writes to padding.
        for (i in 0 until dst.capacity()) dst.put(i, 0xAA.toByte())

        MirrorVideoProcessor.flipPlaneHorizontal(src, width, dst, dstStride, width, height)

        for (row in 0 until height) {
            for (col in width until dstStride) {
                assertEquals(
                    "row $row, col $col should still be sentinel",
                    0xAA.toByte(),
                    dst.get(row * dstStride + col),
                )
            }
        }
    }

    @Test
    fun `flipPlaneHorizontal respects strides larger than width`() {
        val width = 4
        val height = 2
        val srcStride = 8
        val dstStride = 10
        val src = ramp(width, height, srcStride)
        val dst = ByteBuffer.allocate(dstStride * height)

        MirrorVideoProcessor.flipPlaneHorizontal(src, srcStride, dst, dstStride, width, height)

        for (row in 0 until height) {
            val srcRow = readRow(src, srcStride, row, width)
            val dstRow = readRow(dst, dstStride, row, width)
            assertEquals("row $row", srcRow.reversed(), dstRow)
        }
    }

    @Test
    fun `flipPlaneHorizontal is its own inverse`() {
        val width = 5
        val height = 3
        val src = ramp(width, height, width)
        val once = ByteBuffer.allocate(width * height)
        val twice = ByteBuffer.allocate(width * height)

        MirrorVideoProcessor.flipPlaneHorizontal(src, width, once, width, width, height)
        MirrorVideoProcessor.flipPlaneHorizontal(once, width, twice, width, width, height)

        for (row in 0 until height) {
            assertEquals(
                "row $row",
                readRow(src, width, row, width),
                readRow(twice, width, row, width),
            )
        }
    }

    @Test
    fun `flipPlaneVertical reverses row order`() {
        val width = 4
        val height = 5
        val src = ramp(width, height, width)
        val dst = java.nio.ByteBuffer.allocate(width * height)

        MirrorVideoProcessor.flipPlaneVertical(src, width, dst, width, width, height)

        for (row in 0 until height) {
            assertEquals(
                "row $row",
                readRow(src, width, row, width),
                readRow(dst, width, height - 1 - row, width),
            )
        }
    }

    @Test
    fun `flipPlaneVertical is its own inverse`() {
        val width = 3
        val height = 6
        val src = ramp(width, height, width)
        val once = java.nio.ByteBuffer.allocate(width * height)
        val twice = java.nio.ByteBuffer.allocate(width * height)

        MirrorVideoProcessor.flipPlaneVertical(src, width, once, width, width, height)
        MirrorVideoProcessor.flipPlaneVertical(once, width, twice, width, width, height)

        for (row in 0 until height) {
            assertEquals(
                "row $row",
                readRow(src, width, row, width),
                readRow(twice, width, row, width),
            )
        }
    }

    @Test
    fun `flipPlaneHorizontal handles odd-width rows`() {
        val width = 7
        val height = 2
        val src = ramp(width, height, width)
        val dst = ByteBuffer.allocate(width * height)

        MirrorVideoProcessor.flipPlaneHorizontal(src, width, dst, width, width, height)

        for (row in 0 until height) {
            val srcRow = readRow(src, width, row, width)
            val dstRow = readRow(dst, width, row, width)
            assertEquals("row $row", srcRow.reversed(), dstRow)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Fills each row of a `stride`-pitch buffer with the ramp `[0, 1, 2, ..., width-1]`. */
    private fun ramp(width: Int, height: Int, stride: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(stride * height)
        for (row in 0 until height) {
            buf.position(row * stride)
            for (col in 0 until width) buf.put(col.toByte())
        }
        return buf
    }

    private fun readRow(buf: ByteBuffer, stride: Int, row: Int, width: Int): List<Byte> {
        val view = buf.duplicate()
        view.position(row * stride)
        val out = ByteArray(width)
        view.get(out)
        return out.toList()
    }
}
