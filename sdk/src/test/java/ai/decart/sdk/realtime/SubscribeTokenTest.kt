package ai.decart.sdk.realtime

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalEncodingApi::class)
class SubscribeTokenTest {

    @Test
    fun `encodes a simple room name to base64 JSON matching JS btoa`() {
        val token = encodeSubscribeToken("foo-room")
        // btoa(JSON.stringify({room_name: "foo-room"})) == "eyJyb29tX25hbWUiOiJmb28tcm9vbSJ9"
        assertEquals("eyJyb29tX25hbWUiOiJmb28tcm9vbSJ9", token)
    }

    @Test
    fun `decodes back to a JSON object containing the room name`() {
        val token = encodeSubscribeToken("inference-room-12345")
        val json = String(Base64.decode(token), Charsets.UTF_8)
        assertEquals("""{"room_name":"inference-room-12345"}""", json)
    }

    @Test
    fun `escapes quotes inside the room name`() {
        val token = encodeSubscribeToken("weird\"name")
        val json = String(Base64.decode(token), Charsets.UTF_8)
        assertEquals("""{"room_name":"weird\"name"}""", json)
    }

    @Test
    fun `escapes backslashes inside the room name`() {
        val token = encodeSubscribeToken("path\\to\\room")
        val json = String(Base64.decode(token), Charsets.UTF_8)
        assertEquals("""{"room_name":"path\\to\\room"}""", json)
    }
}
