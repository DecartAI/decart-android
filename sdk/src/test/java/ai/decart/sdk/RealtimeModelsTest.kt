package ai.decart.sdk

import org.junit.Assert.*
import org.junit.Test

class RealtimeModelsTest {

    @Test
    fun `all models have correct count`() {
        assertEquals(10, RealtimeModels.all.size)
    }

    @Test
    fun `fromName returns correct model for canonical names`() {
        assertEquals(RealtimeModels.LUCY, RealtimeModels.fromName("lucy"))
        assertEquals(RealtimeModels.LUCY_2, RealtimeModels.fromName("lucy-2"))
        assertEquals(RealtimeModels.LUCY_2_1, RealtimeModels.fromName("lucy-2.1"))
        assertEquals(RealtimeModels.LUCY_2_1_VTON, RealtimeModels.fromName("lucy-2.1-vton"))
        assertEquals(RealtimeModels.LUCY_RESTYLE, RealtimeModels.fromName("lucy-restyle"))
        assertEquals(RealtimeModels.LUCY_RESTYLE_2, RealtimeModels.fromName("lucy-restyle-2"))
        assertEquals(RealtimeModels.LIVE_AVATAR, RealtimeModels.fromName("live-avatar"))
    }

    @Test
    fun `fromName returns correct model for latest aliases`() {
        assertEquals(RealtimeModels.LUCY_LATEST, RealtimeModels.fromName("lucy-latest"))
        assertEquals(RealtimeModels.LUCY_VTON_LATEST, RealtimeModels.fromName("lucy-vton-latest"))
        assertEquals(RealtimeModels.LUCY_RESTYLE_LATEST, RealtimeModels.fromName("lucy-restyle-latest"))
    }

    @Test
    fun `fromName returns correct model for deprecated names`() {
        assertNotNull(RealtimeModels.fromName("mirage"))
        assertNotNull(RealtimeModels.fromName("mirage_v2"))
        assertNotNull(RealtimeModels.fromName("lucy_v2v_720p_rt"))
        assertNotNull(RealtimeModels.fromName("lucy_2_rt"))
        assertNotNull(RealtimeModels.fromName("live_avatar"))
    }

    @Test
    fun `fromName returns null for unknown`() {
        assertNull(RealtimeModels.fromName("nonexistent_model"))
    }

    @Test
    fun `all models use v1 stream urlPath`() {
        RealtimeModels.all.forEach { model ->
            assertEquals("/v1/stream", model.urlPath)
        }
    }

    @Test
    fun `model dimensions are valid`() {
        RealtimeModels.all.forEach { model ->
            assertTrue("${model.name} width should be > 0", model.width > 0)
            assertTrue("${model.name} height should be > 0", model.height > 0)
            assertTrue("${model.name} fps should be > 0", model.fps > 0)
        }
    }
}
