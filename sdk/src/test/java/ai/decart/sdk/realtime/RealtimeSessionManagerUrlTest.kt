@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.decart.sdk.realtime

import ai.decart.sdk.RealtimeModels
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Pins that the signaling URL built once in [RealTimeClient.connect] (including
 * `speed=fast`) is the URL the session manager dials on the first attempt AND
 * on every automatic re-dial: [RealtimeSessionConfig.signalingUrl] is immutable
 * and every dial site reads it verbatim.
 */
class RealtimeSessionManagerUrlTest {

    @Test
    fun `first dial and retry re-dial both keep speed=fast`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val server = MockWebServer()
        val serverSockets = CopyOnWriteArrayList<WebSocket>()
        // Accept every WebSocket upgrade but never answer livekit_join, so each
        // attempt times out and the manager re-dials through runWithRetry.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSockets += webSocket
                    }
                })
        }
        server.start()
        try {
            val baseUrl = server.url("/").toString().removeSuffix("/").replace("http://", "ws://")
            val url = buildWebrtcUrl(
                baseUrl = baseUrl,
                model = RealtimeModels.LUCY_2_5,
                apiKey = "test-key",
                resolution = null,
                speed = Speed.FAST,
            )
            assertEquals("$baseUrl/v1/stream?api_key=test-key&model=lucy-2.5&speed=fast", url)

            val manager = RealtimeSessionManager(
                RealtimeSessionConfig(
                    context = hostJvmContext(),
                    signalingUrl = url,
                    model = RealtimeModels.LUCY_2_5,
                    realtimeConfiguration = RealtimeConfiguration(
                        connection = RealtimeConfiguration.ConnectionConfig(connectionTimeoutMs = 200L),
                    ),
                    publishCamera = false,
                    onLocalStream = {},
                    onRemoteStream = {},
                    onConnectionStateChange = {},
                    onSessionStarted = {},
                    onGenerationTick = {},
                    onGenerationEnded = {},
                    onQueuePosition = {},
                    onError = { _, _ -> },
                ),
            )
            val connect = async(Dispatchers.Default) {
                runCatching { manager.connect(localStream = null) }
            }
            try {
                val first = server.takeRequest(5, TimeUnit.SECONDS)
                assertNotNull("first dial did not reach the signaling server", first)
                // The retry re-dial happens after the join timeout + 1s backoff.
                val second = server.takeRequest(10, TimeUnit.SECONDS)
                assertNotNull("re-dial did not reach the signaling server", second)

                listOf(first!!, second!!).forEachIndexed { index, request ->
                    val path = request.path ?: ""
                    // SignalingChannel appends `&user_agent=...` after the URL it was handed.
                    assertTrue(
                        "dial #${index + 1} path was $path",
                        path.startsWith("/v1/stream?api_key=test-key&model=lucy-2.5&speed=fast&user_agent="),
                    )
                    assertEquals("dial #${index + 1} speed param count", 1, path.split("speed=").size - 1)
                    assertTrue("dial #${index + 1} is a websocket upgrade", request.getHeader("Upgrade").equals("websocket", ignoreCase = true))
                }
            } finally {
                manager.cleanup()
                connect.await()
            }
        } finally {
            serverSockets.forEach { runCatching { it.close(1000, "test complete") } }
            server.shutdown()
            Dispatchers.resetMain()
        }
    }

    /**
     * The manager only stores the [Context] for LiveKit/camera setup, which this
     * test never reaches (publishCamera=false, join never answered). android.jar
     * stubs throw from every constructor, so allocate a [ContextWrapper] without
     * running one.
     */
    private fun hostJvmContext(): Context {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ContextWrapper::class.java) as Context
    }
}
