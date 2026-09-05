package com.quest.jellyquest

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.spatial.uiset.theme.LocalColorScheme
import com.meta.spatial.uiset.theme.SpatialTheme
import com.quest.jellyquest.streaming.ExoPlayerSource
import kotlinx.coroutines.delay

/**
 * Floating playback HUD: scrub bar, transport buttons, and a controller-binding
 * legend. Spawned below the viewer's eye line (remote-control position).
 *
 * Auto-shown when playback pauses; toggled manually with the Y button.
 * Position/duration are polled from ExoPlayer while the panel is visible —
 * the panel runs on the main thread, so direct player access is safe.
 */
@Composable
fun PlaybackControlsPanel(
    exoPlayerSource: ExoPlayerSource,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onHide: () -> Unit,
    onButtonHover: () -> Unit = {},
) {
    CompositionLocalProvider(LocalButtonHover provides onButtonHover) {
        PlaybackControlsContent(exoPlayerSource, onPlayPause, onStop, onSeekTo, onHide)
    }
}

/** Haptic callback shared by every transport button, provided by the activity. */
private val LocalButtonHover = compositionLocalOf<() -> Unit> { {} }

@Composable
private fun PlaybackControlsContent(
    exoPlayerSource: ExoPlayerSource,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onHide: () -> Unit,
) {
    val mediaInfo by exoPlayerSource.mediaInfo.collectAsState()
    val audioTracks by exoPlayerSource.audioTracks.collectAsState()
    val selectedAudioIndex by exoPlayerSource.selectedAudioIndex.collectAsState()
    val subtitleTracks by exoPlayerSource.subtitleTracks.collectAsState()
    val selectedSubtitleIndex by exoPlayerSource.selectedSubtitleIndex.collectAsState()

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    // While the user is dragging the scrubber, show the drag position instead of
    // the live position so the thumb doesn't fight the 250ms poll.
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            positionMs = exoPlayerSource.player.currentPosition.coerceAtLeast(0)
            durationMs = exoPlayerSource.player.duration.takeIf { it > 0 } ?: 0L
            isPlaying = exoPlayerSource.player.isPlaying
            delay(250)
        }
    }

    val shownPositionMs = scrubPositionMs ?: positionMs

    SpatialTheme(colorScheme = draculaSpatialColorScheme()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(SpatialTheme.shapes.large)
                .background(brush = LocalColorScheme.current.panel)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mediaInfo?.title ?: "Nothing playing",
                    style = SpatialTheme.typography.body1.copy(
                        color = SpatialTheme.colorScheme.primaryAlphaBackground,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.size(12.dp))
                TransportButton("Hide", onClick = onHide)
            }

            // Scrub bar
            Slider(
                value = if (durationMs > 0) {
                    (shownPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f,
                onValueChange = { fraction ->
                    if (durationMs > 0) {
                        scrubPositionMs = (fraction * durationMs).toLong()
                    }
                },
                onValueChangeFinished = {
                    scrubPositionMs?.let { onSeekTo(it) }
                    scrubPositionMs = null
                },
                enabled = durationMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = DraculaPurple,
                    activeTrackColor = DraculaPurple,
                    inactiveTrackColor = DraculaCurrentLine,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            )

            // Time + transport row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(shownPositionMs),
                    style = SpatialTheme.typography.body2.copy(color = DraculaCyan),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportButton("« 10s") { onSeekTo((positionMs - 10_000).coerceAtLeast(0)) }
                    TransportButton(
                        label = if (isPlaying) "Pause" else "Play",
                        background = DraculaGreen,
                        contentColor = Color.Black,
                        onClick = onPlayPause,
                    )
                    TransportButton("10s »") {
                        val target = positionMs + 10_000
                        onSeekTo(if (durationMs > 0) target.coerceAtMost(durationMs) else target)
                    }
                    TransportButton("Stop", background = DraculaRed, contentColor = Color.Black, onClick = onStop)
                }

                Text(
                    text = if (durationMs > 0) formatTime(durationMs) else "--:--",
                    style = SpatialTheme.typography.body2.copy(color = DraculaCyan),
                )
            }

            Spacer(modifier = Modifier.size(6.dp))

            // Language row: cycle audio tracks and subtitle tracks.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val audioLabel = audioTracks.getOrNull(selectedAudioIndex)?.label
                TransportButton(
                    label = "Audio: ${audioLabel ?: "—"}" +
                        if (audioTracks.size > 1) "  (${selectedAudioIndex + 1}/${audioTracks.size})" else "",
                    background = DraculaPurple,
                    contentColor = Color.Black,
                ) { exoPlayerSource.cycleAudioTrack() }

                // Mirror the audio button: show (i/n) so multiple tracks are
                // discoverable instead of requiring blind cycling.
                val subsLabel = when {
                    subtitleTracks.isEmpty() -> "None"
                    selectedSubtitleIndex >= 0 ->
                        (subtitleTracks.getOrNull(selectedSubtitleIndex)?.label ?: "On") +
                            "  (${selectedSubtitleIndex + 1}/${subtitleTracks.size})"
                    subtitleTracks.size > 1 -> "Off  (${subtitleTracks.size} tracks)"
                    else -> "Off"
                }
                TransportButton(
                    label = "Subs: $subsLabel",
                    background = DraculaOrange,
                    contentColor = Color.Black,
                ) { exoPlayerSource.cycleSubtitleTrack() }
            }

            Spacer(modifier = Modifier.size(6.dp))

            // Controller binding legend — the bindings existed before this HUD,
            // but nothing in VR told the user about them.
            Text(
                text = "A Play/Pause    B Stop    Stick ◄► Seek    Stick-Click Controls    Grip Browse",
                style = SpatialTheme.typography.body2.copy(color = DraculaComment),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun TransportButton(
    label: String,
    background: Color = DraculaCurrentLine,
    contentColor: Color = DraculaForeground,
    onClick: () -> Unit,
) {
    val onHover = LocalButtonHover.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.08f else 1f, label = "buttonHoverScale")

    LaunchedEffect(hovered) {
        if (hovered) onHover()
    }

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (hovered) lerp(background, Color.White, 0.22f) else background,
            contentColor = contentColor,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.scale(scale),
    ) {
        Text(label)
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
