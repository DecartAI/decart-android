package ai.decart.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VideoModelsTest {
    @Test
    fun `video models match JS SDK registry`() {
        assertEquals(10, VideoModels.all.size)
        assertEquals(VideoModels.LUCY_VTON_2, VideoModels.fromName("lucy-vton-2"))
        assertEquals(20, VideoModels.LUCY_VTON_2.fps)
        assertEquals(1088, VideoModels.LUCY_VTON_2.width)
        assertEquals(624, VideoModels.LUCY_VTON_2.height)
        assertEquals(ModelInputType.VIDEO_EDIT, VideoModels.LUCY_VTON_2.inputType)
    }

    @Test
    fun `lucy-vton-3 matches JS SDK registry`() {
        assertEquals(VideoModels.LUCY_VTON_3, VideoModels.fromName("lucy-vton-3"))
        assertEquals("/v1/jobs/lucy-vton-3", VideoModels.LUCY_VTON_3.jobsUrlPath)
        assertEquals(20, VideoModels.LUCY_VTON_3.fps)
        assertEquals(1088, VideoModels.LUCY_VTON_3.width)
        assertEquals(624, VideoModels.LUCY_VTON_3.height)
        assertEquals(ModelInputType.VIDEO_EDIT, VideoModels.LUCY_VTON_3.inputType)
    }

    @Test
    fun `deprecated vton aliases are available`() {
        assertNotNull(VideoModels.fromName("lucy-vton"))
        assertNotNull(VideoModels.fromName("lucy-2.1-vton-2"))
    }
}
