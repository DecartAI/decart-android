package ai.decart.sdk.realtime

import org.junit.Assert.*
import org.junit.Test

class SignalingMessagesTest {

    // ── Server message parsing ──────────────────────────────────────────

    @Test
    fun `parse ready message`() {
        val msg = SignalingMessageParser.parse("""{"type":"ready"}""")
        assertTrue(msg is ReadyMessage)
    }

    @Test
    fun `parse offer message`() {
        val msg = SignalingMessageParser.parse("""{"type":"offer","sdp":"v=0\r\no=..."}""")
        assertTrue(msg is OfferMessage)
        assertEquals("v=0\r\no=...", (msg as OfferMessage).sdp)
    }

    @Test
    fun `parse answer message`() {
        val msg = SignalingMessageParser.parse("""{"type":"answer","sdp":"v=0\r\na=..."}""")
        assertTrue(msg is AnswerMessage)
        assertEquals("v=0\r\na=...", (msg as AnswerMessage).sdp)
    }

    @Test
    fun `parse ice-candidate message`() {
        val json = """{"type":"ice-candidate","candidate":{"candidate":"candidate:1 1 udp 123 10.0.0.1 5000 typ host","sdpMLineIndex":0,"sdpMid":"0"}}"""
        val msg = SignalingMessageParser.parse(json)
        assertTrue(msg is IceCandidateMessage)
        val ice = msg as IceCandidateMessage
        assertNotNull(ice.candidate)
        assertTrue(ice.candidate!!.candidate.contains("typ host"))
        assertEquals(0, ice.candidate!!.sdpMLineIndex)
        assertEquals("0", ice.candidate!!.sdpMid)
    }

    @Test
    fun `parse ice-candidate with null candidate`() {
        val json = """{"type":"ice-candidate","candidate":null}"""
        val msg = SignalingMessageParser.parse(json)
        assertTrue(msg is IceCandidateMessage)
        assertNull((msg as IceCandidateMessage).candidate)
    }

    @Test
    fun `parse prompt_ack success`() {
        val json = """{"type":"prompt_ack","prompt":"hello","success":true,"error":null}"""
        val msg = SignalingMessageParser.parse(json) as PromptAckMessage
        assertEquals("hello", msg.prompt)
        assertTrue(msg.success)
        assertNull(msg.error)
    }

    @Test
    fun `parse prompt_ack failure`() {
        val json = """{"type":"prompt_ack","prompt":"bad","success":false,"error":"invalid prompt"}"""
        val msg = SignalingMessageParser.parse(json) as PromptAckMessage
        assertFalse(msg.success)
        assertEquals("invalid prompt", msg.error)
    }

    @Test
    fun `parse error message`() {
        val msg = SignalingMessageParser.parse("""{"type":"error","error":"something went wrong"}""")
        assertTrue(msg is ErrorMessage)
        assertEquals("something went wrong", (msg as ErrorMessage).error)
    }

    @Test
    fun `parse set_image_ack success`() {
        val json = """{"type":"set_image_ack","success":true,"error":null}"""
        val msg = SignalingMessageParser.parse(json) as SetImageAckMessage
        assertTrue(msg.success)
    }

    @Test
    fun `parse generation_started`() {
        val msg = SignalingMessageParser.parse("""{"type":"generation_started"}""")
        assertTrue(msg is GenerationStartedMessage)
    }

    @Test
    fun `parse generation_tick`() {
        val msg = SignalingMessageParser.parse("""{"type":"generation_tick","seconds":1.5}""") as GenerationTickMessage
        assertEquals(1.5, msg.seconds, 0.001)
    }

    @Test
    fun `parse generation_ended`() {
        val json = """{"type":"generation_ended","seconds":30.0,"reason":"complete"}"""
        val msg = SignalingMessageParser.parse(json) as GenerationEndedMessage
        assertEquals(30.0, msg.seconds, 0.001)
        assertEquals("complete", msg.reason)
    }

    @Test
    fun `parse session_id`() {
        val json = """{"type":"session_id","session_id":"abc-123","server_ip":"10.0.0.1","server_port":8080}"""
        val msg = SignalingMessageParser.parse(json) as SessionIdMessage
        assertEquals("abc-123", msg.sessionId)
        assertEquals("10.0.0.1", msg.serverIp)
        assertEquals(8080, msg.serverPort)
    }

    @Test
    fun `parse livekit room info`() {
        val json = """{"type":"livekit_room_info","livekit_url":"wss://livekit.example.com","token":"token-123","room_name":"room-123","session_id":"session-123"}"""
        val msg = SignalingMessageParser.parse(json) as LiveKitRoomInfoMessage
        assertEquals("wss://livekit.example.com", msg.liveKitUrl)
        assertEquals("token-123", msg.token)
        assertEquals("room-123", msg.roomName)
        assertEquals("session-123", msg.sessionId)
    }

