package ai.decart.sdk.realtime

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
    val stalled: Boolean,
    val durationMs: Long,
)

/** Emitted once per session, on the first decoded remote frame. */
data class FirstFrameEvent(
    val timeSinceConnectMs: Double,
    val width: Int? = null,
    val height: Int? = null,
)

/** Outbound RTP sample. Flat bytesSent/framesEncoded across samples means the encoder isn't producing data. */
data class PublishStatsEvent(
    val bytesSent: Long,
    val deltaBytes: Long,
    val framesEncoded: Long,
    val deltaFrames: Long,
    val frameWidth: Long,
    val frameHeight: Long,
    val encoderImplementation: String? = null,
    val qualityLimitationReason: String? = null,
)

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
    data class PublishStats(val data: PublishStatsEvent) : DiagnosticEvent()
}

typealias DiagnosticEmitter = (DiagnosticEvent) -> Unit
