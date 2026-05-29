package ai.decart.sdk.realtime

import ai.decart.sdk.RealtimeModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorModeTest {

    @Test
    fun `auto mirrors front camera only`() {
        assertTrue(MirrorMode.AUTO.shouldMirror(FacingMode.FRONT))
        assertFalse(MirrorMode.AUTO.shouldMirror(FacingMode.BACK))
    }

    @Test
    fun `on and off ignore camera facing`() {
        assertTrue(MirrorMode.ON.shouldMirror(FacingMode.FRONT))
        assertTrue(MirrorMode.ON.shouldMirror(FacingMode.BACK))
        assertFalse(MirrorMode.OFF.shouldMirror(FacingMode.FRONT))
        assertFalse(MirrorMode.OFF.shouldMirror(FacingMode.BACK))
    }

    @Test
    fun `connect options default to auto mirror`() {
        val options = ConnectOptions(model = RealtimeModels.LUCY_2_1)

        assertEquals(MirrorMode.AUTO, options.mirror)
    }

    @Test
    fun `connect options preserve trailing remote stream callback`() {
        val options = ConnectOptions(model = RealtimeModels.LUCY_2_1) {}

        assertEquals(MirrorMode.AUTO, options.mirror)
        assertNotNull(options.onRemoteStream)
    }
}
