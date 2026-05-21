package ai.decart.sdk.realtime

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class IceCandidateData(
    val candidate: String,
    @SerialName("sdpMLineIndex") val sdpMLineIndex: Int? = null,
    @SerialName("sdpMid") val sdpMid: String? = null,
    @SerialName("usernameFragment") val usernameFragment: String? = null
)

sealed interface SignalingMessage

sealed interface ServerMessage : SignalingMessage
sealed interface ClientMessage : SignalingMessage

// Messages shared between server and client

@Serializable
data class OfferMessage(val sdp: String) : ServerMessage, ClientMessage

@Serializable
data class AnswerMessage(val sdp: String) : ServerMessage, ClientMessage

@Serializable
data class IceCandidateMessage(val candidate: IceCandidateData?) : ServerMessage, ClientMessage

// Server-only messages

@Serializable
object ReadyMessage : ServerMessage

@Serializable
data class PromptAckMessage(
    val prompt: String,
    val success: Boolean,
    val error: String? = null
) : ServerMessage

@Serializable
data class ErrorMessage(val error: String) : ServerMessage

@Serializable
data class SetImageAckMessage(
    val success: Boolean,
    val error: String? = null
) : ServerMessage

@Serializable
object GenerationStartedMessage : ServerMessage

@Serializable
data class GenerationTickMessage(val seconds: Double) : ServerMessage

@Serializable
data class GenerationEndedMessage(
    val seconds: Double,
    val reason: String
) : ServerMessage

@Serializable
data class SessionIdMessage(
    @SerialName("session_id") val sessionId: String,
    @SerialName("server_ip") val serverIp: String,
    @SerialName("server_port") val serverPort: Int
) : ServerMessage

@Serializable
data class LiveKitRoomInfoMessage(
    @SerialName("livekit_url") val liveKitUrl: String,
    val token: String,
    @SerialName("room_name") val roomName: String,
    @SerialName("session_id") val sessionId: String
) : ServerMessage

@Serializable
data class StatusMessage(val status: String) : ServerMessage

@Serializable
data class QueuePositionMessage(
    val position: Int? = null,
    @SerialName("queue_position") val legacyQueuePosition: Int? = null,
    @SerialName("queue_size") val queueSize: Int? = null
) : ServerMessage {
    val queuePosition: Int?
        get() = position ?: legacyQueuePosition
}

// Client-only messages

@Serializable
object LiveKitJoinMessage : ClientMessage

@Serializable
data class PromptMessage(
    val prompt: String,
    @SerialName("enhance_prompt") val enhancePrompt: Boolean
) : ClientMessage

@Serializable
data class SetImageMessage(
    @SerialName("image_data") val imageData: String?,
    val prompt: String? = null,
    @SerialName("enhance_prompt") val enhancePrompt: Boolean? = null
) : ClientMessage

// Parser / Serializer

object SignalingMessageParser {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(jsonString: String): ServerMessage {
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        val type = jsonObject["type"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'type' field in message")

        return when (type) {
            "ready" -> ReadyMessage
            "offer" -> json.decodeFromJsonElement<OfferMessage>(jsonObject)
            "answer" -> json.decodeFromJsonElement<AnswerMessage>(jsonObject)
            "ice-candidate" -> json.decodeFromJsonElement<IceCandidateMessage>(jsonObject)
            "prompt_ack" -> json.decodeFromJsonElement<PromptAckMessage>(jsonObject)
            "error" -> json.decodeFromJsonElement<ErrorMessage>(jsonObject)
            "set_image_ack" -> json.decodeFromJsonElement<SetImageAckMessage>(jsonObject)
            "generation_started" -> GenerationStartedMessage
            "generation_tick" -> json.decodeFromJsonElement<GenerationTickMessage>(jsonObject)
            "generation_ended" -> json.decodeFromJsonElement<GenerationEndedMessage>(jsonObject)
            "session_id" -> json.decodeFromJsonElement<SessionIdMessage>(jsonObject)
            "livekit_room_info" -> json.decodeFromJsonElement<LiveKitRoomInfoMessage>(jsonObject)
            "status" -> json.decodeFromJsonElement<StatusMessage>(jsonObject)
            "queue_position" -> json.decodeFromJsonElement<QueuePositionMessage>(jsonObject)
            else -> throw IllegalArgumentException("Unknown message type: $type")
        }
    }

    fun serialize(message: ClientMessage): String {
        val jsonObject = when (message) {
            is OfferMessage -> buildJsonObject {
                put("type", "offer")
                put("sdp", message.sdp)
            }
            is AnswerMessage -> buildJsonObject {
                put("type", "answer")
                put("sdp", message.sdp)
            }
            is IceCandidateMessage -> buildJsonObject {
                put("type", "ice-candidate")
                if (message.candidate != null) {
                    put("candidate", json.encodeToJsonElement(message.candidate))
                } else {
                    put("candidate", JsonNull)
                }
            }
            LiveKitJoinMessage -> buildJsonObject {
                put("type", "livekit_join")
            }
            is PromptMessage -> buildJsonObject {
                put("type", "prompt")
                put("prompt", message.prompt)
                put("enhance_prompt", message.enhancePrompt)
            }
            is SetImageMessage -> buildJsonObject {
                put("type", "set_image")
                if (message.imageData != null) {
                    put("image_data", message.imageData)
                } else {
                    put("image_data", JsonNull)
                }
                if (message.prompt != null) {
                    put("prompt", message.prompt)
                }
                if (message.enhancePrompt != null) {
                    put("enhance_prompt", message.enhancePrompt)
                }
            }
        }
        return jsonObject.toString()
    }
}
