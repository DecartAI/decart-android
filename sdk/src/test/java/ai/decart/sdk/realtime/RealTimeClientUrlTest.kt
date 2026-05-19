package ai.decart.sdk.realtime

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
    fun `api key and model name are url-encoded`() {
        val weirdKey = "key with spaces&special=chars"
        val url = buildWebrtcUrl(baseUrl, model, weirdKey, resolution = null)
        assertTrue(url.contains("api_key=key+with+spaces%26special%3Dchars"))
        assertTrue(url.contains("&model=lucy-2.1"))
    }
}
