package com.quest.jellyquest

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.Cue
import com.quest.jellyquest.streaming.ExoPlayerSource
import kotlinx.coroutines.delay

/**
 * Transparent overlay panel congruent with the video frame (same fitted
 * dimensions, floating just in front of it). Renders ExoPlayer's active
 * subtitle cues — needed because the video renders direct-to-surface via a
 * compositor layer, so there is no Android view for ExoPlayer's usual
 * SubtitleView to draw into.
 *
 * Bitmap cues without placement metadata (typical VobSub) are full-frame
 * images with transparency, stretched 1:1 over the frame. Bitmap cues with a
 * size (typical PGS crops) and text cues are laid into the bottom band.
 *
 * Also flashes a brief announcement pill when the subtitle track changes —
 * user feedback that doubles as visual confirmation the overlay renders.
 */
@Composable
fun SubtitlePanel(exoPlayerSource: ExoPlayerSource) {
    val cues by exoPlayerSource.cues.collectAsState()
    val subtitleTracks by exoPlayerSource.subtitleTracks.collectAsState()
    val selectedIndex by exoPlayerSource.selectedSubtitleIndex.collectAsState()

    var announcement by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            announcement = "Subtitles: ${subtitleTracks.getOrNull(selectedIndex)?.label ?: "On"}"
            delay(3000)
        }
        announcement = null
    }

    LaunchedEffect(cues) {
        if (cues.isNotEmpty()) {
            Log.i("VirtualMonitor", "SubtitlePanel rendering ${cues.size} cue(s)")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-frame bitmap cues (no size metadata): stretch over the frame
        // exactly as authored.
        cues.forEach { cue ->
            val bitmap = cue.bitmap
            if (bitmap != null && cue.size == Cue.DIMEN_UNSET) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Bottom band: sized bitmap cues and text cues.
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            cues.forEach { cue ->
                val bitmap = cue.bitmap
                val text = cue.text
                when {
                    bitmap != null && cue.size != Cue.DIMEN_UNSET -> Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(cue.size.coerceIn(0.05f, 1f)),
                    )
                    bitmap == null && text != null -> Text(
                        text = text.toString(),
                        color = Color.White,
                        fontSize = 34.sp,
                        lineHeight = 42.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.fillMaxHeight(0.045f))
        }

        // Track-change announcement pill (top center, briefly).
        announcement?.let { label ->
            Text(
                text = label,
                color = DraculaForeground,
                fontSize = 26.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(DraculaBackground.copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}
