@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.decart.sdk.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SignalingChannelTest {

    @Test
    fun `livekit join bundles initial state`() = runBlocking {
        withSignalingServer { server ->
            val channel = newChannel()
            channel.connect(server.wsUrl(), timeoutMs = 1_000)

            val roomInfo = async {
                channel.sendLiveKitJoin(
                    timeoutMs = 1_000,
                    initialState = InitialState(
                        image = "base64data",
                        prompt = "test prompt",
                        enhance = false,
                    ),
                )
            }
            yield()

            val join = Json.parseToJsonElement(server.messages.receiveWithTimeout()).jsonObject
            val initialState = join.getValue("initial_state").jsonObject

            assertEquals("livekit_join", join.getValue("type").jsonPrimitive.content)
            assertEquals("set_image", initialState.getValue("type").jsonPrimitive.content)
            assertEquals("base64data", initialState.getValue("image_data").jsonPrimitive.content)
            assertEquals("test prompt", initialState.getValue("prompt").jsonPrimitive.content)
            assertEquals("false", initialState.getValue("enhance_prompt").jsonPrimitive.content)

            server.socket.await().send(ROOM_INFO)
            roomInfo.await()
            channel.close()
        }
    }

    @Test
    fun `early bundled prompt ack is buffered until room info arrives`() = runBlocking {
        withSignalingServer { server ->
            val channel = newChannel()
            channel.connect(server.wsUrl(), timeoutMs = 1_000)

            val initialState = InitialState(prompt = "hello", enhance = true)
            val roomInfo = async {
                channel.sendLiveKitJoin(timeoutMs = 1_000, initialState = initialState)
            }
            yield()

            server.messages.receiveWithTimeout()
            server.socket.await().send("""{"type":"prompt_ack","prompt":"hello","success":true,"error":null}""")
            assertFalse("room info must still gate the join", roomInfo.isCompleted)

            server.socket.await().send(ROOM_INFO)
            roomInfo.await()

            channel.awaitInitialStateAck(initialState, timeoutMs = 1_000)
            channel.close()
        }
    }

    @Test
    fun `legacy set image path still sends after room info`() = runBlocking {
        withSignalingServer { server ->
            val channel = newChannel()
            channel.connect(server.wsUrl(), timeoutMs = 1_000)

            val roomInfo = async { channel.sendLiveKitJoin(timeoutMs = 1_000) }
            yield()
            val join = server.messages.receiveWithTimeout()
            assertTrue(join.contains(""""initial_state":null"""))

            server.socket.await().send(ROOM_INFO)
            roomInfo.await()

            val setImage = async {
                channel.setImage(
                    "base64data",
                    SignalingChannel.SetImageOptions(
                        prompt = "legacy prompt",
                        enhance = true,
                        timeout = 1_000,
                    ),
                )
            }
            yield()

            val setImageJson = Json.parseToJsonElement(server.messages.receiveWithTimeout()).jsonObject
            assertEquals("set_image", setImageJson.getValue("type").jsonPrimitive.content)
            assertEquals("base64data", setImageJson.getValue("image_data").jsonPrimitive.content)
            assertEquals("legacy prompt", setImageJson.getValue("prompt").jsonPrimitive.content)

            server.socket.await().send("""{"type":"set_image_ack","success":true,"error":null}""")
            setImage.await()
            channel.close()
        }
    }

    private fun newChannel(): SignalingChannel =
        SignalingChannel(
            onStateChange = {},
            onError = { _, _ -> },
            onClosed = { _, _ -> },
        )

    private suspend fun withSignalingServer(block: suspend (TestSignalingServer) -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val mockServer = MockWebServer()
        val server = TestSignalingServer(mockServer)
        mockServer.enqueue(MockResponse().withWebSocketUpgrade(server.listener))
        mockServer.start()
        try {
            block(server)
        } finally {
            server.closeSocket()
            mockServer.shutdown()
            Dispatchers.resetMain()
        }
    }

    private class TestSignalingServer(private val server: MockWebServer) {
        val messages = LinkedBlockingQueue<String>()
        val socket = CompletableDeferred<WebSocket>()
        @Volatile private var webSocket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@TestSignalingServer.webSocket = webSocket
                socket.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.offer(text)
            }
        }

        fun wsUrl(): String = server.url("/signaling").toString().replace("http://", "ws://")

        fun closeSocket() {
            webSocket?.close(1000, "test complete")
        }
    }

    private fun LinkedBlockingQueue<String>.receiveWithTimeout(): String =
        poll(2, TimeUnit.SECONDS) ?: error("Timed out waiting for websocket message")

    private companion object {
        const val ROOM_INFO =
            """{"type":"livekit_room_info","livekit_url":"wss://livekit.example.com","token":"token-123","room_name":"room-123","session_id":"session-123"}"""
    }
}
