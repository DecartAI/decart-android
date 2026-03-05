# Decart Android SDK

![Platform](https://img.shields.io/badge/platform-Android%20API%2024%2B-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

Android SDK for real-time AI video transformation.

## Features

- Real-time video restyling and editing via WebRTC
- 5 built-in models (Mirage, Mirage V2, Lucy V2V, Lucy 2 RT, Live Avatar)
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
    implementation("com.github.DecartAI:decart-android:0.1.0")
}
```

## Quick Start

```kotlin
import ai.decart.sdk.realtime.RealTimeClient
import ai.decart.sdk.realtime.RealTimeClientConfig
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.InitialPrompt
import ai.decart.sdk.RealtimeModels

// 1. Create client
val client = RealTimeClient(
    context,
    RealTimeClientConfig(apiKey = "your-api-key")
)

// 2. Initialize WebRTC
client.initialize(eglBase)

// 3. Connect with a camera track
client.connect(
    localVideoTrack = cameraTrack,
    localAudioTrack = null,
    options = ConnectOptions(
        model = RealtimeModels.MIRAGE_V2,
        onRemoteVideoTrack = { track ->
            // Display the transformed video
            remoteRenderer.addSink(track)
        },
        initialPrompt = InitialPrompt("a cyberpunk cityscape")
    )
)

// 4. Change prompt during session
client.setPrompt("a sunny beach scene", enhance = true)

// 5. Disconnect when done
client.disconnect()
client.release()
```

## Available Models

This SDK currently supports **real-time streaming models only**. Video and image generation models (text-to-video, image-to-video, video-to-video, etc.) are available via a straightforward HTTP API -- see the [API docs](https://docs.platform.decart.ai/api-reference/create-job-lucy-pro-t2v).

| Model | Constant | Resolution | FPS |
|-------|----------|-----------|-----|
| Mirage | `RealtimeModels.MIRAGE` | 1280x704 | 25 |
| Mirage V2 | `RealtimeModels.MIRAGE_V2` | 1280x704 | 22 |
| Lucy V2V | `RealtimeModels.LUCY_V2V_720P_RT` | 1280x704 | 25 |
| Lucy 2 RT | `RealtimeModels.LUCY_2_RT` | 1280x720 | 20 |
| Live Avatar | `RealtimeModels.LIVE_AVATAR` | 1280x720 | 25 |

## API Reference

### Core Classes

| Class | Description |
|-------|-------------|
| `RealTimeClient` | Main entry point for real-time video streaming |
| `RealTimeClientConfig` | Client configuration (API key, base URL, logger) |
| `ConnectOptions` | Connection parameters (model, callbacks, initial prompt) |
| `InitialPrompt` | Initial prompt with optional enhancement |
| `ConnectionState` | Connection lifecycle enum (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `GENERATING`, `RECONNECTING`) |
| `RealtimeModels` | Available AI model definitions |
| `DecartError` | Error with code, message, and optional cause |
| `ErrorCodes` | Predefined error code constants |

### RealTimeClient

**Methods:**

| Method | Description |
|--------|-------------|
| `initialize(eglBase?)` | Initialize WebRTC (optional, auto-called on connect) |
| `connect(videoTrack, audioTrack, options)` | Connect to a model |
| `disconnect()` | End the current session |
| `setPrompt(prompt, enhance)` | Update the prompt |
| `setImage(imageBase64, prompt, enhance, timeout)` | Set a reference image |
| `playAudio(audioData)` | Play audio (Live Avatar only) |
| `release()` | Release all resources |

**Observable State:**

| Property | Type | Description |
|----------|------|-------------|
| `connectionState` | `StateFlow<ConnectionState>` | Current connection state |
| `errors` | `SharedFlow<DecartError>` | Error events |
| `stats` | `SharedFlow<WebRTCStats>` | WebRTC performance stats |
| `diagnostics` | `SharedFlow<DiagnosticEvent>` | Connection diagnostic events |

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

## Sample App

See the [`sample/`](sample/) directory for a minimal example with Jetpack Compose UI, camera setup, model selection, and prompt input.

## Example App

For a more complete app showcasing real-world use cases -- video restyling, video editing, 90+ style presets, multiple view modes (fullscreen, PIP, split), and swipe-based navigation -- check out the [Decart Android Example App](https://github.com/DecartAI/decart-example-android-realtime).

## Resources

- [Decart Platform](https://decart.ai)
- [API Documentation](https://docs.decart.ai)
- [Get an API Key](https://decart.ai)
- [Example App](https://github.com/DecartAI/decart-example-android-realtime)

## License

MIT
