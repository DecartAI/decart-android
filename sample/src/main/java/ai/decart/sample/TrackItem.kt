package ai.decart.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.livekit.android.compose.types.TrackReference
import io.livekit.android.compose.ui.ScaleType
import io.livekit.android.compose.ui.VideoTrackView
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow

@Composable
fun TrackItem(
    trackReference: TrackReference?,
    videoTrack: VideoTrack?,
    room: Room?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    val participant = trackReference?.participant
    val isSpeakingState = participant?.let {
        it::isSpeaking.flow.collectAsState(initial = it.isSpeaking)
    } ?: remember { mutableStateOf(false) }
    val isVideoMutedState = trackReference?.publication?.let {
        it::muted.flow.collectAsState(initial = it.muted)
    } ?: remember { mutableStateOf(videoTrack == null) }

    val isSpeaking by isSpeakingState
    val isVideoMuted by isVideoMutedState

    val borderModifier = if (isSpeaking) {
        Modifier.border(2.dp, Color(0xFF2196F3), RoundedCornerShape(8.dp))
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(borderModifier)
            .background(Color.DarkGray, RoundedCornerShape(8.dp)),
    ) {
        if (room != null) {
            key(room) {
                VideoTrackView(
                    videoTrack = videoTrack,
                    passedRoom = room,
                    mirror = mirror,
                    scaleType = ScaleType.Fill,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (isVideoMuted || videoTrack == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF202124)),
            ) {
                Text(
                    text = "No video",
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
