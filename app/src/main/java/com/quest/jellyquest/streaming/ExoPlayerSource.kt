@file:OptIn(UnstableApi::class)

package com.quest.jellyquest.streaming

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoDecoderException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** A selectable audio or subtitle track with a human-readable label. */
data class TrackOption(
    val group: Tracks.Group,
    val label: String,
)

/**
 * StreamSource backed by ExoPlayer (Media3) for HTTP/HLS/DASH video playback.
 * Uses the standard Android media stack for HTTP/HLS/DASH playback.
 */
class ExoPlayerSource(context: Context) : StreamSource {

    companion object {
        private const val TAG = "VirtualMonitor"
        private const val BUMPER_VOLUME = 0.1f
    }

    // Two renderer customizations:
    //  - Decoder fallback: when a hardware decoder rejects a stream (e.g. the
    //    Qualcomm HEVC decoder failing on unusual encodes), retry with the next
    //    available decoder, including software, instead of failing playback.
    //  - Legacy subtitle decoding: bitmap subtitle formats (Blu-ray PGS, DVD
    //    VobSub) are not handled by media3's parse-during-extraction pipeline;
    //    without legacy decoding the TextRenderer reports those tracks as
    //    unsupported and selecting them silently does nothing.
    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        object : DefaultRenderersFactory(context) {
            init {
                setEnableDecoderFallback(true)
                setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder,
                    )
                    if (preferSoftwareVideoDecoder && mimeType.startsWith("video/")) {
                        infos.sortedByDescending { it.softwareOnly }
                    } else {
                        infos
                    }
                }
            }

            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                out.add(TextRenderer(output, outputLooper).apply {
                    experimentalSetLegacyDecodingEnabled(true)
                })
            }
        },
    ).build()

    var isBumperPlaying: Boolean = false
        private set

    // Software-decoder retry state. Some files pass hardware decoder
    // initialization but fail on the first buffer (e.g. the Qualcomm HEVC
    // decoder rejecting unusual encodes); when that happens we flip this flag
    // and reconnect, and the codec selector below then prefers software
    // decoders. Reset when a different item is connected.
    @Volatile
    private var preferSoftwareVideoDecoder = false
    private var currentUri: String? = null

    /** Called when ExoPlayer transitions to STATE_READY. Used by the activity
     *  to wire spatial audio and room acoustics to the player's audio session. */
    var onPlayerReady: (() -> Unit)? = null

    /** Average screen color per sample (GL thread!) — drives ambient room lighting. */
    var onScreenColor: ((r: Float, g: Float, b: Float) -> Unit)? = null

    private var frameTap: VideoFrameTap? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _mediaInfo = MutableStateFlow<MediaInfo?>(null)
    override val mediaInfo: StateFlow<MediaInfo?> = _mediaInfo.asStateFlow()

    // Track selection state — audio languages and subtitles for the current item.
    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    private val _selectedAudioIndex = MutableStateFlow(0)
    val selectedAudioIndex: StateFlow<Int> = _selectedAudioIndex.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val subtitleTracks: StateFlow<List<TrackOption>> = _subtitleTracks.asStateFlow()

    /** Index into [subtitleTracks], or -1 when subtitles are off. */
    private val _selectedSubtitleIndex = MutableStateFlow(-1)
    val selectedSubtitleIndex: StateFlow<Int> = _selectedSubtitleIndex.asStateFlow()

    /** Active subtitle cues, rendered by the subtitle overlay panel. */
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                    Player.STATE_READY -> {
                        _connectionState.value = if (player.isPlaying) {
                            ConnectionState.PLAYING
                        } else {
                            ConnectionState.PAUSED
                        }
                        logStreamFormats()
                        onPlayerReady?.invoke()
                    }
                    Player.STATE_ENDED, Player.STATE_IDLE -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player.playbackState == Player.STATE_READY) {
                    _connectionState.value = if (isPlaying) {
                        ConnectionState.PLAYING
                    } else {
                        ConnectionState.PAUSED
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val currentTitle = _mediaInfo.value?.title
                        ?: player.mediaMetadata.title?.toString()
                        ?: player.currentMediaItem?.localConfiguration?.uri
                            ?.lastPathSegment
                        ?: "Unknown"
                    _mediaInfo.value = MediaInfo(
                        title = currentTitle,
                        width = videoSize.width,
                        height = videoSize.height,
                    )
                    Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                // Video decoder died at runtime (init succeeded, so decoder
                // fallback didn't trigger): retry once preferring software.
                var cause: Throwable? = error.cause
                var videoDecoderFailed = false
                while (cause != null) {
                    if (cause is MediaCodecVideoDecoderException) {
                        videoDecoderFailed = true
                        break
                    }
                    cause = cause.cause
                }
                val uri = currentUri
                if (videoDecoderFailed && !preferSoftwareVideoDecoder &&
                    uri != null && !isBumperPlaying) {
                    val resumeMs = player.currentPosition.coerceAtLeast(0)
                    Log.w(TAG, "Hardware video decoder failed — retrying with software decoder at ${resumeMs}ms")
                    preferSoftwareVideoDecoder = true
                    connect(uri, resumeMs, startPaused = false)
                    return
                }
                _connectionState.value = ConnectionState.ERROR
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Only offer tracks the renderer can actually decode — forcing
                // selection of an unsupported track crashes the text renderer.
                val audio = tracks.groups.filter {
                    it.type == C.TRACK_TYPE_AUDIO && it.isTrackSupported(0)
                }
                // "Forced" subtitle tracks only cover foreign-language snippets
                // and are near-empty on a foreign film — order them last so the
                // first cycle click lands on the real subtitles.
                val (forcedText, normalText) = tracks.groups.filter {
                    it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0)
                }.partition {
                    (it.mediaTrackGroup.getFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                }
                val text = normalText + forcedText
                _audioTracks.value = audio.mapIndexed { i, g -> TrackOption(g, trackLabel(g, i)) }
                _subtitleTracks.value = text.mapIndexed { i, g ->
                    val suffix = if (g in forcedText) " (Forced)" else ""
                    TrackOption(g, trackLabel(g, i) + suffix)
                }
                _selectedAudioIndex.value = audio.indexOfFirst { it.isSelected }.coerceAtLeast(0)
                _selectedSubtitleIndex.value = text.indexOfFirst { it.isSelected }
                Log.i(TAG, "Tracks: ${audio.size} audio, ${text.size} subtitle")
                tracks.groups.filter {
                    it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT
                }.forEach { g ->
                    val f = g.mediaTrackGroup.getFormat(0)
                    val kind = if (g.type == C.TRACK_TYPE_AUDIO) "audio" else "text"
                    Log.i(TAG, "  $kind mime=${f.sampleMimeType} codecs=${f.codecs} " +
                        "lang=${f.language} label=${f.label} " +
                        "selected=${g.isSelected} supported=${g.isTrackSupported(0)}")
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                _cues.value = cueGroup.cues
                if (cueGroup.cues.isNotEmpty()) {
                    Log.d(TAG, "Cues: ${cueGroup.cues.size} " +
                        cueGroup.cues.joinToString { c ->
                            c.bitmap?.let { "bitmap ${it.width}x${it.height}" }
                                ?: "text '${c.text?.take(40)}'"
                        })
                }
            }
        })
    }

    private fun trackLabel(group: Tracks.Group, index: Int): String {
        val format = group.mediaTrackGroup.getFormat(0)
        format.label?.let { return it }
        val lang = format.language
        if (lang != null && lang != "und") {
            val display = Locale(lang).getDisplayLanguage(Locale.ENGLISH)
            if (display.isNotBlank() && display != lang) {
                return display.replaceFirstChar { it.uppercase() }
            }
            return lang
        }
        return "Track ${index + 1}"
    }

    /** Cycle to the next audio track (no-op with fewer than two). */
    fun cycleAudioTrack() {
        val tracks = _audioTracks.value
        if (tracks.size < 2) return
        selectAudioTrack((_selectedAudioIndex.value + 1) % tracks.size)
    }

    fun selectAudioTrack(index: Int) {
        val option = _audioTracks.value.getOrNull(index) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, 0))
            .build()
        _selectedAudioIndex.value = index
        Log.i(TAG, "Audio track → ${option.label}")
    }

    /** Cycle subtitles: off → first track → … → last track → off. */
    fun cycleSubtitleTrack() {
        val tracks = _subtitleTracks.value
        if (tracks.isEmpty()) return
        val next = _selectedSubtitleIndex.value + 1
        selectSubtitleTrack(if (next >= tracks.size) -1 else next)
    }

    fun selectSubtitleTrack(index: Int) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (index < 0) {
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            _cues.value = emptyList()
            Log.i(TAG, "Subtitles → off")
        } else {
            val option = _subtitleTracks.value.getOrNull(index) ?: return
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, 0))
            Log.i(TAG, "Subtitles → ${option.label}")
        }
        player.trackSelectionParameters = builder.build()
        _selectedSubtitleIndex.value = index
        // Progressive streams extract all tracks together, so the already
        // buffered stretch (up to ~50s) has no text samples for a track that
        // was off when it was extracted; a seek forces re-extraction with the
        // track enabled. Jump back a few seconds rather than seeking in place:
        // re-extraction starts at the keyframe, so a cue already mid-display
        // would otherwise be skipped and subs only appear at the NEXT line —
        // and the viewer enabling subs likely just missed some dialogue anyway.
        if (index >= 0 && player.playbackState != Player.STATE_IDLE) {
            player.seekTo((player.currentPosition - 4_000).coerceAtLeast(0))
        }
    }

    private fun logStreamFormats() {
        val videoFormat = player.videoFormat
        val audioFormat = player.audioFormat
        Log.i(TAG, "Stream video: ${videoFormat?.width}x${videoFormat?.height} " +
            "codec=${videoFormat?.codecs} bitrate=${videoFormat?.bitrate}")
        Log.i(TAG, "Stream audio: channels=${audioFormat?.channelCount} " +
            "sampleRate=${audioFormat?.sampleRate} codec=${audioFormat?.codecs} " +
            "bitrate=${audioFormat?.bitrate}")
    }

    /** Play bundled bumper videos in a loop at 50% volume until real content is selected. */
    fun playBumpers(context: Context, bumperResIds: List<Int>) {
        disconnect()
        if (bumperResIds.isEmpty()) return
        Log.i(TAG, "Playing ${bumperResIds.size} bumper(s) at 50% volume")
        val items = bumperResIds.map { resId ->
            val uri = Uri.parse("android.resource://${context.packageName}/$resId")
            MediaItem.fromUri(uri)
        }
        player.setMediaItems(items)
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = BUMPER_VOLUME
        player.prepare()
        player.play()
        isBumperPlaying = true
    }

    override fun connect(uri: String) {
        connect(uri, startPositionMs = 0, startPaused = false)
    }

    /** Connect with optional resume position. If startPaused, prepares but does not auto-play. */
    fun connect(uri: String, startPositionMs: Long, startPaused: Boolean) {
        // New item: give the hardware decoder a fresh chance. Same item means
        // this is the software-decoder retry — keep the preference.
        if (uri != currentUri) {
            preferSoftwareVideoDecoder = false
        }
        currentUri = uri
        disconnect()
        Log.i(TAG, "ExoPlayer connecting to: $uri (startAt=${startPositionMs}ms, paused=$startPaused)")
        _connectionState.value = ConnectionState.CONNECTING
        player.setMediaItem(MediaItem.fromUri(uri))
        // Fresh item: clear any per-track overrides and start with subtitles off.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _selectedSubtitleIndex.value = -1
        _cues.value = emptyList()
        if (startPositionMs > 0) {
            player.seekTo(startPositionMs)
        }
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.volume = 1.0f
        player.prepare()
        if (!startPaused) {
            player.play()
        }
    }

    override fun disconnect() {
        player.stop()
        player.clearMediaItems()
        isBumperPlaying = false
        _connectionState.value = ConnectionState.DISCONNECTED
        _mediaInfo.value = null
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        _selectedAudioIndex.value = 0
        _selectedSubtitleIndex.value = -1
        _cues.value = emptyList()
    }

    override fun attachSurface(surface: Surface) {
        frameTap?.release()
        frameTap = null
        // Route video through the GL frame tap so the theater can sample the
        // screen's average color; fall back to a direct connection if GL
        // setup fails so playback never depends on the ambient lighting.
        val tap = VideoFrameTap(surface) { r, g, b -> onScreenColor?.invoke(r, g, b) }
        val input = tap.inputSurface
        if (input != null) {
            frameTap = tap
            player.setVideoSurface(input)
        } else {
            tap.release()
            player.setVideoSurface(surface)
        }
    }

    override fun detachSurface() {
        player.setVideoSurface(null)
        frameTap?.release()
        frameTap = null
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekForward(seconds: Long) {
        player.seekTo(player.currentPosition + (seconds * 1000))
    }

    override fun seekBackward(seconds: Long) {
        player.seekTo((player.currentPosition - (seconds * 1000)).coerceAtLeast(0))
    }

    override fun stop() {
        disconnect()
    }

    /** Release the ExoPlayer instance. Call from Activity.onDestroy(). */
    fun release() {
        player.release()
        frameTap?.release()
        frameTap = null
    }
}
