# Decart Android SDK

![Platform](https://img.shields.io/badge/platform-Android%20API%2024%2B-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

Android SDK for Decart realtime streaming and batch video generation.

## Features

- Real-time video restyling and editing via WebRTC
- Batch video generation via `/v1/jobs/*` queue APIs
- Built-in realtime and video model registries
- Kotlin coroutines and Flow-based reactive state management
- Observable connection state, errors, and WebRTC stats
- Camera and audio track support

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 2.1+
- Java 17

## Installation

Add the JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.DecartAI:decart-android:0.6.1")
}
```

## Quick Start

```kotlin
import ai.decart.sdk.DecartClient
import ai.decart.sdk.DecartClientConfig
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.InitialPrompt
import ai.decart.sdk.RealtimeModels

val client = DecartClient(context, DecartClientConfig(apiKey = "your-api-key"))
val realtime = client.realtime

// 1. Initialize WebRTC
realtime.initialize(eglBase)

// 2. Connect with a camera track
realtime.connect(
    localVideoTrack = cameraTrack,
    localAudioTrack = null,
    options = ConnectOptions(
        model = RealtimeModels.LUCY_RESTYLE_2,
        onRemoteVideoTrack = { track ->
            // Display the transformed video
            remoteRenderer.addSink(track)
        },
        initialPrompt = InitialPrompt("a cyberpunk cityscape")
    )
)

// 3. Change prompt during session (suspends until the server acks)
try {
    realtime.setPrompt("a sunny beach scene", enhance = true)
} catch (e: Exception) {
    // ack failure, timeout, or websocket disconnect
}

// 4. Disconnect when done
realtime.disconnect()
client.release()
```

### Front-camera mirroring

The SDK can pre-flip the input video horizontally before sending it to the server.
For selfie use cases this means you can render the remote stream as-is, without
applying `SurfaceViewRenderer.setMirror(true)` on the remote view — which would
also flip any server-baked overlay pixels (e.g. watermarks).

Recommended: use the camera helper, which wraps `Camera2Enumerator`, the capturer,
the source, and the track in one call:

```kotlin
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.MirrorMode

realtime.initialize(eglBase)

val cameraTrack = realtime.createCameraVideoTrack(
    facing = FacingMode.FRONT,
    mirror = MirrorMode.AUTO, // OFF | ON | AUTO (AUTO mirrors iff facing == FRONT)
)

realtime.connect(
    localVideoTrack = cameraTrack.track,
    options = ConnectOptions(model = RealtimeModels.LUCY_2_1, onRemoteVideoTrack = { /* ... */ }),
)

// On teardown:
cameraTrack.stop()
```

If you already manage your own camera pipeline, attach the public
`MirrorVideoProcessor` to your `VideoSource` directly:

```kotlin
import ai.decart.sdk.realtime.MirrorVideoProcessor

val source = realtime.createVideoSource()!!
source.setVideoProcessor(MirrorVideoProcessor())
// ...wire your VideoCapturer to source.capturerObserver as usual.
```

When you pre-flip the input, render the remote stream **without**
`setMirror(true)` on the remote `SurfaceViewRenderer` so server-baked overlays
appear correctly oriented.

### Output resolution

Opt into 1080p output for a realtime session (defaults to 720p server-side):

```kotlin
import ai.decart.sdk.realtime.Resolution

realtime.connect(
    localVideoTrack = cameraTrack.track,
    options = ConnectOptions(
        model = RealtimeModels.LUCY_2_1,
        resolution = Resolution.P1080, // default: server-side 720p
        onRemoteVideoTrack = { /* ... */ },
    ),
)
```

### Batch Queue Example (Lucy 2 V2V)

```kotlin
import ai.decart.sdk.DecartClient
import ai.decart.sdk.DecartClientConfig
import ai.decart.sdk.VideoModels
import ai.decart.sdk.queue.FileInput
import ai.decart.sdk.queue.QueueJobResult
import ai.decart.sdk.queue.VideoEditInput

