package ai.decart.sdk.realtime

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import okhttp3.*
import org.webrtc.*
import java.util.concurrent.TimeUnit

private val ICE_SERVERS = listOf(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
)
private const val AVATAR_SETUP_TIMEOUT_MS = 30_000L

data class InitialPrompt(val text: String, val enhance: Boolean = true)

data class ConnectionCallbacks(
    val onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null,
    val onRemoteAudioTrack: ((AudioTrack) -> Unit)? = null,
    val onStateChange: ((ConnectionState) -> Unit)? = null,
    val onError: ((Exception) -> Unit)? = null,
    val vp8MinBitrate: Int? = null,
    val vp8StartBitrate: Int? = null,
    val modelName: String? = null,
    val initialImage: String? = null,
    val initialPrompt: InitialPrompt? = null,
    val logger: Logger? = null,
    val onDiagnostic: DiagnosticEmitter? = null
)

/**
 * Manages the WebSocket signaling and WebRTC peer connection lifecycle.
 *
 * Ported from the JS SDK's webrtc-connection.ts. Uses OkHttp for WebSocket
 * signaling and the org.webrtc library for the peer connection.
 */
class WebRTCConnection(private val callbacks: ConnectionCallbacks = ConnectionCallbacks()) {

    private val logger: Logger = callbacks.logger ?: NoopLogger
    private val emitDiagnostic: DiagnosticEmitter = callbacks.onDiagnostic ?: {}

    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private var pc: PeerConnection? = null
    private var ws: WebSocket? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null

    /**
     * Reject callback wired during [connect]. Any signaling error, WS closure,
     * or ICE failure invokes this to abort the entire connect flow (mirroring
     * the JS SDK's shared `connectAbort` promise).
     */
    private var connectionReject: ((Exception) -> Unit)? = null

    // ── Event flows for internal message routing (like mitt in JS) ──────

    private val _promptAckFlow = MutableSharedFlow<PromptAckMessage>(extraBufferCapacity = 10)
    private val _setImageAckFlow = MutableSharedFlow<SetImageAckMessage>(extraBufferCapacity = 10)
    private val _sessionIdFlow = MutableSharedFlow<SessionIdMessage>(extraBufferCapacity = 10)
    private val _generationTickFlow = MutableSharedFlow<GenerationTickMessage>(extraBufferCapacity = 10)

    val promptAckFlow: SharedFlow<PromptAckMessage> = _promptAckFlow
    val setImageAckFlow: SharedFlow<SetImageAckMessage> = _setImageAckFlow
    val sessionIdFlow: SharedFlow<SessionIdMessage> = _sessionIdFlow
    val generationTickFlow: SharedFlow<GenerationTickMessage> = _generationTickFlow

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite read timeout for WS
        .build()

    fun getPeerConnection(): PeerConnection? = pc

    // ── Public connect entry point ─────────────────────────────────────

