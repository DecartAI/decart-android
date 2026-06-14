package ai.decart.sdk.realtime.livekit

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.Logger
import ai.decart.sdk.NoopLogger
import ai.decart.sdk.realtime.ConnectionQualityEvaluator
import ai.decart.sdk.realtime.ConnectionQualityThresholds
import ai.decart.sdk.realtime.DiagnosticEmitter
import ai.decart.sdk.realtime.DiagnosticEvent
import ai.decart.sdk.realtime.G2GMetrics
import ai.decart.sdk.realtime.LiveKitRoomInfoMessage
import ai.decart.sdk.realtime.PixelMarker
import ai.decart.sdk.realtime.PublishStatsEvent
import ai.decart.sdk.realtime.QualitySignals
import ai.decart.sdk.realtime.RealtimeConfiguration
import ai.decart.sdk.realtime.RealtimeMediaStream
import ai.decart.sdk.realtime.SeqTracker
import ai.decart.sdk.realtime.monotonicMs
import android.content.Context
import io.livekit.android.ConnectOptions as LiveKitConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.RTCStats
import livekit.org.webrtc.RTCStatsReport
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink

/**
 * Owns the LiveKit [Room] for one realtime session. The Room is normally
 * supplied by [LocalStreamFactory.createCameraStream] so preview, capture
 * and publish share a single Room; if none is provided we create our own.
 */
