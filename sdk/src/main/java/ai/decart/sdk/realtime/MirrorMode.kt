package ai.decart.sdk.realtime

/**
 * Whether to pre-flip the input video horizontally before sending it to the server.
 *
 * Pre-flipping moves the "natural selfie orientation" contract into the SDK so that
 * integrators render the remote stream as-is — without an extra display-time
 * [org.webrtc.SurfaceViewRenderer.setMirror] (true) on the remote view. This matters
 * when the server bakes pixels into the output (e.g. watermarks), since a display-time
 * flip would also flip those pixels.
 */
enum class MirrorMode {
    /** Never mirror. */
    OFF,
    /** Always mirror. */
    ON,
    /** Mirror iff [FacingMode] is [FacingMode.FRONT]. */
    AUTO,
}

/**
 * Camera facing direction for [RealTimeClient.createCameraVideoTrack].
 */
enum class FacingMode { FRONT, BACK }
