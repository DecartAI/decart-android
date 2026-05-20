package ai.decart.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.livekit.android.compose.types.TrackReference
import io.livekit.android.compose.ui.ScaleType
import io.livekit.android.compose.ui.VideoTrackView
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.util.flow

@Composable
fun TrackItem(
    trackReference: TrackReference?,
    videoTrack: VideoTrack?,
    room: Room?,
    label: String,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
) {
    val participant = trackReference?.participant
    val identityState = participant?.let {
        it::identity.flow.collectAsState(initial = it.identity)
    } ?: remember { mutableStateOf(null) }
    val isSpeakingState = participant?.let {
        it::isSpeaking.flow.collectAsState(initial = it.isSpeaking)
    } ?: remember { mutableStateOf(false) }
    val connectionQualityState = participant?.let {
        it::connectionQuality.flow.collectAsState(initial = it.connectionQuality)
    } ?: remember { mutableStateOf(ConnectionQuality.UNKNOWN) }
    val isVideoMutedState = trackReference?.publication?.let {
        it::muted.flow.collectAsState(initial = it.muted)
    } ?: remember { mutableStateOf(videoTrack == null) }

    val identity by identityState
    val isSpeaking by isSpeakingState
    val connectionQuality by connectionQualityState
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

        Surface(
            color = Color(0x80000000),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = identity?.value?.let { identityValue ->
                        if (trackReference?.source == Track.Source.SCREEN_SHARE) {
                            "$identityValue's screen"
                        } else {
                            identityValue
                        }
                    } ?: label,
                    color = Color.White,
                )

                if (connectionQuality == ConnectionQuality.POOR || connectionQuality == ConnectionQuality.LOST) {
                    Text(
                        text = connectionQuality.name.lowercase(),
                        color = Color.Red,
                        modifier = Modifier.alpha(0.8f),
                    )
                }
            }
        }
    }
}