internal class LiveKitMediaChannel(
    private val context: Context,
    private val connectOptions: LiveKitConnectOptions,
    private val roomOptions: RoomOptions,
    private val videoConfig: RealtimeConfiguration.VideoConfig,
    private val logger: Logger = NoopLogger,
    private val diagnostics: DiagnosticEmitter? = null,
    private val qualityThresholds: ConnectionQualityThresholds = ConnectionQualityThresholds.DEFAULT,
) {
    data class DisconnectInfo(val reason: String?)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var room: Room? = null
    private var ownsRoom: Boolean = false
    private var roomEventsJob: Job? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var firstFrameSink: FirstFrameSinkRef? = null
    private var roomConnectedAtNs: Long = 0L
    private var publishStatsJob: Job? = null
    private val qualityEvaluator = ConnectionQualityEvaluator(qualityThresholds)
    // Null until the first inbound sample baselines the cumulative freeze counter,
    // so the first delta is 0 (a fresh baseline) rather than the whole running total.
    private var lastFreezeCount: Long? = null

    // Glass-to-glass (opt-in): set per connect when the local stream carries a tracker.
    private var seqTracker: SeqTracker? = null
    private var markerSink: MarkerSinkRef? = null

    // Whether the selected ICE path goes through a TURN relay; null until stats arrive.
    @Volatile
    private var lastIsRelayed: Boolean? = null

    private val _remoteStreamUpdates = MutableSharedFlow<RealtimeMediaStream>(replay = 1)
    private val _connectionStateUpdates = MutableSharedFlow<ConnectionState>(replay = 1)
    private val _disconnectUpdates = MutableSharedFlow<DisconnectInfo>(replay = 1)
    private val _firstFrameEvents = MutableSharedFlow<FirstFrameEventInternal>(replay = 1)

    val remoteStreamUpdates: SharedFlow<RealtimeMediaStream> = _remoteStreamUpdates
    val connectionStateUpdates: SharedFlow<ConnectionState> = _connectionStateUpdates
    val disconnectUpdates: SharedFlow<DisconnectInfo> = _disconnectUpdates
    val firstFrameEvents: SharedFlow<FirstFrameEventInternal> = _firstFrameEvents

    suspend fun connect(roomInfo: LiveKitRoomInfoMessage, providedRoom: Room?) {
        val nextRoom = providedRoom ?: createOwnedRoom()
        adoptRoom(nextRoom, owns = providedRoom == null)
        remoteVideoTrack = null
        detachFirstFrameSink()

        nextRoom.connect(roomInfo.liveKitUrl, roomInfo.token, connectOptions)
        roomConnectedAtNs = System.nanoTime()
        emitExistingRemoteTracks(nextRoom)
    }

    suspend fun publishLocalTracks(stream: RealtimeMediaStream) {
        val participant = requireNotNull(room?.localParticipant) { "LiveKit room is not connected" }
        (stream.videoTrack as? LocalVideoTrack)?.let { track ->
            // Capture was started by LocalStreamFactory; do not stop it on
            // re-publish — Camera2 can freeze on restart.
            track.start()
            val published = participant.publishVideoTrack(track, videoConfig.publishOptions())
            if (!published) {
                throw IllegalStateException("Failed to publish local video track")
            }
            startPublishStatsLoop(track)
        }
    }

    /**
     * Polls outbound RTP stats and emits [DiagnosticEvent.PublishStats]. If
     * bytesSent / framesEncoded stay at zero we're publishing nothing and
     * the server has nothing to generate from.
     */
    private fun startPublishStatsLoop(track: LocalVideoTrack) {
        publishStatsJob?.cancel()
        publishStatsJob = scope.launch {
            var lastBytesSent: Long = 0
            var lastFramesEncoded: Long = 0
            lastFreezeCount = null // re-baseline the freeze delta when the loop (re)starts
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val report = try {
                    track.getRTCStats()
                } catch (_: Exception) {
                    null
                } ?: continue

                var bytesSent: Long = 0
                var framesEncoded: Long = 0
                var frameWidth: Long = 0
                var frameHeight: Long = 0
                var encoderImplementation: String? = null
                var qualityLimitationReason: String? = null

                report.statsMap.values.forEach { stat ->
                    if (stat.type != "outbound-rtp") return@forEach
                    val members = stat.members
                    (members["kind"] as? String)?.let { if (it != "video") return@forEach }
                    (members["bytesSent"] as? Number)?.toLong()?.let { bytesSent += it }
                    (members["framesEncoded"] as? Number)?.toLong()?.let { framesEncoded += it }
                    (members["frameWidth"] as? Number)?.toLong()?.let { if (it > frameWidth) frameWidth = it }
                    (members["frameHeight"] as? Number)?.toLong()?.let { if (it > frameHeight) frameHeight = it }
                    encoderImplementation = encoderImplementation
                        ?: members["encoderImplementation"] as? String
                    qualityLimitationReason = qualityLimitationReason
                        ?: members["qualityLimitationReason"] as? String
                }

                val deltaBytes = bytesSent - lastBytesSent
                val deltaFrames = framesEncoded - lastFramesEncoded
                lastBytesSent = bytesSent
                lastFramesEncoded = framesEncoded

                diagnostics?.invoke(
                    DiagnosticEvent.PublishStats(
                        PublishStatsEvent(
                            bytesSent = bytesSent,
                            deltaBytes = deltaBytes,
                            framesEncoded = framesEncoded,
                            deltaFrames = deltaFrames,
                            frameWidth = frameWidth,
                            frameHeight = frameHeight,
                            encoderImplementation = encoderImplementation,
                            qualityLimitationReason = qualityLimitationReason,
                        ),
                    ),
                )

                // Derive the interpreted connection-quality verdict. RTT/loss/upstream
                // BWE/relay come from this (publisher) report; rendered fps/freezes come
                // from the subscriber PC, read via the remote track's stats.
                val remoteReport = remoteVideoTrack?.let {
                    try {
                        it.getRTCStats()
                    } catch (_: Exception) {
                        null
                    }
                }
                val signals = extractQualitySignals(report, remoteReport, qualityLimitationReason)
                lastIsRelayed = signals.isRelayed
                // Feed the sample (which debounces the *level*), then emit the latest report
                // every tick so the live metrics (rtt/fps/loss/g2g/jitter/…) keep flowing —
                // on Android these are surfaced only through connectionQuality, not `stats`.
                qualityEvaluator.update(signals)
                qualityEvaluator.current()?.let { quality ->
                    diagnostics?.invoke(DiagnosticEvent.ConnectionQualitySample(quality))
                }
            }
        }
    }

    /**
     * Merge the publisher report (RTT/loss/upstream BWE/relay) and the
     * subscriber report (rendered fps/freezes) into the signal set the
     * [ConnectionQualityEvaluator] scores. Missing fields stay null — the
     * scorer treats absence as "good".
     */
    private fun extractQualitySignals(
        localReport: RTCStatsReport,
        remoteReport: RTCStatsReport?,
        qualityLimitationReason: String?,
    ): QualitySignals {
        val stats = localReport.statsMap

        var remoteInboundRttSec: Double? = null
        var remoteInboundJitterSec: Double? = null
        var fractionLostRaw: Double? = null
        var candidatePairRttSec: Double? = null
        var availableOutgoing: Double? = null
        var selectedLocalId: String? = null
        var selectedRemoteId: String? = null
        var bestPairScore = -1

        stats.values.forEach { stat ->
            when (stat.type) {
                "remote-inbound-rtp" -> {
                    val members = stat.members
                    val kind = members["kind"] as? String
                    if (kind == null || kind == "video") {
                        (members["roundTripTime"] as? Number)?.toDouble()?.let { remoteInboundRttSec = it }
                        (members["jitter"] as? Number)?.toDouble()?.let { remoteInboundJitterSec = it }
                        (members["fractionLost"] as? Number)?.toDouble()?.let { fractionLostRaw = it }
                    }
                }
                "candidate-pair" -> {
                    val members = stat.members
                    val state = members["state"] as? String
                    val nominated = (members["nominated"] as? Boolean) == true
                    val hasBwe = members["availableOutgoingBitrate"] != null
                    // Prefer nominated+succeeded, then any pair carrying a BWE estimate,
                    // then any succeeded pair.
                    val score = when {
                        state == "succeeded" && nominated -> 3
                        hasBwe -> 2
                        state == "succeeded" -> 1
                        else -> 0
                    }
                    if (score > bestPairScore) {
                        bestPairScore = score
                        candidatePairRttSec = (members["currentRoundTripTime"] as? Number)?.toDouble()
                        availableOutgoing = (members["availableOutgoingBitrate"] as? Number)?.toDouble()
                        selectedLocalId = members["localCandidateId"] as? String
                        selectedRemoteId = members["remoteCandidateId"] as? String
                    }
                }
            }
        }

        // RTT: prefer the far-end's report, fall back to the candidate pair. ×1000 → ms.
        val rttMs = (remoteInboundRttSec ?: candidatePairRttSec)?.let { it * 1000 }

        // Native libwebrtc reports fractionLost as a 0–1 fraction, but some stacks
        // pass through the raw RFC 3550 8-bit value (loss × 256). Normalize either
        // form: a value > 1 must be the 8-bit scale.
        val fractionLost = fractionLostRaw?.let { if (it > 1.0) it / 256.0 else it }

        var fps: Double? = null
        var freezeCountDelta: Double? = null
        remoteReport?.statsMap?.values?.forEach { stat ->
            if (stat.type != "inbound-rtp") return@forEach
            val members = stat.members
            val kind = members["kind"] as? String
            if (kind != null && kind != "video") return@forEach
            (members["framesPerSecond"] as? Number)?.toDouble()?.let { fps = it }
            (members["freezeCount"] as? Number)?.toLong()?.let { freeze ->
                // First sample after (re)start only baselines — don't count the whole
                // cumulative total as a freeze burst.
                freezeCountDelta = lastFreezeCount?.let { (freeze - it).toDouble() } ?: 0.0
                lastFreezeCount = freeze
            }
        }

        // Glass-to-glass is null unless the opt-in pixel-marker measurement is on.
        val g2g = seqTracker?.snapshot()

        return QualitySignals(
            rttMs = rttMs,
            g2gMs = g2g?.medianMs,
            ttffMs = g2g?.ttffMs,
            upstreamJitterMs = remoteInboundJitterSec?.let { it * 1000 },
            fractionLost = fractionLost,
            g2gDropRatio = g2g?.dropRatio,
            availableOutgoingKbps = availableOutgoing?.let { it / 1000 },
            fps = fps,
            freezeCountDelta = freezeCountDelta,
            qualityLimitationReason = qualityLimitationReason,
            isRelayed = isRelayPair(stats, selectedLocalId, selectedRemoteId),
        )
    }

    private fun isRelayPair(
        stats: Map<String, RTCStats>,
        localId: String?,
        remoteId: String?,
    ): Boolean {
        fun candidateTypeOf(id: String?): String? =
            id?.let { stats[it]?.members?.get("candidateType") as? String }
        return candidateTypeOf(localId) == "relay" || candidateTypeOf(remoteId) == "relay"
    }

    /** Wire glass-to-glass measurement to the local stream's [tracker]; null disables it. */
    fun enableGlassToGlass(tracker: SeqTracker?) {
        seqTracker = tracker
    }

    /** Latest glass-to-glass snapshot, or null if measurement is off. */
    fun currentGlassToGlass(): G2GMetrics? = seqTracker?.snapshot()

    /** Whether the selected ICE path is TURN-relayed, or null before stats arrive. */
    fun currentPathRelayed(): Boolean? = lastIsRelayed

    private fun stopPublishStatsLoop() {
        publishStatsJob?.cancel()
        publishStatsJob = null
    }

    val currentRemoteStream: RealtimeMediaStream
        get() = RealtimeMediaStream(
            videoTrack = remoteVideoTrack,
            id = RealtimeMediaStream.REMOTE_STREAM_ID,
            room = room,
        )

    /**
     * `Room.disconnect()` is terminal on LiveKit Android — local tracks are
     * disposed, so callers must build a fresh Room for the next reconnect.
     */
    fun disconnect() {
        val currentRoom = room
        val shouldReleaseRoom = ownsRoom
        room = null
        ownsRoom = false
        roomEventsJob?.cancel()
        roomEventsJob = null
        stopPublishStatsLoop()
        detachFirstFrameSink()
        detachMarkerReader()
        remoteVideoTrack = null
        if (currentRoom != null) {
            try {
                if (shouldReleaseRoom) {
                    currentRoom.release()
                } else {
                    currentRoom.disconnect()
                }
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    fun cleanup() {
        scope.cancel()
    }

    private fun adoptRoom(newRoom: Room, owns: Boolean) {
        if (room === newRoom) return
        room = newRoom
        ownsRoom = owns
        roomEventsJob?.cancel()
        roomEventsJob = scope.launch {
            newRoom.events.collect { event -> handleRoomEvent(newRoom, event) }
        }
    }

    private fun createOwnedRoom(): Room =
        LiveKit.create(
            appContext = context.applicationContext,
            options = roomOptions,
        )

    private fun handleRoomEvent(room: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected -> {
                _connectionStateUpdates.tryEmit(ConnectionState.CONNECTED)
            }
            is RoomEvent.Reconnecting -> {
                // LiveKit reconnects in-place (this channel + stats loop survive), so
                // reset the quality evaluator the way the SDK-level reconnect does by
                // recreating the channel — otherwise the still-running stats loop would
                // re-emit the stale pre-reconnect verdict and skip a fresh warm-up.
                qualityEvaluator.reset()
                lastFreezeCount = null
                _connectionStateUpdates.tryEmit(ConnectionState.RECONNECTING)
            }
            is RoomEvent.Reconnected -> _connectionStateUpdates.tryEmit(ConnectionState.CONNECTED)
            is RoomEvent.Disconnected -> {
                logger.warn(
                    "livekit: room disconnected",
                    mapOf("reason" to event.reason.name),
                )
                _connectionStateUpdates.tryEmit(ConnectionState.DISCONNECTED)
                _disconnectUpdates.tryEmit(DisconnectInfo(event.error?.message ?: event.reason.name))
            }
            is RoomEvent.ParticipantConnected -> {
                emitExistingRemoteTracks(room)
            }
            is RoomEvent.TrackSubscribed -> {
                handleRemoteTrack(event.participant, event.track)
            }
            is RoomEvent.TrackPublished -> {
                (event.publication as? RemoteTrackPublication)?.setSubscribed(true)
                emitExistingRemoteTracks(room)
            }
            is RoomEvent.TrackSubscriptionFailed -> {
                logger.warn(
                    "livekit: track subscription failed",
                    mapOf(
                        "identity" to event.participant.identity?.value,
                        "sid" to event.sid,
                        "error" to event.exception.message,
                    ),
                )
            }
            is RoomEvent.FailedToConnect -> {
                logger.error(
                    "livekit: failed to connect",
                    mapOf("error" to event.error.message),
                )
            }
            else -> Unit
        }
    }

    private fun emitExistingRemoteTracks(room: Room) {
        room.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach { publication ->
                val remotePub = publication as? RemoteTrackPublication
                remotePub?.setSubscribed(true)
                publication.track?.let { track -> handleRemoteTrack(participant, track) }
            }
        }
    }

    private fun handleRemoteTrack(participant: RemoteParticipant, track: Track) {
        val identity = participant.identity?.value
        if (!shouldAcceptTrack(identity)) return
        when (track) {
            is VideoTrack -> {
                if (remoteVideoTrack !== track) {
                    remoteVideoTrack = track
                    attachFirstFrameSink(track)
                    attachMarkerReader(track)
                }
                emitRemoteStreamIfAvailable()
            }
            else -> Unit
        }
    }

    private fun attachFirstFrameSink(track: VideoTrack) {
        detachFirstFrameSink()
        val startedAtNs = roomConnectedAtNs.takeIf { it != 0L } ?: System.nanoTime()
        val sink = FirstFrameSink { frame ->
            val deltaMs = (System.nanoTime() - startedAtNs) / 1_000_000.0
            val event = FirstFrameEventInternal(
                timeSinceConnectMs = deltaMs,
                width = frame?.rotatedWidth,
                height = frame?.rotatedHeight,
            )
            _firstFrameEvents.tryEmit(event)
            scope.launch { detachFirstFrameSink() }
        }
        try {
            track.addRenderer(sink)
            firstFrameSink = FirstFrameSinkRef(track, sink)
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun detachFirstFrameSink() {
        val ref = firstFrameSink ?: return
        firstFrameSink = null
        try {
            ref.track.removeRenderer(ref.sink)
        } catch (_: Exception) {
            // best-effort
        }
    }

    /** Attach the glass-to-glass marker reader to the remote track (no-op unless enabled). */
    private fun attachMarkerReader(track: VideoTrack) {
        val tracker = seqTracker ?: return
        detachMarkerReader()
        val sink = MarkerReaderSink(tracker)
        try {
            track.addRenderer(sink)
            markerSink = MarkerSinkRef(track, sink)
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun detachMarkerReader() {
        val ref = markerSink ?: return
        markerSink = null
        try {
            ref.track.removeRenderer(ref.sink)
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun emitRemoteStreamIfAvailable() {
        if (remoteVideoTrack != null) {
            _remoteStreamUpdates.tryEmit(currentRemoteStream)
        }
    }

    private fun shouldAcceptTrack(identity: String?): Boolean =
        identity?.startsWith(INFERENCE_SERVER_PREFIX) == true

    internal data class FirstFrameEventInternal(
        val timeSinceConnectMs: Double,
        val width: Int?,
        val height: Int?,
    )

    private class FirstFrameSink(private val onFirstFrame: (VideoFrame?) -> Unit) : VideoSink {
        @Volatile private var fired: Boolean = false
        override fun onFrame(frame: VideoFrame?) {
            if (fired) return
            fired = true
            onFirstFrame(frame)
        }
    }

    private data class FirstFrameSinkRef(val track: VideoTrack, val sink: FirstFrameSink)

    /**
     * Reads the pixel marker off each rendered remote frame's luma (Y) plane and
     * feeds matches to the [SeqTracker]. The marker lives in *display* space, so
     * rotated frames are uprighted first.
     */
    private class MarkerReaderSink(private val tracker: SeqTracker) : VideoSink {
        override fun onFrame(frame: VideoFrame?) {
            frame ?: return
            val i420 = try {
                frame.buffer.toI420()
            } catch (_: Exception) {
                null
            } ?: return
            try {
                val rotation = ((frame.rotation % 360) + 360) % 360
                val upright = if (rotation == 0) i420 else rotateI420ToUpright(i420, rotation)
                try {
                    val dataY = upright.dataY
                    val strideY = upright.strideY
                    val seq = PixelMarker.read(upright.width, upright.height) { x, y ->
                        dataY.get(y * strideY + x).toInt() and 0xff
                    }
                    if (seq != null) tracker.recordInbound(seq, monotonicMs())
                } finally {
                    if (upright !== i420) upright.release()
                }
            } finally {
                i420.release()
            }
        }
    }

    private data class MarkerSinkRef(val track: VideoTrack, val sink: MarkerReaderSink)

    companion object {
        private const val INFERENCE_SERVER_PREFIX = "inference-server-"
        private const val STATS_INTERVAL_MS = 3_000L
    }
}
