package ai.decart.sdk.realtime

import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.RealtimeModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins every constructor shape that shipped in an earlier release so a new
 * option can't silently drop a JVM constructor precompiled consumers rely on.
 * Each shape is exercised both as a plain positional Kotlin call and by looking
 * up the exact JVM descriptor via reflection.
 */
class ConnectOptionsCompatTest {

    private val model = RealtimeModels.LUCY_2_1
    private val remote: (RealtimeMediaStream) -> Unit = {}
    private val quality: (ConnectionQualityReport) -> Unit = {}

    @Test
    fun `pre-speed primary shape (12 args ending in onRemoteStream) still exists`() {
        val options = ConnectOptions(
            model,
            null,
            null,
            Resolution.P720,
            RealtimeConfiguration(),
            true,
            false,
            FacingMode.BACK,
            MirrorMode.OFF,
            true,
            quality,
            remote,
        )
        assertEquals(Resolution.P720, options.resolution)
        assertEquals(MirrorMode.OFF, options.mirror)
        assertTrue(options.debugQuality)
        assertNotNull(options.onConnectionQuality)
        assertNotNull(options.onRemoteStream)
        assertNull(options.speed)

        assertNotNull(
            ConnectOptions::class.java.getConstructor(
                RealtimeModel::class.java,
                InitialPrompt::class.java,
                String::class.java,
                Resolution::class.java,
                RealtimeConfiguration::class.java,
                Boolean::class.java,
                Boolean::class.java,
                FacingMode::class.java,
                MirrorMode::class.java,
                Boolean::class.java,
                Function1::class.java,
                Function1::class.java,
            ),
        )
    }

    @Test
    fun `pre-debugQuality shape (10 args ending in mirror, onRemoteStream) still exists`() {
        val options = ConnectOptions(
            model,
            null,
            null,
            null,
            RealtimeConfiguration(),
            true,
            false,
            FacingMode.FRONT,
            MirrorMode.ON,
            remote,
        )
        assertEquals(MirrorMode.ON, options.mirror)
        assertFalse(options.debugQuality)
        assertNull(options.speed)
        assertNotNull(options.onRemoteStream)

        assertNotNull(
            ConnectOptions::class.java.getConstructor(
                RealtimeModel::class.java,
                InitialPrompt::class.java,
                String::class.java,
                Resolution::class.java,
                RealtimeConfiguration::class.java,
                Boolean::class.java,
                Boolean::class.java,
                FacingMode::class.java,
                MirrorMode::class.java,
                Function1::class.java,
            ),
        )
    }

    @Test
    fun `pre-mirror shape (9 args ending in facing, onRemoteStream) still exists`() {
        val options = ConnectOptions(
            model,
            null,
            null,
            null,
            RealtimeConfiguration(),
            true,
            false,
            FacingMode.FRONT,
            remote,
        )
        assertEquals(MirrorMode.AUTO, options.mirror)
        assertNull(options.speed)
        assertNotNull(options.onRemoteStream)
    }

    @Test
    fun `new primary shape keeps onRemoteStream last after speed`() {
        val options = ConnectOptions(
            model,
            null,
            null,
            null,
            RealtimeConfiguration(),
            true,
            false,
            FacingMode.FRONT,
            MirrorMode.AUTO,
            false,
            null,
            Speed.FAST,
            remote,
        )
        assertEquals(Speed.FAST, options.speed)
        assertNotNull(options.onRemoteStream)
        assertNotNull(ConnectOptions(model) {}.onRemoteStream)
    }

    @Test
    fun `RealtimeModel keeps its pre-supportedSpeeds 5-arg JVM constructor`() {
        val custom = RealtimeModel("custom", "/v1/stream", 30, 1280, 720)
        assertTrue(custom.supportedSpeeds.isEmpty())
        assertNotNull(
            RealtimeModel::class.java.getConstructor(
                String::class.java,
                String::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
            ),
        )
    }
}