    @Test
    fun `parse status message`() {
        val msg = SignalingMessageParser.parse("""{"type":"status","status":"waiting"}""") as StatusMessage
        assertEquals("waiting", msg.status)
    }

    @Test
    fun `parse queue position message`() {
        val json = """{"type":"queue_position","queue_position":2,"queue_size":5}"""
        val msg = SignalingMessageParser.parse(json) as QueuePositionMessage
        assertEquals(2, msg.queuePosition)
        assertEquals(5, msg.queueSize)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse unknown type throws`() {
        SignalingMessageParser.parse("""{"type":"unknown_type"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse missing type throws`() {
        SignalingMessageParser.parse("""{"foo":"bar"}""")
    }

    @Test
    fun `parse ignores unknown fields`() {
        val json = """{"type":"ready","extraField":"should be ignored"}"""
        val msg = SignalingMessageParser.parse(json)
        assertTrue(msg is ReadyMessage)
    }

    // ── Client message serialization ────────────────────────────────────

    @Test
    fun `serialize offer message`() {
        val json = SignalingMessageParser.serialize(OfferMessage(sdp = "v=0"))
        assertTrue(json.contains(""""type":"offer""""))
        assertTrue(json.contains(""""sdp":"v=0""""))
    }

    @Test
    fun `serialize answer message`() {
        val json = SignalingMessageParser.serialize(AnswerMessage(sdp = "v=0"))
        assertTrue(json.contains(""""type":"answer""""))
    }

    @Test
    fun `serialize ice-candidate with data`() {
        val msg = IceCandidateMessage(IceCandidateData(
            candidate = "candidate:1 1 udp 123 10.0.0.1 5000 typ host",
            sdpMLineIndex = 0,
            sdpMid = "0"
        ))
        val json = SignalingMessageParser.serialize(msg)
        assertTrue(json.contains(""""type":"ice-candidate""""))
        assertTrue(json.contains("typ host"))
    }

    @Test
    fun `serialize ice-candidate with null`() {
        val json = SignalingMessageParser.serialize(IceCandidateMessage(null))
        assertTrue(json.contains(""""candidate":null"""))
    }

    @Test
    fun `serialize prompt message`() {
        val json = SignalingMessageParser.serialize(PromptMessage(prompt = "hello", enhancePrompt = true))
        assertTrue(json.contains(""""type":"prompt""""))
        assertTrue(json.contains(""""prompt":"hello""""))
        assertTrue(json.contains(""""enhance_prompt":true"""))
    }

    @Test
    fun `serialize set_image message with data`() {
        val json = SignalingMessageParser.serialize(SetImageMessage(
            imageData = "base64data",
            prompt = "test prompt",
            enhancePrompt = true
        ))
        assertTrue(json.contains(""""type":"set_image""""))
        assertTrue(json.contains(""""image_data":"base64data""""))
        assertTrue(json.contains(""""prompt":"test prompt""""))
        assertTrue(json.contains(""""enhance_prompt":true"""))
    }

    @Test
    fun `serialize set_image message with null image`() {
        val json = SignalingMessageParser.serialize(SetImageMessage(imageData = null))
        assertTrue(json.contains(""""image_data":null"""))
        // Should not include optional fields when null
        assertFalse(json.contains(""""prompt""""))
        assertFalse(json.contains(""""enhance_prompt""""))
    }

    @Test
    fun `serialize set_image message for passthrough`() {
        val json = SignalingMessageParser.serialize(SetImageMessage(
            imageData = null,
            prompt = null,
            enhancePrompt = null
        ))
        assertTrue(json.contains(""""type":"set_image""""))
        assertTrue(json.contains(""""image_data":null"""))
    }

    @Test
    fun `serialize livekit join message`() {
        val json = SignalingMessageParser.serialize(LiveKitJoinMessage)
        assertEquals("""{"type":"livekit_join"}""", json)
    }

    // ── Round-trip tests ────────────────────────────────────────────────

    @Test
    fun `round-trip offer`() {
        val original = OfferMessage(sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-")
        val json = SignalingMessageParser.serialize(original)
        val parsed = SignalingMessageParser.parse(json) as OfferMessage
        assertEquals(original.sdp, parsed.sdp)
    }

    @Test
    fun `round-trip answer`() {
        val original = AnswerMessage(sdp = "v=0\r\na=test")
        val json = SignalingMessageParser.serialize(original)
        val parsed = SignalingMessageParser.parse(json) as AnswerMessage
        assertEquals(original.sdp, parsed.sdp)
    }
}
