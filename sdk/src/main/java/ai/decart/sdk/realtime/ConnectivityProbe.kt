package ai.decart.sdk.realtime

import ai.decart.sdk.Logger
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val TYP_REGEX = Regex("""\btyp (\w+)""")

/** Extract the candidate type ("host" | "srflx" | "prflx" | "relay") from the SDP line. */
private fun candidateType(candidate: IceCandidate): String =
    TYP_REGEX.find(candidate.sdp)?.groupValues?.getOrNull(1) ?: ""

/** SdpObserver that ignores set-description outcomes. */
private object NoopSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

/**
 * Spin up a throwaway [PeerConnection] against [iceServerUrls] (public STUN by
 * default), gather ICE candidates, and classify the transport plus a rough RTT
 * from the time-to-first server-reflexive candidate. No media, no session.
 *
 * Uses LiveKit's bundled `livekit.org.webrtc` so it shares the native library
 * LiveKit already initializes. The factory + peer connection are created and
 * disposed per call. Any failure degrades cleanly to [ConnectivityTransport.FAILED].
 */
internal suspend fun gatherIceCandidates(
    context: Context,
    iceServerUrls: List<String>,
    timeoutMs: Long,
    logger: Logger,
): ConnectivityMetrics = withContext(Dispatchers.Default) {
    // Defensive + idempotent: a no-op if LiveKit already loaded the native lib,
    // but required if checkConnectivity() runs before any Room exists.
    runCatching {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setNativeLibraryName("lkjingle_peerconnection_so")
                .createInitializationOptions(),
        )
    }.onFailure {
        logger.warn("preflight: native init failed", mapOf("error" to it.message))
    }

    val factory = try {
        // Data-channel-only PC needs no audio device module or codec factories.
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    } catch (e: Exception) {
        logger.warn("preflight: factory creation failed", mapOf("error" to e.message))
        return@withContext ConnectivityMetrics(ConnectivityTransport.FAILED, null)
    }

    var pc: PeerConnection? = null
    try {
        val iceServers = iceServerUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

        val startNs = System.nanoTime()
        val sawSrflx = AtomicBoolean(false)
        // host or relay candidate — proves we gathered *something* but not direct UDP egress.
        val sawOther = AtomicBoolean(false)
        val firstSrflxNs = AtomicLong(0L)
        val result = CompletableDeferred<ConnectivityMetrics>()

        fun buildMetrics(): ConnectivityMetrics {
            val first = firstSrflxNs.get()
            val rttMs = if (first != 0L) (first - startNs) / 1_000_000L else null
            // srflx → confirmed UDP egress; any other candidate but no srflx → STUN
            // unreachable over UDP, the session will need TURN; nothing at all → failed.
            val transport = when {
                sawSrflx.get() -> ConnectivityTransport.UDP
                sawOther.get() -> ConnectivityTransport.RELAY
                else -> ConnectivityTransport.FAILED
            }
            return ConnectivityMetrics(transport, rttMs)
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null || candidate.sdp.isEmpty()) {
                    result.complete(buildMetrics()) // end-of-candidates
                    return
                }
                if (candidateType(candidate) == "srflx") {
                    sawSrflx.set(true)
                    firstSrflxNs.compareAndSet(0L, System.nanoTime())
                } else {
                    sawOther.set(true)
                }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) result.complete(buildMetrics())
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        pc = factory.createPeerConnection(rtcConfig, observer)
            ?: return@withContext ConnectivityMetrics(ConnectivityTransport.FAILED, null)

        // A data channel gives us an m-section so ICE gathering actually runs,
        // without needing camera permission or any media tracks.
        pc.createDataChannel("decart-preflight", DataChannel.Init())

        val peer = pc
        peer.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description != null) peer.setLocalDescription(NoopSdpObserver, description)
                }

                override fun onCreateFailure(error: String?) {
                    logger.warn("preflight: createOffer failed", mapOf("error" to error))
                    result.complete(ConnectivityMetrics(ConnectivityTransport.FAILED, null))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints(),
        )

        // Timeout → use whatever we gathered so far.
        withTimeoutOrNull(timeoutMs) { result.await() } ?: buildMetrics()
    } catch (e: CancellationException) {
        throw e // caller cancelled — propagate, don't report it as a failed probe
    } catch (e: Exception) {
        logger.warn("preflight: connectivity probe threw", mapOf("error" to e.message))
        ConnectivityMetrics(ConnectivityTransport.FAILED, null)
    } finally {
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { factory.dispose() }
    }
}