    /**
     * Opens a signaling WebSocket, performs the pre-handshake setup (image /
     * prompt / passthrough), creates the peer connection, and waits until the
     * connection state reaches [ConnectionState.CONNECTED] or
     * [ConnectionState.GENERATING].
     */
    suspend fun connect(
        url: String,
        localVideoTrack: VideoTrack?,
        localAudioTrack: AudioTrack?,
        peerConnectionFactory: PeerConnectionFactory,
        timeout: Long
    ) {
        val deadline = System.currentTimeMillis() + timeout
        this.localVideoTrack = localVideoTrack
        this.localAudioTrack = localAudioTrack

        // Append user agent as query parameter (mirrors JS buildUserAgent)
        val userAgent = "decart-android-sdk/0.0.1"
        val separator = if (url.contains("?")) "&" else "?"
        val wsUrl = "$url${separator}user_agent=${java.net.URLEncoder.encode(userAgent, "UTF-8")}"

        val totalStart = System.nanoTime()

        try {
            // Phase 1: WebSocket setup
            val wsStart = System.nanoTime()
            openWebSocket(wsUrl, timeout)
            emitDiagnostic(
                DiagnosticEvent.PhaseTiming(
                    PhaseTimingEvent(
                        phase = ConnectionPhase.WEBSOCKET,
                        durationMs = (System.nanoTime() - wsStart) / 1_000_000.0,
                        success = true
                    )
                )
            )

            // Phase 2: Pre-handshake setup (initial image and/or prompt)
            if (callbacks.initialImage != null) {
                val imageStart = System.nanoTime()
                setImageBase64(
                    callbacks.initialImage,
                    SetImageOptions(
                        prompt = callbacks.initialPrompt?.text,
                        enhance = callbacks.initialPrompt?.enhance
                    )
                )
                emitDiagnostic(
                    DiagnosticEvent.PhaseTiming(
                        PhaseTimingEvent(
                            phase = ConnectionPhase.AVATAR_IMAGE,
                            durationMs = (System.nanoTime() - imageStart) / 1_000_000.0,
                            success = true
                        )
                    )
                )
            } else if (callbacks.initialPrompt != null) {
                val promptStart = System.nanoTime()
                sendInitialPrompt(callbacks.initialPrompt)
                emitDiagnostic(
                    DiagnosticEvent.PhaseTiming(
                        PhaseTimingEvent(
                            phase = ConnectionPhase.INITIAL_PROMPT,
                            durationMs = (System.nanoTime() - promptStart) / 1_000_000.0,
                            success = true
                        )
                    )
                )
            } else if (localVideoTrack != null) {
                // No image and no prompt — send passthrough (skip for subscribe mode)
                val nullStart = System.nanoTime()
                setImageBase64(null, SetImageOptions(prompt = null))
                emitDiagnostic(
                    DiagnosticEvent.PhaseTiming(
                        PhaseTimingEvent(
                            phase = ConnectionPhase.INITIAL_PROMPT,
                            durationMs = (System.nanoTime() - nullStart) / 1_000_000.0,
                            success = true
                        )
                    )
                )
            }

            // Phase 3: WebRTC handshake
            val handshakeStart = System.nanoTime()
            setupNewPeerConnection(peerConnectionFactory)

            // Wait for connection state to reach CONNECTED or GENERATING
            waitForConnection(deadline)

            emitDiagnostic(
                DiagnosticEvent.PhaseTiming(
                    PhaseTimingEvent(
                        phase = ConnectionPhase.WEBRTC_HANDSHAKE,
                        durationMs = (System.nanoTime() - handshakeStart) / 1_000_000.0,
                        success = true
                    )
                )
            )

            emitDiagnostic(
                DiagnosticEvent.PhaseTiming(
                    PhaseTimingEvent(
                        phase = ConnectionPhase.TOTAL,
                        durationMs = (System.nanoTime() - totalStart) / 1_000_000.0,
                        success = true
                    )
                )
            )
        } finally {
            connectionReject = null
        }
    }

    // ── WebSocket lifecycle ────────────────────────────────────────────

