# Decart Android SDK

![Platform](https://img.shields.io/badge/platform-Android%20API%2024%2B-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

Android SDK for Decart realtime streaming and batch video generation.

## Features

- Real-time video restyling and editing via LiveKit media transport
- Batch video generation via `/v1/jobs/*` queue APIs
- Built-in realtime and video model registries
- Kotlin coroutines and Flow-based reactive state management
- Observable connection state, remote media streams, errors, diagnostics, and publish stats
- LiveKit camera publishing support

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
    implementation("com.github.DecartAI:decart-android:0.7.3")
}
```

## Quick Start

```kotlin
import ai.decart.sdk.DecartClient
import ai.decart.sdk.DecartClientConfig
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.InitialPrompt
import ai.decart.sdk.realtime.MirrorMode
import ai.decart.sdk.RealtimeModels

val client = DecartClient(context, DecartClientConfig(apiKey = "your-api-key"))
val realtime = client.realtime

// 1. Connect and publish the device camera through LiveKit
realtime.connect(
    ConnectOptions(
        model = RealtimeModels.LUCY_RESTYLE_2,
        initialPrompt = InitialPrompt("a cyberpunk cityscape"),
        facing = FacingMode.FRONT,
        mirror = MirrorMode.AUTO,
        publishCamera = true,
        onRemoteStream = { stream ->
            // Display stream.videoTrack with a LiveKit renderer.
        },
    ),
)

// 2. Change prompt during session and wait for the server ack.
try {
    realtime.setPrompt("a sunny beach scene", enhance = true)
} catch (e: Exception) {
    // ack failure, timeout, or websocket disconnect
}

// Or start immediately and keep a Deferred if you want JS Promise-style usage.
val promptAck = realtime.setPromptAsync("a sunny beach scene", enhance = true)
promptAck.await()

// 3. Disconnect when done
realtime.disconnect()
client.release()
```

### LiveKit rendering

Realtime media tracks are LiveKit tracks. The Android publisher API currently
surfaces remote video streams:

```kotlin
import io.livekit.android.renderer.SurfaceViewRenderer

realtime.remoteStreamUpdates.collect { stream ->
    stream.videoTrack?.addRenderer(remoteRenderer)
}
```

### Realtime audio support

The Android LiveKit realtime publisher currently supports video only. `publishMicrophone`, `includeMicrophone`, and `RealtimeMediaStream.audioTrack` are retained for 0.7 source compatibility, but they are deprecated and ignored; SDK-created streams always expose `audioTrack = null`.


### Realtime camera sizing and codec

Use the model registry to size camera input instead of hardcoding dimensions:

```kotlin
val model = RealtimeModels.LUCY_2_1
val localStream = realtime.createLocalVideoStream(model) // uses model.width/model.height
```

The Lucy 2.1 realtime model configs use `1088x624`. The default LiveKit publisher codec is VP8; override it only when needed via `RealtimeConfiguration.VideoConfig(preferredCodec = ...)`.

### Realtime camera mirroring

Use `mirror` to pre-flip captured frames before they are sent to Decart. This is safer than flipping the rendered remote stream because server-baked pixels such as watermarks and overlays remain readable.

```kotlin
val localStream = realtime.createLocalVideoStream(
    model = RealtimeModels.LUCY_2_1,
    facing = FacingMode.FRONT,
    mirror = MirrorMode.AUTO, // default: front camera mirrored, back camera unmodified
)
```

When capture mirroring is enabled, render both local previews and remote streams as-is; do not also set renderer-level mirroring for those tracks.

You can also set the same behavior for SDK-owned camera capture:

```kotlin
realtime.connect(
    ConnectOptions(
        model = RealtimeModels.LUCY_2_1,
        facing = FacingMode.FRONT,
        mirror = MirrorMode.AUTO,
    )
)
```

### Output resolution

Opt into 1080p output for a realtime session (defaults to 720p server-side):

```kotlin
import ai.decart.sdk.realtime.Resolution

