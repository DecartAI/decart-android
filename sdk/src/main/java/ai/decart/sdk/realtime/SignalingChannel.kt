package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private var roomInfoTimeoutJob: Job? = null

    private val _promptAckFlow = MutableSharedFlow<PromptAckMessage>(extraBufferCapacity = 10)
    private val _setImageAckFlow = MutableSharedFlow<SetImageAckMessage>(extraBufferCapacity = 10)
    private val _roomInfoFlow = MutableSharedFlow<LiveKitRoomInfoMessage>(extraBufferCapacity = 1)
    private val _sessionIdFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    private val _generationTickFlow = MutableSharedFlow<GenerationTickMessage>(extraBufferCapacity = 10)
    private val _generationEndedFlow = MutableSharedFlow<GenerationEndedMessage>(extraBufferCapacity = 10)
    private val _statusFlow = MutableSharedFlow<StatusMessage>(extraBufferCapacity = 10)
    private val _queuePositionFlow = MutableSharedFlow<QueuePositionMessage>(extraBufferCapacity = 10)

    val roomInfoFlow: SharedFlow<LiveKitRoomInfoMessage> = _roomInfoFlow
    val sessionIdFlow: SharedFlow<String> = _sessionIdFlow
    val generationTickFlow: SharedFlow<GenerationTickMessage> = _generationTickFlow
    val generationEndedFlow: SharedFlow<GenerationEndedMessage> = _generationEndedFlow
    val statusFlow: SharedFlow<StatusMessage> = _statusFlow
    val queuePositionFlow: SharedFlow<QueuePositionMessage> = _queuePositionFlow

    // Fail callbacks from in-flight ack waiters. Invoked synchronously from
    // ws close / server error so callers don't hang waiting on a dead scope.
    private val pendingFailHooks = mutableSetOf<(Throwable) -> Unit>()

    private fun registerFailHook(hook: (Throwable) -> Unit) {
        synchronized(pendingFailHooks) { pendingFailHooks.add(hook) }
    }

    private fun unregisterFailHook(hook: (Throwable) -> Unit) {
        synchronized(pendingFailHooks) { pendingFailHooks.remove(hook) }
    }

    private fun resolvePendingWaits(error: Throwable) {
        val hooks: List<(Throwable) -> Unit>
        synchronized(pendingFailHooks) {
            hooks = pendingFailHooks.toList()
            pendingFailHooks.clear()
        }
        for (hook in hooks) hook(error)
    }

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

    /**
     * Sends `livekit_join` and awaits `livekit_room_info`. The timeout is a
     * cancellable [Job] (not [withTimeout]) so a `queue_position` can pause
     * it — users in a queue would otherwise time out before getting a slot.
     */
    suspend fun sendLiveKitJoin(timeoutMs: Long): LiveKitRoomInfoMessage {
        val deferred = CompletableDeferred<LiveKitRoomInfoMessage>()
        roomInfoDeferred = deferred
        val timeoutJob = scope.launch {
            delay(timeoutMs)
            logger.warn("signaling: livekit_room_info timeout", mapOf("timeoutMs" to timeoutMs))
            deferred.completeExceptionally(Exception("livekit_room_info timeout (${timeoutMs}ms)"))
        }
        roomInfoTimeoutJob = timeoutJob

        if (!send(LiveKitJoinMessage)) {
            timeoutJob.cancel()
            roomInfoTimeoutJob = null
            roomInfoDeferred = null
            throw IllegalStateException("WebSocket is not open")
        }
        return try {
            deferred.await()
        } finally {
            timeoutJob.cancel()
            roomInfoTimeoutJob = null
            roomInfoDeferred = null
        }
    }

    suspend fun sendInitialPrompt(prompt: InitialPrompt, timeoutMs: Long) {
        sendPrompt(prompt.text, prompt.enhance, timeoutMs)
    }

    suspend fun sendPrompt(text: String, enhance: Boolean, timeoutMs: Long) {
        awaitAck(
            scope = scope,
            timeoutMs = timeoutMs,
            timeoutMessage = "Prompt send timed out",
            ackFailureMessage = "Failed to send prompt",
            register = ::registerFailHook,
            unregister = ::unregisterFailHook,
            awaitMatchingAck = {
                val ack = _promptAckFlow.first { it.prompt == text }
                AckResult(success = ack.success, error = ack.error)
            },
            send = { send(PromptMessage(prompt = text, enhancePrompt = enhance)) },
        )
    }

    suspend fun setImage(
        imageBase64: String?,
        options: SetImageOptions = SetImageOptions(),
    ) {
        awaitAck(
            scope = scope,
            timeoutMs = options.timeout,
            timeoutMessage = "Image send timed out",
            ackFailureMessage = "Failed to send image",
            register = ::registerFailHook,
            unregister = ::unregisterFailHook,
            awaitMatchingAck = {
                val ack = _setImageAckFlow.first()
                AckResult(success = ack.success, error = ack.error)
            },
            send = {
                send(
                    SetImageMessage(
                        imageData = imageBase64,
                        prompt = options.prompt,
                        enhancePrompt = options.enhance,
                    ),
                )
            },
        )
    }

    fun close() {
        val closeError = Exception("Control channel closed")
        webSocket?.close(1000, "client disconnect")
        resolvePendingWaits(closeError)
        roomInfoDeferred?.completeExceptionally(closeError)
        roomInfoTimeoutJob?.cancel()
        cleanup()
    }

    fun cleanup() {
        openDeferred = null
        roomInfoDeferred = null
        roomInfoTimeoutJob = null
        webSocket = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private fun handleMessage(message: ServerMessage) {
        when (message) {
            is ErrorMessage -> {
                logger.error(
                    "signaling: server error received",
                    mapOf("error" to message.error),
                )
                val error = Exception(message.error)
                roomInfoDeferred?.completeExceptionally(error)
                resolvePendingWaits(error)
                onError(error, "server")
            }
            is SetImageAckMessage -> {
                _setImageAckFlow.tryEmit(message)
            }
            is PromptAckMessage -> {
                _promptAckFlow.tryEmit(message)
            }
            is GenerationStartedMessage -> {
                onStateChange(ConnectionState.GENERATING)
            }
            is GenerationTickMessage -> _generationTickFlow.tryEmit(message)
            is GenerationEndedMessage -> _generationEndedFlow.tryEmit(message)
            is SessionIdMessage -> _sessionIdFlow.tryEmit(message.sessionId)
            is LiveKitRoomInfoMessage -> {
                _sessionIdFlow.tryEmit(message.sessionId)
                _roomInfoFlow.tryEmit(message)
                roomInfoTimeoutJob?.cancel()
                roomInfoTimeoutJob = null
                roomInfoDeferred?.complete(message)
            }
            is StatusMessage -> _statusFlow.tryEmit(message)
            is QueuePositionMessage -> {
                // Cancel the room-info timeout: a queued user may wait minutes.
                roomInfoTimeoutJob?.cancel()
                roomInfoTimeoutJob = null
                _queuePositionFlow.tryEmit(message)
            }
            is ReadyMessage,
            is OfferMessage,
            is AnswerMessage,
            is IceCandidateMessage,
            -> Unit
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            openDeferred?.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(SignalingMessageParser.parse(text))
            } catch (e: Exception) {
                onError(e, "signaling")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = if (t is Exception) t else Exception(t)
            openDeferred?.completeExceptionally(error)
            roomInfoDeferred?.completeExceptionally(error)
            roomInfoTimeoutJob?.cancel()
            resolvePendingWaits(error)
            onError(error, "websocket")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val wasOpen = this@SignalingChannel.webSocket != null
            val pendingCount: Int
            synchronized(pendingFailHooks) { pendingCount = pendingFailHooks.size }
            logger.warn(
                "signaling: websocket closed",
                mapOf(
                    "code" to code,
                    "reason" to reason,
                    "wasConnected" to wasOpen,
                    "pendingAcks" to pendingCount,
                ),
            )
            val error = Exception("WebSocket closed: $code $reason")
            roomInfoDeferred?.completeExceptionally(error)
            roomInfoTimeoutJob?.cancel()
            resolvePendingWaits(error)
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
