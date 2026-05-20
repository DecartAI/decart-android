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

enum class FacingMode { FRONT, BACK }