    /**
     * Opens an OkHttp WebSocket and suspends until [WebSocketListener.onOpen]
     * fires or the [timeout] elapses.
     */
    private suspend fun openWebSocket(url: String, timeout: Long) =
        suspendCancellableCoroutine { cont ->
            val request = Request.Builder().url(url).build()

            val timeoutJob = coroutineScope.launch {
                delay(timeout)
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(Exception("WebSocket timeout")))
                }
            }

            ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    timeoutJob.cancel()
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(Unit))
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = SignalingMessageParser.parse(text)
                        handleSignalingMessage(message)
                    } catch (e: Exception) {
                        logger.error("Signaling message parse error", mapOf("error" to e.toString()))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    timeoutJob.cancel()
                    val error = Exception("WebSocket error: ${t.message}", t)
                    if (cont.isActive) {
                        cont.resumeWith(Result.failure(error))
                    }
                    connectionReject?.invoke(error)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    setState(ConnectionState.DISCONNECTED)
                    timeoutJob.cancel()
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.failure(Exception("WebSocket closed before connection was established"))
                        )
                    }
                    connectionReject?.invoke(Exception("WebSocket closed"))
                }
            })

            cont.invokeOnCancellation {
                timeoutJob.cancel()
                ws?.cancel()
            }
        }

    // ── Connection state polling ───────────────────────────────────────

    /**
     * Polls [state] at 100 ms intervals until it reaches CONNECTED / GENERATING,
     * or until the [deadline] is exceeded (whichever comes first).
     */
    private suspend fun waitForConnection(deadline: Long) =
        suspendCancellableCoroutine { cont ->
            connectionReject = { error ->
                if (cont.isActive) cont.resumeWith(Result.failure(error))
            }

            val checkJob = coroutineScope.launch {
                while (isActive) {
                    when {
                        state == ConnectionState.CONNECTED ||
                            state == ConnectionState.GENERATING -> {
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                            return@launch
                        }
                        state == ConnectionState.DISCONNECTED -> {
                            if (cont.isActive) {
                                cont.resumeWith(
                                    Result.failure(Exception("Connection lost during WebRTC handshake"))
                                )
                            }
                            return@launch
                        }
                        System.currentTimeMillis() >= deadline -> {
                            if (cont.isActive) {
                                cont.resumeWith(Result.failure(Exception("Connection timeout")))
                            }
                            return@launch
                        }
                    }
                    delay(100)
                }
            }

            cont.invokeOnCancellation { checkJob.cancel() }
        }

    // ── Signaling message dispatch ─────────────────────────────────────

    private fun handleSignalingMessage(msg: ServerMessage) {
        try {
            when (msg) {
                is ErrorMessage -> {
                    val error = Exception(msg.error)
                    callbacks.onError?.invoke(error)
                    connectionReject?.invoke(error)
                    connectionReject = null
                }
                is SetImageAckMessage -> {
                    _setImageAckFlow.tryEmit(msg)
                }
                is PromptAckMessage -> {
                    _promptAckFlow.tryEmit(msg)
                }
                is GenerationStartedMessage -> {
                    setState(ConnectionState.GENERATING)
                }
                is GenerationTickMessage -> {
                    _generationTickFlow.tryEmit(msg)
                }
                is GenerationEndedMessage -> {
                    // Handled internally — not surfaced as a public event.
                    // Devs use onStateChange for disconnect and onError for errors.
                }
                is SessionIdMessage -> {
                    _sessionIdFlow.tryEmit(msg)
                }
                is ReadyMessage -> {
                    handleReady()
                }
                is OfferMessage -> {
                    handleOffer(msg)
                }
                is AnswerMessage -> {
                    handleAnswer(msg)
                }
                is IceCandidateMessage -> {
                    handleIceCandidate(msg)
                }
            }
        } catch (e: Exception) {
            logger.error("Signaling handler error", mapOf("error" to e.toString()))
            callbacks.onError?.invoke(e)
            connectionReject?.invoke(e)
        }
    }

    // ── WebRTC signaling handlers ──────────────────────────────────────

    private fun handleReady() {
        val pc = this.pc ?: return
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                var sdp = SdpUtils.preferVP8(desc.description)
                val minBitrate = callbacks.vp8MinBitrate
                val startBitrate = callbacks.vp8StartBitrate
                if (minBitrate != null && startBitrate != null &&
                    !(minBitrate == 0 && startBitrate == 0)
                ) {
                    sdp = SdpUtils.injectBitrate(sdp, minBitrate, startBitrate)
                }
                val modifiedDesc = SessionDescription(desc.type, sdp)
                pc.setLocalDescription(SdpObserverAdapter(), modifiedDesc)
                send(OfferMessage(sdp))
            }

            override fun onCreateFailure(error: String?) {
                val e = Exception("Failed to create offer: $error")
                logger.error("createOffer failed", mapOf("error" to (error ?: "")))
                callbacks.onError?.invoke(e)
                connectionReject?.invoke(e)
            }
        }, MediaConstraints())
    }

    private fun handleOffer(msg: OfferMessage) {
        val pc = this.pc ?: return
        pc.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    pc.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            pc.setLocalDescription(SdpObserverAdapter(), desc)
                            send(AnswerMessage(desc.description))
                        }

                        override fun onCreateFailure(error: String?) {
                            val e = Exception("Failed to create answer: $error")
                            logger.error("createAnswer failed", mapOf("error" to (error ?: "")))
                            callbacks.onError?.invoke(e)
                            connectionReject?.invoke(e)
                        }
                    }, MediaConstraints())
                }

                override fun onSetFailure(error: String?) {
                    val e = Exception("Failed to set remote description (offer): $error")
                    logger.error("setRemoteDescription failed", mapOf("error" to (error ?: "")))
                    callbacks.onError?.invoke(e)
                    connectionReject?.invoke(e)
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, msg.sdp)
        )
    }

    private fun handleAnswer(msg: AnswerMessage) {
        val pc = this.pc ?: return
        pc.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetFailure(error: String?) {
                    val e = Exception("Failed to set remote description (answer): $error")
                    logger.error("setRemoteDescription failed", mapOf("error" to (error ?: "")))
                    callbacks.onError?.invoke(e)
                    connectionReject?.invoke(e)
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
        )
    }

    private fun handleIceCandidate(msg: IceCandidateMessage) {
        val pc = this.pc ?: return
        val candidateData = msg.candidate ?: return
        val iceCandidate = IceCandidate(
            candidateData.sdpMid ?: "",
            candidateData.sdpMLineIndex ?: 0,
            candidateData.candidate
        )
        pc.addIceCandidate(iceCandidate)

        val candidateStr = candidateData.candidate
        val typeMatch = Regex("typ (\\w+)").find(candidateStr)
        val protocolMatch = Regex("(?i)(udp|tcp)").find(candidateStr)
        emitDiagnostic(
            DiagnosticEvent.IceCandidate(
                IceCandidateEvent(
                    source = IceCandidateEvent.IceCandidateSource.REMOTE,
                    candidateType = parseCandidateType(typeMatch?.groupValues?.get(1)),
                    protocol = parseProtocol(protocolMatch?.groupValues?.get(1))
                )
            )
        )
    }

    // ── Message sending ────────────────────────────────────────────────

    /**
     * Serialise and send a [ClientMessage] over the WebSocket.
     * Returns `true` if the message was enqueued, `false` if the socket is null.
     */
    fun send(message: ClientMessage): Boolean {
        val ws = this.ws ?: run {
            logger.warn("Message dropped: WebSocket is not open")
            return false
        }
        val json = SignalingMessageParser.serialize(message)
        return ws.send(json)
    }

    data class SetImageOptions(
        val prompt: String? = null,
        val enhance: Boolean? = null,
        val timeout: Long = AVATAR_SETUP_TIMEOUT_MS
    )

    /**
     * Send a `set_image` message and suspend until the server acknowledges it
     * or the [SetImageOptions.timeout] elapses.
     */
    suspend fun setImageBase64(
        imageBase64: String?,
        options: SetImageOptions = SetImageOptions()
    ) = suspendCancellableCoroutine { cont ->
        val timeoutJob = coroutineScope.launch {
            delay(options.timeout)
            if (cont.isActive) {
                cont.resumeWith(Result.failure(Exception("Image send timed out")))
            }
        }

        val listenerJob = coroutineScope.launch {
            _setImageAckFlow.first().let { msg ->
                timeoutJob.cancel()
                if (cont.isActive) {
                    if (msg.success) {
                        cont.resumeWith(Result.success(Unit))
                    } else {
                        cont.resumeWith(Result.failure(Exception(msg.error ?: "Failed to send image")))
                    }
                }
            }
        }

        val message = SetImageMessage(
            imageData = imageBase64,
            prompt = if (options.prompt != null || imageBase64 == null) options.prompt else null,
            enhancePrompt = options.enhance
        )

        if (!send(message)) {
            timeoutJob.cancel()
            listenerJob.cancel()
            if (cont.isActive) {
                cont.resumeWith(Result.failure(Exception("WebSocket is not open")))
            }
        }

        cont.invokeOnCancellation {
            timeoutJob.cancel()
            listenerJob.cancel()
        }
    }

    /**
     * Send a prompt message during an active session. Does not wait for an
     * acknowledgement — fire-and-forget.
     */
    fun sendPromptMessage(prompt: String, enhance: Boolean) {
        send(PromptMessage(prompt = prompt, enhancePrompt = enhance))
    }

    /**
     * Send the initial prompt before the WebRTC handshake and suspend until
     * the server acknowledges it.
     */
    private suspend fun sendInitialPrompt(prompt: InitialPrompt) =
        suspendCancellableCoroutine { cont ->
            val timeoutJob = coroutineScope.launch {
                delay(AVATAR_SETUP_TIMEOUT_MS)
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(Exception("Prompt send timed out")))
                }
            }

            val listenerJob = coroutineScope.launch {
                _promptAckFlow.first { it.prompt == prompt.text }.let { msg ->
                    timeoutJob.cancel()
                    if (cont.isActive) {
                        if (msg.success) {
                            cont.resumeWith(Result.success(Unit))
                        } else {
                            cont.resumeWith(
                                Result.failure(Exception(msg.error ?: "Failed to send prompt"))
                            )
                        }
                    }
                }
            }

            if (!send(PromptMessage(prompt = prompt.text, enhancePrompt = prompt.enhance))) {
                timeoutJob.cancel()
                listenerJob.cancel()
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(Exception("WebSocket is not open")))
                }
            }

            cont.invokeOnCancellation {
                timeoutJob.cancel()
                listenerJob.cancel()
            }
        }

    // ── State management ───────────────────────────────────────────────

    private fun setState(newState: ConnectionState) {
        if (state != newState) {
            state = newState
            callbacks.onStateChange?.invoke(newState)
        }
    }

    // ── PeerConnection setup ───────────────────────────────────────────

    private fun setupNewPeerConnection(factory: PeerConnectionFactory) {
        // Tear down any existing peer connection
        pc?.close()

        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        var prevPcState = "new"
        var prevIceState = "new"
        var prevSignalingState = "stable"

        pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {

            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                val newState = signalingState.name
                emitDiagnostic(
                    DiagnosticEvent.SignalingStateChange(
                        SignalingStateEvent(
                            state = newState,
                            previousState = prevSignalingState,
                            timestampMs = System.nanoTime() / 1_000_000.0
                        )
                    )
                )
                prevSignalingState = newState
            }

            override fun onIceConnectionChange(iceState: PeerConnection.IceConnectionState) {
                val newState = iceState.name
                emitDiagnostic(
                    DiagnosticEvent.IceStateChange(
                        IceStateEvent(
                            state = newState,
                            previousState = prevIceState,
                            timestampMs = System.nanoTime() / 1_000_000.0
                        )
                    )
                )
                prevIceState = newState

                if (iceState == PeerConnection.IceConnectionState.FAILED) {
                    setState(ConnectionState.DISCONNECTED)
                    callbacks.onError?.invoke(Exception("ICE connection failed"))
                }
            }

            override fun onConnectionChange(newPcState: PeerConnection.PeerConnectionState) {
                val stateStr = newPcState.name
                emitDiagnostic(
                    DiagnosticEvent.PeerConnectionStateChange(
                        PeerConnectionStateEvent(
                            state = stateStr,
                            previousState = prevPcState,
                            timestampMs = System.nanoTime() / 1_000_000.0
                        )
                    )
                )
                prevPcState = stateStr

                if (newPcState == PeerConnection.PeerConnectionState.CONNECTED) {
                    emitSelectedCandidatePair()
                }

                val nextState = when (newPcState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> ConnectionState.CONNECTED
                    PeerConnection.PeerConnectionState.CONNECTING,
                    PeerConnection.PeerConnectionState.NEW -> ConnectionState.CONNECTING
                    else -> ConnectionState.DISCONNECTED
                }
                // Keep "generating" sticky unless connection is actually lost
                if (state == ConnectionState.GENERATING && nextState != ConnectionState.DISCONNECTED) return
                setState(nextState)
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                send(
                    IceCandidateMessage(
                        IceCandidateData(
                            candidate = candidate.sdp,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            sdpMid = candidate.sdpMid
                        )
                    )
                )
                emitDiagnostic(
                    DiagnosticEvent.IceCandidate(
                        IceCandidateEvent(
                            source = IceCandidateEvent.IceCandidateSource.LOCAL,
                            candidateType = parseCandidateType(
                                Regex("typ (\\w+)").find(candidate.sdp)?.groupValues?.get(1)
                            ),
                            protocol = parseProtocol(
                                Regex("(?i)(udp|tcp)").find(candidate.sdp)?.groupValues?.get(1)
                            )
                        )
                    )
                )
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                when (val track = transceiver.receiver?.track()) {
                    is VideoTrack -> callbacks.onRemoteVideoTrack?.invoke(track)
                    is AudioTrack -> callbacks.onRemoteAudioTrack?.invoke(track)
                }
            }

            // Required no-op stubs
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })

        setState(ConnectionState.CONNECTING)

        val pc = this.pc ?: return

        if (localVideoTrack != null || localAudioTrack != null) {
            // For live_avatar: add receive-only video transceiver
            // (sends audio only via addTrack, receives video + audio from server)
            if (callbacks.modelName == "live-avatar" || callbacks.modelName == "live_avatar") {
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
            }

            // Add local tracks to the peer connection
            val streamIds = listOf("local_stream")
            localVideoTrack?.let { pc.addTrack(it, streamIds) }
            localAudioTrack?.let { pc.addTrack(it, streamIds) }
        } else {
            // Subscribe mode: receive-only transceivers for both video and audio
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }

        // Trigger the "ready" flow immediately — mirrors JS SDK which
        // calls this.handleSignalingMessage({ type: "ready" }) after setup.
        handleSignalingMessage(ReadyMessage)
    }

    // ── Diagnostics helpers ────────────────────────────────────────────

    /**
     * Query the peer connection stats to find the selected (succeeded)
     * candidate pair and emit it as a diagnostic event.
     */
    private fun emitSelectedCandidatePair() {
        val pc = this.pc ?: return
        coroutineScope.launch {
            try {
                // getStats is async; we use a continuation to bridge it
                val stats = suspendCancellableCoroutine { cont ->
                    pc.getStats { report ->
                        if (cont.isActive) cont.resumeWith(Result.success(report))
                    }
                }

                var found = false
                for (report in stats.statsMap.values) {
                    if (found) break
                    if (report.type == "candidate-pair") {
                        val members = report.members
                        if (members["state"] == "succeeded") {
                            found = true
                            val localId = members["localCandidateId"] as? String
                            val remoteId = members["remoteCandidateId"] as? String

                            var localInfo: SelectedCandidatePairEvent.CandidateInfo? = null
                            var remoteInfo: SelectedCandidatePairEvent.CandidateInfo? = null

                            for (r in stats.statsMap.values) {
                                if (r.id == localId) {
                                    localInfo = SelectedCandidatePairEvent.CandidateInfo(
                                        candidateType = r.members["candidateType"]?.toString() ?: "unknown",
                                        protocol = r.members["protocol"]?.toString() ?: "unknown",
                                        address = r.members["address"]?.toString(),
                                        port = (r.members["port"] as? Number)?.toInt()
                                    )
                                }
                                if (r.id == remoteId) {
                                    remoteInfo = SelectedCandidatePairEvent.CandidateInfo(
                                        candidateType = r.members["candidateType"]?.toString() ?: "unknown",
                                        protocol = r.members["protocol"]?.toString() ?: "unknown",
                                        address = r.members["address"]?.toString(),
                                        port = (r.members["port"] as? Number)?.toInt()
                                    )
                                }
                            }

                            if (localInfo != null && remoteInfo != null) {
                                emitDiagnostic(
                                    DiagnosticEvent.SelectedCandidatePair(
                                        SelectedCandidatePairEvent(
                                            local = localInfo,
                                            remote = remoteInfo
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // getStats can fail if PC is already closed; silently ignore
            }
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────

    /**
     * Tear down the peer connection and WebSocket. Local tracks are NOT
     * stopped — they belong to the caller, not the SDK.
     */
    fun cleanup() {
        pc?.close()
        pc = null
        ws?.close(1000, "cleanup")
        ws = null
        localVideoTrack = null
        localAudioTrack = null
        coroutineScope.coroutineContext.cancelChildren()
        setState(ConnectionState.DISCONNECTED)
    }

    // ── Companion helpers ──────────────────────────────────────────────

    companion object {
        fun parseCandidateType(type: String?): IceCandidateEvent.CandidateType {
            return when (type?.lowercase()) {
                "host" -> IceCandidateEvent.CandidateType.HOST
                "srflx" -> IceCandidateEvent.CandidateType.SRFLX
                "prflx" -> IceCandidateEvent.CandidateType.PRFLX
                "relay" -> IceCandidateEvent.CandidateType.RELAY
                else -> IceCandidateEvent.CandidateType.UNKNOWN
            }
        }

        fun parseProtocol(protocol: String?): IceCandidateEvent.TransportProtocol {
            return when (protocol?.lowercase()) {
                "udp" -> IceCandidateEvent.TransportProtocol.UDP
                "tcp" -> IceCandidateEvent.TransportProtocol.TCP
                else -> IceCandidateEvent.TransportProtocol.UNKNOWN
            }
        }
    }
}

/**
 * Adapter that provides empty default implementations for [SdpObserver],
 * allowing callers to override only the callbacks they care about.
 */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
