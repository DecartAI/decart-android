package ai.decart.sdk.realtime

import io.livekit.android.room.track.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeConfigurationTest {

    @Test
    fun `default preferred video codec is vp8`() {
        assertEquals(
            VideoCodec.VP8.codecName,
            RealtimeConfiguration.VideoConfig.DEFAULT_PREFERRED_CODEC,
        )
        val defaultVideoConfig = RealtimeConfiguration().media.video
        assertEquals(
            VideoCodec.VP8.codecName,
            defaultVideoConfig.preferredCodec,
        )
        assertNull(defaultVideoConfig.backupCodec)
    }
}
