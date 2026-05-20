package ai.decart.sdk.realtime

/**
 * Diagnostic event types for connection monitoring.
 * Ported from JS SDK's diagnostics.ts.
 */

/** Connection phase names for timing events. */
enum class ConnectionPhase {
    WEBSOCKET,
    AVATAR_IMAGE,
    INITIAL_PROMPT,
    WEBRTC_HANDSHAKE,
    LIVEKIT_CONNECT,
    LIVEKIT_PUBLISH,
    TOTAL
}

data class PhaseTimingEvent(
    val phase: ConnectionPhase,
    val durationMs: Double,
    val success: Boolean,
    val error: String? = null
)

data class IceCandidateEvent(
    val source: IceCandidateSource,
    val candidateType: CandidateType,
    val protocol: TransportProtocol,
    val address: String? = null,
    val port: Int? = null
) {
    enum class IceCandidateSource { LOCAL, REMOTE }
    enum class CandidateType { HOST, SRFLX, PRFLX, RELAY, UNKNOWN }
    enum class TransportProtocol { UDP, TCP, UNKNOWN }
}

data class IceStateEvent(
    val state: String,
    val previousState: String,
    val timestampMs: Double
)

data class PeerConnectionStateEvent(
    val state: String,
    val previousState: String,
    val timestampMs: Double
)

data class SignalingStateEvent(
    val state: String,
    val previousState: String,
    val timestampMs: Double
)

data class SelectedCandidatePairEvent(
    val local: CandidateInfo,
    val remote: CandidateInfo
) {
    data class CandidateInfo(
        val candidateType: String,
        val protocol: String,
        val address: String? = null,
        val port: Int? = null
    )
}

data class ReconnectEvent(
    val attempt: Int,
    val maxAttempts: Int,
    val durationMs: Double,
    val success: Boolean,
    val error: String? = null
)

data class VideoStallEvent(
    /** True when a stall is detected, false when recovered. */
    val stalled: Boolean,
    /** Duration of the stall in ms (0 when first detected, actual duration on recovery). */
    val durationMs: Long
)

/** Emitted exactly once per session when the first remote video frame is decoded. */
data class FirstFrameEvent(
    /** Time from the LiveKit room connect to first remote frame, in milliseconds. */
    val timeSinceConnectMs: Double,
    /** Width of the first decoded frame in pixels, if known. */
    val width: Int? = null,
    /** Height of the first decoded frame in pixels, if known. */
    val height: Int? = null,
)

/** A single diagnostic event with its name and typed data. */
sealed class DiagnosticEvent {
    data class PhaseTiming(val data: PhaseTimingEvent) : DiagnosticEvent()
    data class IceCandidate(val data: IceCandidateEvent) : DiagnosticEvent()
    data class IceStateChange(val data: IceStateEvent) : DiagnosticEvent()
    data class PeerConnectionStateChange(val data: PeerConnectionStateEvent) : DiagnosticEvent()
    data class SignalingStateChange(val data: SignalingStateEvent) : DiagnosticEvent()
    data class SelectedCandidatePair(val data: SelectedCandidatePairEvent) : DiagnosticEvent()
    data class Reconnect(val data: ReconnectEvent) : DiagnosticEvent()
    data class VideoStall(val data: VideoStallEvent) : DiagnosticEvent()
    data class FirstFrame(val data: FirstFrameEvent) : DiagnosticEvent()
}

/** Callback type for emitting diagnostic events. */
typealias DiagnosticEmitter = (DiagnosticEvent) -> Unit
