package ai.decart.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoModelsTest {
    @Test
    fun `video models match JS SDK registry`() {
        assertEquals(8, VideoModels.all.size)
        assertEquals(VideoModels.LUCY_2_1, VideoModels.fromName("lucy-2.1"))
        assertEquals(20, VideoModels.LUCY_2_1.fps)
        assertEquals(1280, VideoModels.LUCY_2_1.width)
        assertEquals(720, VideoModels.LUCY_2_1.height)
        assertEquals(ModelInputType.VIDEO_EDIT, VideoModels.LUCY_2_1.inputType)
    }

    @Test
    fun `lucy-2_5 matches JS SDK registry`() {
        assertEquals(VideoModels.LUCY_2_5, VideoModels.fromName("lucy-2.5"))
        assertEquals("/v1/jobs/lucy-2.5", VideoModels.LUCY_2_5.jobsUrlPath)
        assertEquals(20, VideoModels.LUCY_2_5.fps)
        assertEquals(1280, VideoModels.LUCY_2_5.width)
        assertEquals(720, VideoModels.LUCY_2_5.height)
        assertEquals(ModelInputType.VIDEO_EDIT, VideoModels.LUCY_2_5.inputType)
    }

    // lucy-latest resolves server-side to lucy-2.5, so it shares its 720p geometry.
    @Test
    fun `lucy-latest matches JS SDK registry`() {
        assertEquals(VideoModels.LUCY_LATEST, VideoModels.fromName("lucy-latest"))
        assertEquals("/v1/jobs/lucy-latest", VideoModels.LUCY_LATEST.jobsUrlPath)
        assertEquals(20, VideoModels.LUCY_LATEST.fps)
        assertEquals(1280, VideoModels.LUCY_LATEST.width)
        assertEquals(720, VideoModels.LUCY_LATEST.height)
        assertEquals(ModelInputType.VIDEO_EDIT, VideoModels.LUCY_LATEST.inputType)
        assertEquals(VideoModels.LUCY_2_5.width, VideoModels.LUCY_LATEST.width)
        assertEquals(VideoModels.LUCY_2_5.height, VideoModels.LUCY_LATEST.height)
    }

    @Test
    fun `lucy-vton-3_5 matches JS SDK registry`() {
        assertEquals(VideoModels.LUCY_VTON_3_5, VideoModels.fromName("lucy-vton-3.5"))
        assertEquals("/v1/jobs/lucy-vton-3.5", VideoModels.LUCY_VTON_3_5.jobsUrlPath)
        assertEquals(20, VideoModels.LUCY_VTON_3_5.fps)
        assertEquals(1280, VideoModels.LUCY_VTON_3_5.width)
        assertEquals(720, VideoModels.LUCY_VTON_3_5.height)
        assertEquals(ModelInputType.VIDEO_EDIT, VideoModels.LUCY_VTON_3_5.inputType)
    }

    @Test
    fun `lucy-vton-3_6 matches JS SDK registry`() {
        assertEquals(VideoModels.LUCY_VTON_3_6, VideoModels.fromName("lucy-vton-3.6"))
        assertEquals("/v1/jobs/lucy-vton-3.6", VideoModels.LUCY_VTON_3_6.jobsUrlPath)
        assertEquals(20, VideoModels.LUCY_VTON_3_6.fps)
        assertEquals(1280, VideoModels.LUCY_VTON_3_6.width)
        assertEquals(720, VideoModels.LUCY_VTON_3_6.height)
        assertEquals(ModelInputType.VIDEO_EDIT, VideoModels.LUCY_VTON_3_6.inputType)
    }
}
