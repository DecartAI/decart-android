package ai.decart.sdk.realtime

import ai.decart.sdk.Logger
import ai.decart.sdk.RealtimeModels
import org.junit.Assert.*
import org.junit.Test

class RealTimeClientUrlTest {

    private val baseUrl = "wss://api.decart.ai"
    private val apiKey = "test-key"
    private val model = RealtimeModels.LUCY_2_1

    @Test
    fun `omits resolution query param when null`() {
        val url = buildWebrtcUrl(baseUrl, model, apiKey, resolution = null)
        assertFalse(url.contains("resolution="))
        assertEquals("wss://api.decart.ai/v1/stream?api_key=test-key&model=lucy-2.1", url)
    }

    @Test
    fun `appends resolution=720p when P720`() {
        val url = buildWebrtcUrl(baseUrl, model, apiKey, resolution = Resolution.P720)
        assertTrue(url.contains("&resolution=720p"))
    }

    @Test
    fun `appends resolution=1080p when P1080`() {
        val url = buildWebrtcUrl(baseUrl, model, apiKey, resolution = Resolution.P1080)
        assertTrue(url.contains("&resolution=1080p"))
    }

    @Test
    fun `omits pixel_latency by default and appends it when debugQuality`() {
        assertFalse(buildWebrtcUrl(baseUrl, model, apiKey, resolution = null).contains("pixel_latency"))
        val url = buildWebrtcUrl(baseUrl, model, apiKey, resolution = null, debugQuality = true)
        assertTrue(url.contains("&pixel_latency=1"))
    }

    @Test
    fun `omits speed query param when null`() {
        val url = buildWebrtcUrl(baseUrl, RealtimeModels.LUCY_2_5, apiKey, resolution = null)
        assertFalse(url.contains("speed"))
        assertEquals("wss://api.decart.ai/v1/stream?api_key=test-key&model=lucy-2.5", url)
    }

    @Test
    fun `appends speed=fast exactly once when FAST`() {
        val url = buildWebrtcUrl(baseUrl, RealtimeModels.LUCY_2_5, apiKey, resolution = null, speed = Speed.FAST)
        assertEquals("wss://api.decart.ai/v1/stream?api_key=test-key&model=lucy-2.5&speed=fast", url)
        assertEquals(1, url.windowed("speed=".length).count { it == "speed=" })
    }

    @Test
    fun `speed composes with resolution and debugQuality`() {
        val url = buildWebrtcUrl(
            baseUrl,
            RealtimeModels.LUCY_VTON_3_5,
            apiKey,
            resolution = Resolution.P1080,
            debugQuality = true,
            speed = Speed.FAST,
        )
        assertEquals(
            "wss://api.decart.ai/v1/stream?api_key=test-key&model=lucy-vton-3.5&resolution=1080p&pixel_latency=1&speed=fast",
            url,
        )
    }

    @Test
    fun `speed is still sent for models without the capability`() {
        // The server ignores it (standard tier, standard price); the SDK only warns.
        val url = buildWebrtcUrl(baseUrl, RealtimeModels.LUCY_2_1, apiKey, resolution = null, speed = Speed.FAST)
        assertTrue(url.endsWith("&speed=fast"))
    }

    @Test
    fun `api key and model name are url-encoded`() {
        val weirdKey = "key with spaces&special=chars"
        val url = buildWebrtcUrl(baseUrl, model, weirdKey, resolution = null)
        assertTrue(url.contains("api_key=key+with+spaces%26special%3Dchars"))
        assertTrue(url.contains("&model=lucy-2.1"))
    }

    @Test
    fun `warns once when speed is set for a model without the capability`() {
        val logger = RecordingLogger()
        warnIfSpeedUnsupported(RealtimeModels.LUCY_2_1, Speed.FAST, logger)
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings.single().contains("lucy-2.1"))
        assertTrue(logger.warnings.single().contains("speed=fast"))
    }

    @Test
    fun `does not warn when speed is unset or supported by the model`() {
        val logger = RecordingLogger()
        warnIfSpeedUnsupported(RealtimeModels.LUCY_2_1, null, logger)
        warnIfSpeedUnsupported(RealtimeModels.LUCY_2_5, Speed.FAST, logger)
        warnIfSpeedUnsupported(RealtimeModels.LUCY_LATEST, Speed.FAST, logger)
        warnIfSpeedUnsupported(RealtimeModels.LUCY_VTON_3_5, Speed.FAST, logger)
        warnIfSpeedUnsupported(RealtimeModels.LUCY_VTON_LATEST, Speed.FAST, logger)
        assertTrue(logger.warnings.isEmpty())
    }

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()
        override fun debug(message: String, data: Map<String, Any?>?) {}
        override fun info(message: String, data: Map<String, Any?>?) {}
        override fun warn(message: String, data: Map<String, Any?>?) { warnings += message }
        override fun error(message: String, data: Map<String, Any?>?) {}
    }
}
