package ai.decart.sdk.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the JS `pixel-marker` stamp/read unit tests, operating on a luma byte plane. */
class PixelMarkerTest {

    /** A simple in-memory luma plane (row-major, stride == width). */
    private class Luma(val width: Int, val height: Int, fill: Int = 0) {
        val data = IntArray(width * height) { fill }
        val get: (Int, Int) -> Int = { x, y -> data[y * width + x] }
        val set: (Int, Int, Int) -> Unit = { x, y, v -> data[y * width + x] = v }
    }

    /** Uniform nearest-neighbor scale — stands in for WebRTC transport up/downscaling. */
    private fun scaleNearest(src: Luma, factor: Double): Luma {
        val width = Math.round(src.width * factor).toInt()
        val height = Math.round(src.height * factor).toInt()
        val dst = Luma(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = minOf(src.width - 1, Math.floor(x / factor).toInt())
                val sy = minOf(src.height - 1, Math.floor(y / factor).toInt())
                dst.data[y * width + x] = src.data[sy * src.width + sx]
            }
        }
        return dst
    }

    private fun stamp(img: Luma, seq: Int): Boolean = PixelMarker.stamp(img.width, img.height, seq, img.set)

    private fun read(img: Luma): Int? = PixelMarker.read(img.width, img.height, img.get)

    @Test
    fun `round-trips a sweep of seqs at native resolution`() {
        for (seq in listOf(0, 1, 2, 42, 255, 256, 1000, 0x1234, 0x7fff, 0xabcd, 0xffff)) {
            val img = Luma(256, 256)
            assertTrue(stamp(img, seq))
            assertEquals(seq, read(img))
        }
    }

    @Test
    fun `masks seq to 16 bits`() {
        val img = Luma(256, 256)
        stamp(img, 70_000) // 70000 & 0xffff == 4464
        assertEquals(70_000 and 0xffff, read(img))
    }

    @Test
    fun `no-ops and refuses to read on a frame too small for the marker`() {
        val tiny = Luma(PixelMarker.MIN_MARKER_WIDTH - 1, PixelMarker.MIN_MARKER_HEIGHT - 1)
        assertFalse(stamp(tiny, 5))
        assertNull(read(tiny))
    }

    @Test
    fun `recovers the seq after the frame is downscaled (block-size auto-detect)`() {
        val img = Luma(256, 256)
        stamp(img, 0x2bcd)
        assertEquals(0x2bcd, read(scaleNearest(img, 0.5))) // block 8 -> 4
    }

    @Test
    fun `recovers the seq after the frame is upscaled`() {
        val img = Luma(256, 256)
        stamp(img, 0x0777)
        assertEquals(0x0777, read(scaleNearest(img, 2.0))) // block 8 -> 16
    }

    @Test
    fun `returns null on an unstamped frame (no false positive)`() {
        assertNull(read(Luma(256, 256, 0)))
        assertNull(read(Luma(256, 256, 128)))
        assertNull(read(Luma(256, 256, 255)))
    }

    @Test
    fun `rejects a corrupted marker via the checksum`() {
        val img = Luma(256, 256)
        stamp(img, 0x1234)
        // Flip a single data column (the MSB) across every redundant row: majority
        // vote decodes a seq one bit off, so its checksum no longer matches.
        val logCol = 4 // first data bit
        for (logRow in 0 until 4) {
            val row = img.height - (4 - logRow) * 8 + 4
            val flipped = if (img.get(logCol * 8 + 4, row) >= 128) 50 else 200
            for (bx in 0 until 8) img.set(logCol * 8 + bx, row, flipped)
        }
        assertNull(read(img))
    }

    @Test
    fun `survives a single corrupted redundant row via majority vote`() {
        val img = Luma(256, 256)
        stamp(img, 0x5a5a)
        // Destroy only the topmost redundant row (logRow 0); the other 3 still vote.
        val row = img.height - 4 * 8 + 4
        for (x in 0 until img.width) img.set(x, row, 123)
        assertEquals(0x5a5a, read(img))
    }
}