val client = DecartClient(context, DecartClientConfig(apiKey = "your-api-key"))

val input = VideoEditInput(
    prompt = "Cinematic color grade, soft contrast",
    data = FileInput.fromUri(videoUri),                     // required
    referenceImage = FileInput.fromUri(referenceImageUri),  // optional
    seed = 42,
    resolution = "720p",
    enhancePrompt = true,
)

when (val result = client.queue.submitAndPoll(VideoModels.LUCY_2_1, input)) {
    is QueueJobResult.Completed -> {
        // MP4 bytes
        val output = java.io.File(context.cacheDir, "output.mp4")
        output.writeBytes(result.data)
    }
    is QueueJobResult.Failed -> {
        // Job reached terminal failed state
        android.util.Log.e("Decart", "Job failed: ${result.error}")
    }
    else -> Unit
}

client.release()
```

### Batch Progress Stream Example

```kotlin
client.queue.submitAndObserve(VideoModels.LUCY_2_1, input).collect { update ->
    when (update) {
        is QueueJobResult.InProgress -> {
            // pending / processing
            android.util.Log.d("Decart", "Status: ${update.status}")
        }
        is QueueJobResult.Completed -> {
            android.util.Log.d("Decart", "Completed: ${update.data.size} bytes")
        }
        is QueueJobResult.Failed -> {
            android.util.Log.e("Decart", "Failed: ${update.error}")
        }
    }
}
```

### Other Batch Input Examples

```kotlin
import ai.decart.sdk.VideoModels
import ai.decart.sdk.queue.FileInput
import ai.decart.sdk.queue.VideoRestyleInput

