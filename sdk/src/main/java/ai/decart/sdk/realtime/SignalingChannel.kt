package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal class SignalingChannel(
    private val logger: Logger = NoopLogger,
    private val onStateChange: (ConnectionState) -> Unit,
    private val onError: (Exception, String?) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var openDeferred: CompletableDeferred<Unit>? = null
    private var roomInfoDeferred: CompletableDeferred<LiveKitRoomInfoMessage>? = null

    private val _promptAckFlow = MutableSharedFlow<PromptAckMessage>(extraBufferCapacity = 10)
    private val _setImageAckFlow = MutableSharedFlow<SetImageAckMessage>(extraBufferCapacity = 10)
    private val _roomInfoFlow = MutableSharedFlow<LiveKitRoomInfoMessage>(extraBufferCapacity = 1)
    private val _sessionIdFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    private val _generationTickFlow = MutableSharedFlow<GenerationTickMessage>(extraBufferCapacity = 10)
    private val _statusFlow = MutableSharedFlow<StatusMessage>(extraBufferCapacity = 10)
    private val _queuePositionFlow = MutableSharedFlow<QueuePositionMessage>(extraBufferCapacity = 10)

    val roomInfoFlow: SharedFlow<LiveKitRoomInfoMessage> = _roomInfoFlow
    val sessionIdFlow: SharedFlow<String> = _sessionIdFlow
    val generationTickFlow: SharedFlow<GenerationTickMessage> = _generationTickFlow
    val statusFlow: SharedFlow<StatusMessage> = _statusFlow
    val queuePositionFlow: SharedFlow<QueuePositionMessage> = _queuePositionFlow

    suspend fun connect(url: String, timeoutMs: Long) {
        val wsUrl = url.withUserAgent()
        val deferred = CompletableDeferred<Unit>()
        openDeferred = deferred
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, listener)
        withTimeout(timeoutMs) { deferred.await() }
    }

    fun send(message: ClientMessage): Boolean {
        val socket = webSocket ?: return false
        return socket.send(SignalingMessageParser.serialize(message))
    }

    suspend fun sendLiveKitJoin(timeoutMs: Long): LiveKitRoomInfoMessage {
        val deferred = CompletableDeferred<LiveKitRoomInfoMessage>()
        roomInfoDeferred = deferred
        if (!send(LiveKitJoinMessage)) {
            roomInfoDeferred = null
            throw IllegalStateException("WebSocket is not open")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            roomInfoDeferred = null
        }
    }

    suspend fun sendInitialPrompt(prompt: InitialPrompt, timeoutMs: Long) {
        val result = awaitPromptAck(prompt, timeoutMs) {
            send(PromptMessage(prompt = prompt.text, enhancePrompt = prompt.enhance))
        }
        if (!result.success) {
            throw IllegalStateException(result.error ?: "Failed to send prompt")
        }
    }

    suspend fun setImage(
        imageBase64: String?,
        options: SetImageOptions = SetImageOptions(),
    ) {
        val result = awaitSetImageAck(options.timeout) {
            send(
                SetImageMessage(
                    imageData = imageBase64,
                    prompt = options.prompt,
                    enhancePrompt = options.enhance,
                ),
            )
        }
        if (!result.success) {
            throw IllegalStateException(result.error ?: "Failed to set image")
        }
    }

    fun close() {
        webSocket?.close(1000, "client disconnect")
        cleanup()
    }

    fun cleanup() {
        openDeferred = null
        roomInfoDeferred = null
        webSocket = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private suspend fun awaitPromptAck(
        prompt: InitialPrompt,
        timeoutMs: Long,
        sendBlock: () -> Boolean,
    ): PromptAckMessage {
        val deferred = CompletableDeferred<PromptAckMessage>()
        val job = scope.launch {
            deferred.complete(_promptAckFlow.first { it.prompt == prompt.text })
        }
        if (!sendBlock()) {
            job.cancel()
            throw IllegalStateException("WebSocket is not open")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            job.cancel()
        }
    }

    private suspend fun awaitSetImageAck(
        timeoutMs: Long,
        sendBlock: () -> Boolean,
    ): SetImageAckMessage {
        val deferred = CompletableDeferred<SetImageAckMessage>()
        val job = scope.launch {
            deferred.complete(_setImageAckFlow.first())
        }
        if (!sendBlock()) {
            job.cancel()
            throw IllegalStateException("WebSocket is not open")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            job.cancel()
        }
    }

    private fun handleMessage(message: ServerMessage) {
        when (message) {
            is ErrorMessage -> {
                val error = Exception(message.error)
                roomInfoDeferred?.completeExceptionally(error)
                onError(error, "server")
            }
            is SetImageAckMessage -> _setImageAckFlow.tryEmit(message)
            is PromptAckMessage -> _promptAckFlow.tryEmit(message)
            is GenerationStartedMessage -> onStateChange(ConnectionState.GENERATING)
            is GenerationTickMessage -> _generationTickFlow.tryEmit(message)
            is GenerationEndedMessage -> Unit
            is SessionIdMessage -> _sessionIdFlow.tryEmit(message.sessionId)
            is LiveKitRoomInfoMessage -> {
                _sessionIdFlow.tryEmit(message.sessionId)
                _roomInfoFlow.tryEmit(message)
                roomInfoDeferred?.complete(message)
            }
            is StatusMessage -> _statusFlow.tryEmit(message)
            is QueuePositionMessage -> _queuePositionFlow.tryEmit(message)
            is ReadyMessage,
            is OfferMessage,
            is AnswerMessage,
            is IceCandidateMessage,
            -> Unit
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            logger.debug("Realtime signaling WebSocket opened")
            openDeferred?.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(SignalingMessageParser.parse(text))
            } catch (e: Exception) {
                logger.error("Failed to handle signaling message", mapOf("error" to e.message))
                onError(e, "signaling")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = if (t is Exception) t else Exception(t)
            openDeferred?.completeExceptionally(error)
            roomInfoDeferred?.completeExceptionally(error)
            onError(error, "websocket")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            logger.debug("Realtime signaling WebSocket closed", mapOf("code" to code, "reason" to reason))
        }
    }

    data class SetImageOptions(
        val prompt: String? = null,
        val enhance: Boolean? = null,
        val timeout: Long = 30_000L,
    )

    private fun String.withUserAgent(): String {
        val separator = if (contains("?")) "&" else "?"
        val userAgent = URLEncoder.encode("decart-android-sdk/0.0.1", "UTF-8")
        return "$this${separator}user_agent=$userAgent"
    }
}
