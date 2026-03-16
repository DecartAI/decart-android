package ai.decart.sdk.queue

import ai.decart.sdk.NoopLogger
import ai.decart.sdk.VideoModels
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class QueueClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: QueueClient

    private val model = VideoModels.LUCY_2_V2V

    private fun input(vararg bytes: Byte = byteArrayOf(1)) = VideoEditInput(
        prompt = "",
        data = FileInput.fromBytes(bytes, "video/mp4"),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = QueueClient(
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            logger = NoopLogger,
            contentResolver = null,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.release()
    }

    // -- submit ----------------------------------------------------------

    @Test
    fun `submit parses response and sends correct headers`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val response = client.submit(model, input())

        assertEquals("j-1", response.jobId)
        assertEquals(JobStatus.PENDING, response.status)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.startsWith("/v1/jobs/"))
        assertEquals("test-key", req.getHeader("X-API-KEY"))
        assertTrue(req.getHeader("User-Agent")!!.startsWith("decart-android-sdk/"))
    }

    @Test
    fun `submit includes multipart form fields`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val input = VideoEditInput(
            prompt = "anime style",
            data = FileInput.fromBytes(byteArrayOf(10, 20), "video/mp4"),
            seed = 42,
            resolution = "720p",
            enhancePrompt = false,
        )
        client.submit(model, input)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("prompt missing", body.contains("anime style"))
        assertTrue("seed missing", body.contains("42"))
        assertTrue("resolution missing", body.contains("720p"))
        assertTrue("enhance_prompt missing", body.contains("false"))
    }

    @Test
    fun `submit throws QueueSubmitException on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))

        try {
            client.submit(model, input())
            fail()
        } catch (e: QueueSubmitException) {
            assertEquals(400, e.statusCode)
        }
    }

    @Test
    fun `submit throws QueueSubmitException on network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            client.submit(model, input())
            fail()
        } catch (e: QueueSubmitException) {
            assertNull(e.statusCode)
        }
    }

    // -- status ----------------------------------------------------------

    @Test
    fun `status parses response and hits correct endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"processing"}"""))

        val response = client.status("j-1")

        assertEquals("j-1", response.jobId)
        assertEquals(JobStatus.PROCESSING, response.status)
        assertEquals("/v1/jobs/j-1", server.takeRequest().path)
    }

    @Test
    fun `status throws QueueStatusException on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            client.status("nope")
            fail()
        } catch (e: QueueStatusException) {
            assertEquals(404, e.statusCode)
        }
    }

    @Test
    fun `status throws QueueStatusException on network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            client.status("j-1")
            fail()
        } catch (e: QueueStatusException) {
            assertNull(e.statusCode)
        }
    }

    // -- result ----------------------------------------------------------

    @Test
    fun `result returns raw bytes from correct endpoint`() = runTest {
        val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val data = client.result("j-1")

        assertArrayEquals(bytes, data)
        assertEquals("/v1/jobs/j-1/content", server.takeRequest().path)
    }

    @Test
    fun `result throws QueueResultException on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            client.result("j-1")
            fail()
        } catch (e: QueueResultException) {
            assertEquals(404, e.statusCode)
        }
    }

    @Test
    fun `result throws QueueResultException on network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            client.result("j-1")
            fail()
        } catch (e: QueueResultException) {
            assertNull(e.statusCode)
        }
    }

    // -- submitAndPoll ---------------------------------------------------

    @Test
    fun `submitAndPoll polls until completed and downloads result`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"processing"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"completed"}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(42))))

        val statuses = mutableListOf<JobStatus>()
        val result = client.submitAndPoll(model, input(), onStatusChange = { statuses.add(it.status) })

        assertTrue(result is QueueJobResult.Completed)
        assertArrayEquals(byteArrayOf(42), (result as QueueJobResult.Completed).data)
        assertEquals(
            listOf(JobStatus.PENDING, JobStatus.PROCESSING, JobStatus.COMPLETED),
            statuses,
        )
    }

    @Test
    fun `submitAndPoll returns Failed without throwing`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"failed"}"""))

        val result = client.submitAndPoll(model, input())

        assertTrue(result is QueueJobResult.Failed)
        assertEquals("j-1", (result as QueueJobResult.Failed).jobId)
    }

    // -- submitAndObserve ------------------------------------------------

    @Test
    fun `submitAndObserve emits InProgress then Completed`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"processing"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"completed"}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(99))))

        val emissions = client.submitAndObserve(model, input()).toList()

        assertEquals(3, emissions.size)
        assertTrue(emissions[0] is QueueJobResult.InProgress)
        assertEquals(JobStatus.PENDING, (emissions[0] as QueueJobResult.InProgress).status)
        assertTrue(emissions[1] is QueueJobResult.InProgress)
        assertEquals(JobStatus.PROCESSING, (emissions[1] as QueueJobResult.InProgress).status)
        assertTrue(emissions[2] is QueueJobResult.Completed)
    }

    @Test
    fun `submitAndObserve emits InProgress then Failed`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"failed"}"""))

        val emissions = client.submitAndObserve(model, input()).toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions[0] is QueueJobResult.InProgress)
        assertTrue(emissions[1] is QueueJobResult.Failed)
    }

    // -- FileInput.FromFile (no ContentResolver needed) ------------------

    @Test
    fun `submit with FromFile for nonexistent file throws InvalidInputException`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val input = VideoEditInput(
            prompt = "",
            data = FileInput.fromFile(java.io.File("/nonexistent/video.mp4")),
        )

        try {
            client.submit(model, input)
            fail()
        } catch (e: InvalidInputException) {
            assertTrue(e.message!!.contains("does not exist"))
        }
    }

    // -- VideoRestyleInput validation ------------------------------------

    @Test
    fun `VideoRestyleInput requires prompt or referenceImage`() {
        try {
            VideoRestyleInput(data = FileInput.fromBytes(byteArrayOf(1), "video/mp4"))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("prompt or referenceImage"))
        }
    }

    @Test
    fun `VideoRestyleInput rejects both prompt and referenceImage`() {
        try {
            VideoRestyleInput(
                data = FileInput.fromBytes(byteArrayOf(1), "video/mp4"),
                prompt = "test",
                referenceImage = FileInput.fromBytes(byteArrayOf(2), "image/png"),
            )
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mutually exclusive"))
        }
    }

    @Test
    fun `VideoRestyleInput rejects enhancePrompt with referenceImage`() {
        try {
            VideoRestyleInput(
                data = FileInput.fromBytes(byteArrayOf(1), "video/mp4"),
                referenceImage = FileInput.fromBytes(byteArrayOf(2), "image/png"),
                enhancePrompt = true,
            )
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("enhancePrompt"))
        }
    }

    // -- VideoRestyleInput toFormFields -----------------------------------

    @Test
    fun `VideoRestyleInput with prompt produces correct fields`() {
        val input = VideoRestyleInput(
            data = FileInput.fromBytes(byteArrayOf(1), "video/mp4"),
            prompt = "watercolor",
            seed = 7,
            enhancePrompt = true,
        )
        val fields = input.toFormFields()
        assertEquals("watercolor", fields["prompt"])
        assertNull(fields["reference_image"])
        assertEquals("7", fields["seed"])
        assertEquals("true", fields["enhance_prompt"])
        assertTrue(fields["data"] is FileInput)
    }

    @Test
    fun `VideoRestyleInput with referenceImage produces correct fields`() {
        val refImage = FileInput.fromBytes(byteArrayOf(2), "image/png")
        val input = VideoRestyleInput(
            data = FileInput.fromBytes(byteArrayOf(1), "video/mp4"),
            referenceImage = refImage,
        )
        val fields = input.toFormFields()
        assertNull(fields["prompt"])
        assertEquals(refImage, fields["reference_image"])
        assertTrue(fields["data"] is FileInput)
    }

    // -- TextToVideoInput toFormFields ------------------------------------

    @Test
    fun `TextToVideoInput produces correct fields with orientation`() {
        val input = TextToVideoInput(
            prompt = "a cat",
            seed = 99,
            orientation = "portrait",
            enhancePrompt = false,
        )
        val fields = input.toFormFields()
        assertEquals("a cat", fields["prompt"])
        assertEquals("99", fields["seed"])
        assertEquals("portrait", fields["orientation"])
        assertEquals("false", fields["enhance_prompt"])
        assertNull(fields["data"])
    }

    // -- ImageToVideoInput toFormFields -----------------------------------

    @Test
    fun `ImageToVideoInput produces correct fields`() {
        val data = FileInput.fromBytes(byteArrayOf(1), "image/png")
        val input = ImageToVideoInput(
            prompt = "zoom in",
            data = data,
            seed = 5,
        )
        val fields = input.toFormFields()
        assertEquals("zoom in", fields["prompt"])
        assertEquals(data, fields["data"])
        assertEquals("5", fields["seed"])
    }

    // -- MotionVideoInput validation + fields -----------------------------

    @Test
    fun `MotionVideoInput rejects less than 2 trajectory points`() {
        try {
            MotionVideoInput(
                data = FileInput.fromBytes(byteArrayOf(1), "image/png"),
                trajectory = listOf(TrajectoryPoint(0, 0.5f, 0.5f)),
            )
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least 2"))
        }
    }

    @Test
    fun `MotionVideoInput trajectory serializes as JSON in form fields`() {
        val input = MotionVideoInput(
            data = FileInput.fromBytes(byteArrayOf(1), "image/png"),
            trajectory = listOf(
                TrajectoryPoint(0, 0.5f, 0.5f),
                TrajectoryPoint(14, 0.8f, 0.3f),
            ),
            seed = 42,
        )
        val fields = input.toFormFields()
        val json = fields["trajectory"] as String
        assertTrue(json.startsWith("["))
        assertTrue(json.contains("\"frame\":0"))
        assertTrue(json.contains("\"x\":0.5"))
        assertTrue(json.contains("\"frame\":14"))
        assertEquals("42", fields["seed"])
        assertNull(fields["prompt"])
    }

    // -- upload progress --------------------------------------------------

    @Test
    fun `submit calls onUploadProgress with increasing bytesWritten`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        client.submit(model, input(1, 2, 3, 4, 5)) { bytesWritten, totalBytes ->
            progressUpdates.add(bytesWritten to totalBytes)
        }

        assertTrue("Expected at least one progress callback", progressUpdates.isNotEmpty())
        // bytesWritten should be monotonically increasing
        for (i in 1 until progressUpdates.size) {
            assertTrue(
                "bytesWritten should increase",
                progressUpdates[i].first >= progressUpdates[i - 1].first,
            )
        }
        // Last callback should have bytesWritten == totalBytes
        val last = progressUpdates.last()
        assertEquals(last.second, last.first)
        // totalBytes should be consistent
        val totalBytes = progressUpdates.first().second
        assertTrue("totalBytes should be positive", totalBytes > 0)
        progressUpdates.forEach { assertEquals(totalBytes, it.second) }
    }

    @Test
    fun `submit without onUploadProgress still works`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val response = client.submit(model, input())

        assertEquals("j-1", response.jobId)
    }

    @Test
    fun `submitAndPoll calls onUploadProgress`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"completed"}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(42))))

        var progressCalled = false
        client.submitAndPoll(
            model = model,
            input = input(1, 2, 3),
            onUploadProgress = { _, _ -> progressCalled = true },
        )

        assertTrue("onUploadProgress should have been called", progressCalled)
    }

    @Test
    fun `submitAndObserve calls onUploadProgress`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"completed"}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(99))))

        var progressCalled = false
        client.submitAndObserve(
            model = model,
            input = input(1, 2, 3),
            onUploadProgress = { _, _ -> progressCalled = true },
        ).toList()

        assertTrue("onUploadProgress should have been called", progressCalled)
    }

    @Test
    fun `upload progress reports totalBytes as -1 for InputStream input`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val streamInput = VideoEditInput(
            prompt = "test",
            data = FileInput.fromInputStream(byteArrayOf(1, 2, 3).inputStream(), "video/mp4"),
        )

        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        client.submit(model, streamInput) { bytesWritten, totalBytes ->
            progressUpdates.add(bytesWritten to totalBytes)
        }

        assertTrue("Expected progress callbacks", progressUpdates.isNotEmpty())
        progressUpdates.forEach { (_, totalBytes) ->
            assertEquals("totalBytes should be -1 for unknown length", -1L, totalBytes)
        }
    }

    @Test
    fun `upload progress fires even when server returns error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))

        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        try {
            client.submit(model, input(1, 2, 3)) { bytesWritten, totalBytes ->
                progressUpdates.add(bytesWritten to totalBytes)
            }
            fail("Should have thrown")
        } catch (_: QueueSubmitException) {
            // expected
        }

        assertTrue("Progress should fire before error response is read", progressUpdates.isNotEmpty())
    }

    @Test
    fun `upload progress bytesWritten never exceeds totalBytes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        // Use a larger payload to get multiple progress callbacks
        val largePayload = ByteArray(8192) { it.toByte() }
        val largeInput = VideoEditInput(
            prompt = "large upload",
            data = FileInput.fromBytes(largePayload, "video/mp4"),
        )

        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        client.submit(model, largeInput) { bytesWritten, totalBytes ->
            progressUpdates.add(bytesWritten to totalBytes)
        }

        assertTrue("Expected multiple progress callbacks", progressUpdates.size > 1)
        val totalBytes = progressUpdates.first().second
        assertTrue("totalBytes should be positive", totalBytes > 0)
        progressUpdates.forEach { (bytesWritten, total) ->
            assertEquals("totalBytes should be consistent", totalBytes, total)
            assertTrue("bytesWritten ($bytesWritten) should not exceed totalBytes ($total)", bytesWritten <= total)
        }
        // First should be partial, last should be complete
        assertTrue("First callback should be partial", progressUpdates.first().first < totalBytes)
        assertEquals("Last callback should equal totalBytes", totalBytes, progressUpdates.last().first)
    }

    @Test
    fun `upload progress tracks entire multipart body with multiple files`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val videoBytes = ByteArray(4096) { it.toByte() }
        val refBytes = ByteArray(2048) { it.toByte() }
        val multiFileInput = VideoEditInput(
            prompt = "with ref",
            data = FileInput.fromBytes(videoBytes, "video/mp4"),
            referenceImage = FileInput.fromBytes(refBytes, "image/png"),
        )

        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        client.submit(model, multiFileInput) { bytesWritten, totalBytes ->
            progressUpdates.add(bytesWritten to totalBytes)
        }

        assertTrue("Expected progress callbacks", progressUpdates.isNotEmpty())
        val totalBytes = progressUpdates.first().second
        // Total should include both files plus multipart overhead
        assertTrue(
            "totalBytes ($totalBytes) should exceed combined file sizes (${videoBytes.size + refBytes.size})",
            totalBytes > videoBytes.size + refBytes.size,
        )
        assertEquals("Final bytesWritten should equal totalBytes", totalBytes, progressUpdates.last().first)
    }

    @Test
    fun `upload progress works with FromFile input`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val tempFile = java.io.File.createTempFile("test-upload", ".mp4")
        try {
            tempFile.writeBytes(ByteArray(1024) { it.toByte() })
            val fileInput = VideoEditInput(
                prompt = "file test",
                data = FileInput.fromFile(tempFile),
            )

            val progressUpdates = mutableListOf<Pair<Long, Long>>()
            client.submit(model, fileInput) { bytesWritten, totalBytes ->
                progressUpdates.add(bytesWritten to totalBytes)
            }

            assertTrue("Expected progress callbacks", progressUpdates.isNotEmpty())
            assertTrue("totalBytes should be positive", progressUpdates.first().second > 0)
            assertEquals(
                "Final bytesWritten should equal totalBytes",
                progressUpdates.last().second,
                progressUpdates.last().first,
            )
        } finally {
            tempFile.delete()
        }
    }

    // -- submit with new input types -------------------------------------

    @Test
    fun `submit with TextToVideoInput sends no data field`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val input = TextToVideoInput(prompt = "sunset", orientation = "landscape")
        client.submit(VideoModels.LUCY_PRO_T2V, input)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("prompt missing", body.contains("sunset"))
        assertTrue("orientation missing", body.contains("landscape"))
    }

    @Test
    fun `submit with MotionVideoInput sends trajectory JSON`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id":"j-1","status":"pending"}"""))

        val input = MotionVideoInput(
            data = FileInput.fromBytes(byteArrayOf(10), "image/png"),
            trajectory = listOf(
                TrajectoryPoint(0, 0.1f, 0.2f),
                TrajectoryPoint(7, 0.9f, 0.8f),
            ),
        )
        client.submit(VideoModels.LUCY_MOTION, input)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("trajectory missing", body.contains("frame"))
        assertTrue("trajectory missing x", body.contains("0.1"))
    }

}