realtime.connect(
    ConnectOptions(
        model = RealtimeModels.LUCY_2_1,
        resolution = Resolution.P1080, // default: server-side 720p
        onRemoteStream = { /* ... */ },
    )
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
| Lucy VTON 2 | `RealtimeModels.LUCY_VTON_2` | 1088x624 | 30 |
| Lucy VTON 3 | `RealtimeModels.LUCY_VTON_3` | 1088x624 | 30 |
| Lucy Restyle 2 | `RealtimeModels.LUCY_RESTYLE_2` | 1280x704 | 30 |

### Batch Video Models

| Model | Constant | Queue Path | Resolution | FPS |
|-------|----------|------------|------------|-----|
| Lucy Clip | `VideoModels.LUCY_CLIP` | `/v1/jobs/lucy-clip` | 1280x704 | 25 |
| Lucy 2.1 | `VideoModels.LUCY_2_1` | `/v1/jobs/lucy-2.1` | 1088x624 | 20 |
| Lucy 2.1 VTON | `VideoModels.LUCY_2_1_VTON` | `/v1/jobs/lucy-2.1-vton` | 1088x624 | 20 |
| Lucy VTON 2 | `VideoModels.LUCY_VTON_2` | `/v1/jobs/lucy-vton-2` | 1088x624 | 20 |
| Lucy VTON 3 | `VideoModels.LUCY_VTON_3` | `/v1/jobs/lucy-vton-3` | 1088x624 | 20 |
| Lucy Restyle 2 | `VideoModels.LUCY_RESTYLE_2` | `/v1/jobs/lucy-restyle-2` | 1280x704 | 22 |

Typed input helpers:

- `VideoEditInput` (`lucy-2.1`, `lucy-2.1-vton`, `lucy-vton-2`, `lucy-vton-3`, `lucy-clip`)
- `VideoRestyleInput` (`lucy-restyle-2`)

## API Reference

### Core Classes

| Class | Description |
|-------|-------------|
| `DecartClient` | Unified entry point exposing `realtime` and `queue` clients |
| `RealTimeClient` | Main entry point for real-time video streaming |
| `RealTimeClientConfig` | Client configuration (API key, base URL, logger) |
| `ConnectOptions` | Connection parameters (model, LiveKit stream callbacks, initial prompt) |
| `InitialPrompt` | Initial prompt with optional enhancement |
| `Resolution` | Output resolution enum (`P720`, `P1080`) for `ConnectOptions.resolution` |
| `MirrorMode` | Camera-input mirroring enum (`OFF`, `ON`, `AUTO`) |
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
| `connect(options)` | Connect to a model, join the returned LiveKit room, and publish the camera by default |
| `disconnect()` | End the current session |
| `setPrompt(prompt, enhance, timeoutMs)` | **suspend** — update the prompt and wait for the server ack; throws on ack failure, timeout (default 15s), or disconnect |
| `setPromptAsync(prompt, enhance, timeoutMs)` | Starts the prompt update immediately and returns `Deferred<Unit>`; call `await()` to observe ack failure, timeout, or disconnect |
| `setImage(imageBase64, prompt, enhance, timeout)` | **suspend** — set a reference image and optional prompt, then wait for the server ack; throws on ack failure, timeout (default 30s), or disconnect |
| `setImageAsync(imageBase64, prompt, enhance, timeout)` | Starts the image/prompt update immediately and returns `Deferred<Unit>`; call `await()` to observe ack failure, timeout, or disconnect |
| `release()` | Release all resources |

**Observable State:**

| Property | Type | Description |
|----------|------|-------------|
| `connectionState` | `StateFlow<ConnectionState>` | Current connection state |
| `connectionChange` | `StateFlow<ConnectionState>` | JS-aligned alias for `connectionState` |
| `errors` | `SharedFlow<DecartError>` | Error events |
| `generationTick` | `SharedFlow<GenerationTickMessage>` | Generation tick events |
| `localStreamUpdates` | `SharedFlow<RealtimeMediaStream>` | Local LiveKit stream updates |
| `localStream` | `SharedFlow<RealtimeMediaStream>` | JS-aligned alias for `localStreamUpdates` |
| `remoteStreamUpdates` | `SharedFlow<RealtimeMediaStream>` | Remote LiveKit stream updates |
| `remoteStream` | `SharedFlow<RealtimeMediaStream>` | JS-aligned alias for `remoteStreamUpdates` |
| `queuePositionUpdates` | `SharedFlow<QueuePositionMessage>` | Queue position updates while waiting for a server slot |
| `queuePosition` | `SharedFlow<QueuePositionMessage>` | JS-aligned alias for `queuePositionUpdates` |
| `generationEnded` | `SharedFlow<GenerationEndedMessage>` | Generation lifecycle end events |
| `sessionStarted` | `StateFlow<SessionStarted?>` | `(sessionId, subscribeToken)` once the LiveKit room info arrives |
| `subscribeToken` | `String?` | Base64 token to hand to viewer / subscribe clients |
| `diagnostics` | `SharedFlow<DiagnosticEvent>` | Connection diagnostic events (incl. `PublishStats`) |
| `diagnostic` | `SharedFlow<DiagnosticEvent>` | JS-aligned alias for `diagnostics` |
| `stats` | `SharedFlow<PublishStatsEvent>` | Publisher outbound video stats, aligned with the JS `stats` event |

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

- Realtime tab: camera + LiveKit streaming
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
