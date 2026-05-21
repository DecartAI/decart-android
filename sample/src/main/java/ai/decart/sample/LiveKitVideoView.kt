package ai.decart.sample

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon

/**
 * Renders a LiveKit [VideoTrack] inside a Compose layout.
 *
 * Modeled on `VideoTrackView` from livekit/components-android. The
 * [TextureViewRenderer] is initialized exactly once per [room] (against that
 * Room's `EglBase`); when the [Room] identity changes we throw away the old
 * AndroidView and start a fresh one via `key(room)`, because re-initializing
 * a renderer with a different EglBase is not reliable.
 *
 * Track changes within the same Room are handled by `update` — the old track
 * is unbound and the new one is added without ever calling `release()`.
 */
@Composable
fun LiveKitVideoView(
    track: VideoTrack?,
    room: Room?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fit: Boolean = false,
    onFirstFrameRendered: (() -> Unit)? = null,
) {
    if (room == null) return

    key(room) {
        TrackedTextureRenderer(
            track = track,
            room = room,
            modifier = modifier,
            mirror = mirror,
            fit = fit,
            onFirstFrameRendered = onFirstFrameRendered,
        )
    }
}

@Composable
private fun TrackedTextureRenderer(
    track: VideoTrack?,
    room: Room,
    modifier: Modifier,
    mirror: Boolean,
    fit: Boolean,
    onFirstFrameRendered: (() -> Unit)?,
) {
    val rendererHolder = remember { RendererHolder() }
    val rendererEvents = remember {
        object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                Log.i(TAG, "first frame rendered")
                onFirstFrameRendered?.invoke()
            }

            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                Log.i(TAG, "frame resolution changed ${width}x${height} rot=$rotation")
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureViewRenderer(ctx).also { renderer ->
                renderer.init(room.lkObjects.eglBase.eglBaseContext, rendererEvents)
                renderer.setEnableHardwareScaler(true)
                renderer.setMirror(mirror)
                renderer.setScalingType(
                    if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    else RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                )
                rendererHolder.renderer = renderer
                track?.addRenderer(renderer)
                rendererHolder.boundTrack = track
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror)
            renderer.setScalingType(
                if (fit) RendererCommon.ScalingType.SCALE_ASPECT_FIT
                else RendererCommon.ScalingType.SCALE_ASPECT_FILL,
            )
            val previous = rendererHolder.boundTrack
            if (previous !== track) {
                previous?.removeRenderer(renderer)
                track?.addRenderer(renderer)
                rendererHolder.boundTrack = track
            }
        },
        modifier = modifier,
    )

    DisposableEffect(Unit) {
        onDispose {
            val renderer = rendererHolder.renderer
            val bound = rendererHolder.boundTrack
            if (renderer != null && bound != null) {
                try { bound.removeRenderer(renderer) } catch (_: Exception) {}
            }
            rendererHolder.boundTrack = null
            rendererHolder.renderer = null
            try { renderer?.release() } catch (_: Exception) {}
        }
    }
}

private const val TAG = "LiveKitVideoView"

private class RendererHolder {
    var renderer: TextureViewRenderer? = null
    var boundTrack: VideoTrack? = null
}