// Restyle (reference-image mode)
val restyle = VideoRestyleInput(
    data = FileInput.fromUri(videoUri),
    referenceImage = FileInput.fromUri(styleImageUri),
    seed = 7,
)
client.queue.submit(VideoModels.LUCY_RESTYLE_2, restyle)
```

## Available Models

### Realtime Models

| Model | Constant | Resolution | FPS |
|-------|----------|-----------|-----|
| Lucy 2.1 | `RealtimeModels.LUCY_2_1` | 1088x624 | 30 |
| Lucy 2.1 VTON | `RealtimeModels.LUCY_2_1_VTON` | 1088x624 | 30 |
| Lucy Restyle 2 | `RealtimeModels.LUCY_RESTYLE_2` | 1280x704 | 30 |

### Batch Video Models

| Model | Constant | Queue Path | Resolution | FPS |
|-------|----------|------------|------------|-----|
| Lucy Clip | `VideoModels.LUCY_CLIP` | `/v1/jobs/lucy-clip` | 1280x704 | 25 |
| Lucy 2.1 | `VideoModels.LUCY_2_1` | `/v1/jobs/lucy-2.1` | 1088x624 | 20 |
| Lucy 2.1 VTON | `VideoModels.LUCY_2_1_VTON` | `/v1/jobs/lucy-2.1-vton` | 1088x624 | 20 |
| Lucy Restyle 2 | `VideoModels.LUCY_RESTYLE_2` | `/v1/jobs/lucy-restyle-2` | 1280x704 | 22 |

Typed input helpers:

- `VideoEditInput` (`lucy-2.1`, `lucy-2.1-vton`, `lucy-clip`)
- `VideoRestyleInput` (`lucy-restyle-2`)

## API Reference

### Core Classes

| Class | Description |
|-------|-------------|
| `DecartClient` | Unified entry point exposing `realtime` and `queue` clients |
| `RealTimeClient` | Main entry point for real-time video streaming |
| `RealTimeClientConfig` | Client configuration (API key, base URL, logger) |
| `ConnectOptions` | Connection parameters (model, callbacks, initial prompt) |
| `InitialPrompt` | Initial prompt with optional enhancement |
| `Resolution` | Output resolution enum (`P720`, `P1080`) for `ConnectOptions.resolution` |
| `ConnectionState` | Connection lifecycle enum (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `GENERATING`, `RECONNECTING`) |
| `RealtimeModels` | Available AI model definitions |
| `VideoModels` | Available batch video model definitions |
| `ModelInputType` | Input category expected by each batch model |
| `QueueClient` | Batch queue client (`submit`, `status`, `result`, `submitAndPoll`, `submitAndObserve`) |
| `VideoEditInput` | Typed queue input for Lucy 2.1 V2V payload |
| `VideoRestyleInput` | Typed queue input for Lucy Restyle |
| `FileInput` | File wrappers for `Uri`, `File`, `ByteArray`, `InputStream` |
| `DecartError` | Error with code, message, and optional cause |
| `ErrorCodes` | Predefined error code constants |

### RealTimeClient

**Methods:**

| Method | Description |
|--------|-------------|
| `initialize(eglBase?)` | Initialize WebRTC (optional, auto-called on connect) |
| `createCameraVideoTrack(facing, mirror, width, height, fps, trackId)` | One-line camera setup, optional pre-flip via `MirrorVideoProcessor` |
| `connect(videoTrack, audioTrack, options)` | Connect to a model |
| `disconnect()` | End the current session |
| `setPrompt(prompt, enhance, timeoutMs)` | **suspend** — update the prompt; throws on ack failure, timeout (default 15s), or disconnect |
| `setImage(imageBase64, prompt, enhance, timeout)` | **suspend** — set a reference image; throws on ack failure, timeout (default 30s), or disconnect |
| `release()` | Release all resources |

**Observable State:**

| Property | Type | Description |
|----------|------|-------------|
| `connectionState` | `StateFlow<ConnectionState>` | Current connection state |
| `errors` | `SharedFlow<DecartError>` | Error events |
| `stats` | `SharedFlow<WebRTCStats>` | WebRTC performance stats |
| `diagnostics` | `SharedFlow<DiagnosticEvent>` | Connection diagnostic events |

### QueueClient

| Method | Description |
|--------|-------------|
| `submit(model, input)` | Create a job and return `{ jobId, status }` |
| `status(jobId)` | Read current job status |
| `result(jobId)` | Download completed job content as `ByteArray` |
| `submitAndPoll(model, input, onStatusChange?)` | Convenience method: submit and wait for terminal result |
| `submitAndObserve(model, input)` | `Flow` of in-progress updates followed by terminal result |
| `release()` | Close queue HTTP resources |

## Error Handling

Errors are emitted via the `errors` SharedFlow:

```kotlin
client.errors.collect { error ->
    when (error.code) {
        ErrorCodes.INVALID_API_KEY -> { /* handle auth error */ }
        ErrorCodes.WEBRTC_TIMEOUT_ERROR -> { /* handle timeout */ }
        ErrorCodes.WEBRTC_ICE_ERROR -> { /* handle ICE failure */ }
        ErrorCodes.WEBRTC_WEBSOCKET_ERROR -> { /* handle WS error */ }
        ErrorCodes.WEBRTC_SERVER_ERROR -> { /* handle server error */ }
        ErrorCodes.WEBRTC_SIGNALING_ERROR -> { /* handle signaling error */ }
    }
}
```

Queue APIs throw operation-specific exceptions:

- `QueueSubmitException`
- `QueueStatusException`
- `QueueResultException`
- `InvalidInputException`

## Sample App

See the [`sample/`](sample/) directory for a Jetpack Compose app with:

- Realtime tab: camera + WebRTC streaming
- Video tab: batch job submission, status updates, and result playback

## Example App

For a more complete app showcasing real-world use cases -- video restyling, video editing, 90+ style presets, multiple view modes (fullscreen, PIP, split), and swipe-based navigation -- check out the [Decart Android Example App](https://github.com/DecartAI/decart-example-android-realtime).

## Resources

- [Decart Platform](https://decart.ai)
- [API Documentation](https://docs.decart.ai)
- [Get an API Key](https://decart.ai)
- [Example App](https://github.com/DecartAI/decart-example-android-realtime)

## License

MIT
