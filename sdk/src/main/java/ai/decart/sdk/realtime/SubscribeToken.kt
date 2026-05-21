package ai.decart.sdk.realtime

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Emitted once per session. [subscribeToken] is handed to viewer SDKs so
 * they can join the same room without seeing the publisher's API key.
 */
data class SessionStarted(
    val sessionId: String,
    val subscribeToken: String,
)

/** Equivalent of JS `btoa(JSON.stringify({ room_name }))`. */
@OptIn(ExperimentalEncodingApi::class)
fun encodeSubscribeToken(roomName: String): String {
    val escaped = roomName.replace("\\", "\\\\").replace("\"", "\\\"")
    val json = "{\"room_name\":\"$escaped\"}"
    return Base64.encode(json.toByteArray(Charsets.UTF_8))
}
