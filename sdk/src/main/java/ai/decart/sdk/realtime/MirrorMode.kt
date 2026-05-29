package ai.decart.sdk.realtime

/**
 * Pre-flip the input video horizontally before sending it. The remote stream
 * can then be rendered as-is — important when the server bakes pixels into
 * the output (e.g. watermarks) that a display-time flip would also reverse.
 */
enum class MirrorMode {
    OFF,
    ON,
    /** Mirror iff [FacingMode] is [FacingMode.FRONT]. */
    AUTO,
}

internal fun MirrorMode.shouldMirror(facing: FacingMode): Boolean = when (this) {
    MirrorMode.OFF -> false
    MirrorMode.ON -> true
    MirrorMode.AUTO -> facing == FacingMode.FRONT
}

enum class FacingMode { FRONT, BACK }
