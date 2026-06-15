package ai.decart.sdk.realtime

import ai.decart.sdk.BuildConfig
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private val onClosed: (Int, String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var openDeferred: CompletableDeferred<Unit>? = null
    private var roomInfoDeferred: CompletableDeferred<LiveKitRoomInfoMessage>? = null
    private var roomInfoTimeoutJob: Job? = null
    private var connected: Boolean = false
    private var closing: Boolean = false

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
    private val ackLock = Any()
    private val pendingAckWaiters = mutableListOf<PendingAckWaiter>()
    private val bufferedAcks = mutableListOf<ServerMessage>()

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
        connected = false
        clearBufferedAcks()
        closing = false
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
    suspend fun sendLiveKitJoin(
        timeoutMs: Long,
        initialState: InitialState? = null,
    ): LiveKitRoomInfoMessage {
        val deferred = CompletableDeferred<LiveKitRoomInfoMessage>()
        roomInfoDeferred = deferred
        val timeoutJob = scope.launch {
            delay(timeoutMs)
            logger.warn("signaling: livekit_room_info timeout", mapOf("timeoutMs" to timeoutMs))
            deferred.completeExceptionally(Exception("livekit_room_info timeout (${timeoutMs}ms)"))
        }
        roomInfoTimeoutJob = timeoutJob

        val initialStateMessage = initialState.toInitialStateMessage()
        if (!send(LiveKitJoinMessage(initialState = initialStateMessage))) {
            timeoutJob.cancel()
            roomInfoTimeoutJob = null
            roomInfoDeferred = null
            throw IllegalStateException("WebSocket is not open")
        }
        return try {
            val roomInfo = deferred.await()
            if (initialStateMessage == null) connected = true
            roomInfo
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
        awaitAckMessage(
            timeoutMs = timeoutMs,
            timeoutMessage = "Prompt send timed out",
            ackFailureMessage = "Failed to send prompt",
            matches = { it is PromptAckMessage && it.prompt == text },
            ackResult = { message ->
                val ack = message as PromptAckMessage
                AckResult(success = ack.success, error = ack.error)
            },
            send = { send(PromptMessage(prompt = text, enhancePrompt = enhance)) },
        )
    }

    suspend fun setImage(
        imageBase64: String?,
        options: SetImageOptions = SetImageOptions(),
    ) {
        awaitAckMessage(
            timeoutMs = options.timeout,
            timeoutMessage = "Image send timed out",
            ackFailureMessage = "Failed to send image",
            matches = { it is SetImageAckMessage },
            ackResult = { message ->
                val ack = message as SetImageAckMessage
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

    suspend fun awaitInitialStateAck(initialState: InitialState?, timeoutMs: Long) {
        val initialStateMessage = initialState.toInitialStateMessage()
        if (initialStateMessage == null) {
            connected = true
            return
        }

        when (initialStateMessage) {
            is PromptMessage -> {
                awaitAckMessage(
                    timeoutMs = timeoutMs,
                    timeoutMessage = "Prompt send timed out",
                    ackFailureMessage = "Failed to send prompt",
                    matches = {
                        it is PromptAckMessage && it.prompt == initialStateMessage.prompt
                    },
                    ackResult = { message ->
                        val ack = message as PromptAckMessage
                        AckResult(success = ack.success, error = ack.error)
                    },
                    send = { true },
                )
            }
            is SetImageMessage -> {
                awaitAckMessage(
                    timeoutMs = timeoutMs,
                    timeoutMessage = "Image send timed out",
                    ackFailureMessage = "Failed to send image",
                    matches = { it is SetImageAckMessage },
                    ackResult = { message ->
                        val ack = message as SetImageAckMessage
                        AckResult(success = ack.success, error = ack.error)
                    },
                    send = { true },
                )
            }
        }
        connected = true
    }

    fun close() {
        val closeError = Exception("Control channel closed")
        closing = true
        connected = false
        webSocket?.close(1000, "client disconnect")
        resolvePendingWaits(closeError)
        clearBufferedAcks()
        roomInfoDeferred?.completeExceptionally(closeError)
        roomInfoTimeoutJob?.cancel()
        cleanup()
    }

    fun cleanup() {
        openDeferred = null
        roomInfoDeferred = null
        roomInfoTimeoutJob = null
        webSocket = null
        connected = false
        clearBufferedAcks()
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
                clearBufferedAcks()
                onError(error, "server")
            }
            is SetImageAckMessage -> {
                handleAckMessage(message)
            }
            is PromptAckMessage -> {
                handleAckMessage(message)
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
            connected = false
            openDeferred?.completeExceptionally(error)
            roomInfoDeferred?.completeExceptionally(error)
            roomInfoTimeoutJob?.cancel()
            resolvePendingWaits(error)
            clearBufferedAcks()
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
            connected = false
            roomInfoDeferred?.completeExceptionally(error)
            roomInfoTimeoutJob?.cancel()
            resolvePendingWaits(error)
            clearBufferedAcks()
            if (!closing) {
                onClosed(code, reason)
            }
        }
    }

    data class SetImageOptions(
        val prompt: String? = null,
        val enhance: Boolean? = null,
        val timeout: Long = 30_000L,
    )

    private data class PendingAckWaiter(
        val matches: (ServerMessage) -> Boolean,
        val onMatch: (ServerMessage) -> Unit,
    )

    private suspend fun awaitAckMessage(
        timeoutMs: Long,
        timeoutMessage: String,
        ackFailureMessage: String,
        matches: (ServerMessage) -> Boolean,
        ackResult: (ServerMessage) -> AckResult,
        send: () -> Boolean,
    ): Unit = suspendCancellableCoroutine { cont ->
        var timeoutJob: Job? = null
        var registered = false
        var entry: PendingAckWaiter? = null
        lateinit var failHook: (Throwable) -> Unit

        fun resolve(result: Result<Unit>) {
            if (!cont.isActive) return
            timeoutJob?.cancel()
            entry?.let { waiter ->
                synchronized(ackLock) { pendingAckWaiters.remove(waiter) }
            }
            if (registered) unregisterFailHook(failHook)
            try {
                cont.resumeWith(result)
            } catch (_: IllegalStateException) {
                // already resumed by a race winner
            }
        }

        fun resolveAck(message: ServerMessage) {
            val ack = ackResult(message)
            if (ack.success) {
                resolve(Result.success(Unit))
            } else {
                resolve(Result.failure(Exception(ack.error ?: ackFailureMessage)))
            }
        }

        val buffered = synchronized(ackLock) {
            val index = bufferedAcks.indexOfFirst(matches)
            if (index == -1) null else bufferedAcks.removeAt(index)
        }
        if (buffered != null) {
            resolveAck(buffered)
            return@suspendCancellableCoroutine
        }

        failHook = { resolve(Result.failure(it)) }
        entry = PendingAckWaiter(matches = matches, onMatch = ::resolveAck)

        synchronized(ackLock) { pendingAckWaiters.add(entry!!) }
        timeoutJob = scope.launch {
            delay(timeoutMs)
            logger.warn("signaling: ack timed out", mapOf("timeoutMs" to timeoutMs))
            resolve(Result.failure(Exception(timeoutMessage)))
        }
        registerFailHook(failHook)
        registered = true
        if (!cont.isActive) {
            unregisterFailHook(failHook)
            registered = false
            return@suspendCancellableCoroutine
        }

        if (!send()) {
            resolve(Result.failure(Exception("WebSocket is not open")))
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            timeoutJob?.cancel()
            entry?.let { waiter ->
                synchronized(ackLock) { pendingAckWaiters.remove(waiter) }
            }
            if (registered) unregisterFailHook(failHook)
        }
    }

    private fun handleAckMessage(message: ServerMessage) {
        val waiter = synchronized(ackLock) {
            val index = pendingAckWaiters.indexOfFirst { it.matches(message) }
            if (index != -1) {
                pendingAckWaiters.removeAt(index)
            } else {
                if (!connected) bufferedAcks.add(message)
                null
            }
        }
        waiter?.onMatch(message)
    }

    private fun clearBufferedAcks() {
        synchronized(ackLock) { bufferedAcks.clear() }
    }

    private fun InitialState?.toInitialStateMessage(): InitialStateMessage? {
        val state = this ?: return null
        return if (state.image != null || state.prompt == null) {
            SetImageMessage(
                imageData = state.image,
                prompt = state.prompt,
                enhancePrompt = state.enhance,
            )
        } else {
            PromptMessage(
                prompt = state.prompt,
                enhancePrompt = state.enhance ?: true,
            )
        }
    }

    private fun String.withUserAgent(): String {
        val separator = if (contains("?")) "&" else "?"
        val userAgent = URLEncoder.encode("decart-android-sdk/${BuildConfig.SDK_VERSION}", "UTF-8")
        return "$this${separator}user_agent=$userAgent"
    }
}
