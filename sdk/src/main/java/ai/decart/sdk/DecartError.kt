package ai.decart.sdk

data class DecartError(
    val code: String,
    val message: String,
    val data: Map<String, Any?>? = null,
    val cause: Throwable? = null
)

object ErrorCodes {
    const val INVALID_API_KEY = "INVALID_API_KEY"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val WEBRTC_WEBSOCKET_ERROR = "WEBRTC_WEBSOCKET_ERROR"
    const val WEBRTC_ICE_ERROR = "WEBRTC_ICE_ERROR"
    const val WEBRTC_TIMEOUT_ERROR = "WEBRTC_TIMEOUT_ERROR"
    const val WEBRTC_SERVER_ERROR = "WEBRTC_SERVER_ERROR"
    const val WEBRTC_SIGNALING_ERROR = "WEBRTC_SIGNALING_ERROR"
    const val QUEUE_SUBMIT_ERROR = "QUEUE_SUBMIT_ERROR"
    const val QUEUE_STATUS_ERROR = "QUEUE_STATUS_ERROR"
    const val QUEUE_RESULT_ERROR = "QUEUE_RESULT_ERROR"
}
