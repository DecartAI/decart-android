package ai.decart.sdk.realtime

/**
 * Android port of the server's E2E pixel-latency marker protocol
 * (`inference_server/rt/bench/pixel_marker.py`). Used to measure true
 * glass-to-glass latency: the client stamps a monotonic sequence number into the
 * bottom-left of every outgoing frame, the server (with `pixel_latency` enabled)
 * reads it on input and re-stamps it onto the matching output frame, and the
 * client reads it back on the rendered frame.
 *
 * The protocol works on the **luma (Y) channel**. The server writes Y∈{50,200}.
 * On Android the I420 Y plane *is* luma, so [stamp] writes those values directly
 * and [read] thresholds at 128 — no RGB→YUV round-trip (the JS port stamps
 * grayscale RGB so the encoder lands Y≈v; here we stamp Y directly). The SYNC
 * pattern, per-row checksum, 4 redundant rows, and block-size auto-detect
 * together survive VP8/VP9 quantization and WebRTC transport scaling.
 *
 * Intentionally a line-for-line port of `pixel_marker.py` / the JS `pixel-marker.ts`
 * so all three stay byte-compatible — keep the constants and bit layout in sync.
 * Pure: [stamp]/[read] take luma get/set accessors so the same code serves both
 * unit tests (in-memory plane) and live frames (I420 Y-plane ByteBuffer).
 */
internal object PixelMarker {
    private val SYNC = intArrayOf(200, 50, 200, 50)
    private const val SYNC_LEN = 4
    private const val DATA_BITS = 16
    private const val CHECKSUM_BITS = 4

    /** 4 sync + 16 data + 4 checksum logical columns. */
    private const val TOTAL_LOGICAL = SYNC_LEN + DATA_BITS + CHECKSUM_BITS

    /** Redundant logical rows, majority-voted on read. */
    private const val MARKER_ROWS = 4

    /** Physical pixels per logical pixel when stamping (native resolution). */
    private const val BLOCK_SIZE = 8

    /** Received block sizes to try, ordered by likelihood (transport may scale the frame). Mirrors server `_CANDIDATE_BLOCK_SIZES`. */
    private val CANDIDATE_BLOCK_SIZES = intArrayOf(8, 4, 6, 2, 12, 10, 16, 5, 7, 14, 3)

    /** Smallest frame that can hold the marker at nominal block size. */
    const val MIN_MARKER_WIDTH = TOTAL_LOGICAL * BLOCK_SIZE

    const val MIN_MARKER_HEIGHT = MARKER_ROWS * BLOCK_SIZE

    /** Tallest the marker can be in a received frame (largest auto-detect block size). */
    val MAX_MARKER_HEIGHT = MARKER_ROWS * (CANDIDATE_BLOCK_SIZES.maxOrNull() ?: BLOCK_SIZE)

    private fun isHigh(v: Int): Boolean = v >= 128

    /** XOR of the four 4-bit nibbles of the 16-bit seq (matches the server). */
    private fun checksumNibbles(seq: Int): Int {
        var checksum = 0
        var i = 0
        while (i < DATA_BITS) {
            checksum = checksum xor ((seq shr i) and 0xf)
            i += 4
        }
        return checksum
    }

    /** The TOTAL_LOGICAL luma values for one logical row encoding `seq`. */
    private fun rowValues(seq: Int): IntArray {
        val masked = seq and 0xffff
        val values = IntArray(TOTAL_LOGICAL)
        for (i in 0 until SYNC_LEN) values[i] = SYNC[i]
        for (i in 0 until DATA_BITS) {
            values[SYNC_LEN + i] = if (((masked shr (DATA_BITS - 1 - i)) and 1) == 1) 200 else 50
        }
        val checksum = checksumNibbles(masked)
        for (i in 0 until CHECKSUM_BITS) {
            values[SYNC_LEN + DATA_BITS + i] = if (((checksum shr (CHECKSUM_BITS - 1 - i)) and 1) == 1) 200 else 50
        }
        return values
    }

    /**
     * Stamp `seq` into the bottom-left of a luma plane via `set(x, y, value)`.
     * Returns false (no-op) if the frame is too small. Always stamps at
     * BLOCK_SIZE=8, matching the server's native-resolution stamp.
     */
    fun stamp(width: Int, height: Int, seq: Int, set: (x: Int, y: Int, value: Int) -> Unit): Boolean {
        if (width < MIN_MARKER_WIDTH || height < MIN_MARKER_HEIGHT) return false
        val values = rowValues(seq)
        for (logRow in 0 until MARKER_ROWS) {
            val rowStart = height - (MARKER_ROWS - logRow) * BLOCK_SIZE
            for (by in 0 until BLOCK_SIZE) {
                val y = rowStart + by
                if (y < 0 || y >= height) continue
                for (logCol in 0 until TOTAL_LOGICAL) {
                    val v = values[logCol]
                    val xStart = logCol * BLOCK_SIZE
                    val xEnd = minOf(xStart + BLOCK_SIZE, width)
                    for (x in xStart until xEnd) set(x, y, v)
                }
            }
        }
        return true
    }

    private fun syncMatches(rv: IntArray): Boolean {
        for (i in 0 until SYNC_LEN) if (isHigh(SYNC[i]) != isHigh(rv[i])) return false
        return true
    }

    /**
     * Read the marker seq from the bottom of a luma plane via `get(x, y)` (0..255),
     * or null if absent/unreadable. Auto-detects the received block size so it works
     * at any received resolution (the transport may uniformly scale the frame).
     */
    fun read(width: Int, height: Int, get: (x: Int, y: Int) -> Int): Int? {
        for (blockSize in CANDIDATE_BLOCK_SIZES) {
            if (width < TOTAL_LOGICAL * blockSize || height < MARKER_ROWS * blockSize) continue
            val seq = decodeAtBlockSize(width, height, blockSize, get)
            if (seq != null) return seq
        }
        return null
    }

    private fun decodeAtBlockSize(
        width: Int,
        height: Int,
        blockSize: Int,
        get: (x: Int, y: Int) -> Int,
    ): Int? {
        val half = blockSize shr 1
        val validRows = ArrayList<IntArray>(MARKER_ROWS)

        for (logRow in 0 until MARKER_ROWS) {
            val row = (height - (MARKER_ROWS - logRow) * blockSize + half).coerceIn(0, height - 1)
            val rv = IntArray(TOTAL_LOGICAL)
            for (logCol in 0 until TOTAL_LOGICAL) {
                val col = (logCol * blockSize + half).coerceIn(0, width - 1)
                rv[logCol] = get(col, row)
            }
            if (syncMatches(rv)) validRows.add(rv)
        }

        if (validRows.isEmpty()) return null
        val threshold = validRows.size / 2.0

        var seq = 0
        for (i in 0 until DATA_BITS) {
            var votes = 0
            for (rv in validRows) if (isHigh(rv[SYNC_LEN + i])) votes++
            if (votes > threshold) seq = seq or (1 shl (DATA_BITS - 1 - i))
        }

        val expectedChecksum = checksumNibbles(seq)
        var actualChecksum = 0
        for (i in 0 until CHECKSUM_BITS) {
            var votes = 0
            for (rv in validRows) if (isHigh(rv[SYNC_LEN + DATA_BITS + i])) votes++
            if (votes > threshold) actualChecksum = actualChecksum or (1 shl (CHECKSUM_BITS - 1 - i))
        }

        return if (expectedChecksum == actualChecksum) seq else null
    }
}
