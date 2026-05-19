package ai.decart.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VideoModelsTest {
    @Test
    fun `video models match JS SDK registry`() {
        assertEquals(9, VideoModels.all.size)
        assertEquals(VideoModels.LUCY_VTON_2, VideoModels.fromName("lucy-vton-2"))
        assertEquals(20, VideoModels.LUCY_VTON_2.fps)
        assertEquals(1088, VideoModels.LUCY_VTON_2.width)
        assertEquals(624, VideoModels.LUCY_VTON_2.height)
        assertEquals(ModelInputType.VIDEO_EDIT, VideoModels.LUCY_VTON_2.inputType)
    }

    @Test
    fun `deprecated vton aliases are available`() {
        assertNotNull(VideoModels.fromName("lucy-vton"))
        assertNotNull(VideoModels.fromName("lucy-2.1-vton-2"))
    }
}
