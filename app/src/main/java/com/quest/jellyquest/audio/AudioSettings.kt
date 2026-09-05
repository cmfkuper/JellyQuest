package com.quest.jellyquest.audio

import android.content.SharedPreferences

/**
 * Persists spatial audio and room acoustics toggle state.
 * Spatial audio defaults to ON; room acoustics defaults to OFF (opt-in).
 * Accepts SharedPreferences via constructor for testability.
 */
class AudioSettings(private val prefs: SharedPreferences) {

    companion object {
        const val KEY_SPATIAL_AUDIO = "spatial_audio_enabled"
        const val KEY_ROOM_ACOUSTICS = "room_acoustics_enabled"
    }

    var spatialAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPATIAL_AUDIO, true)
        set(value) { prefs.edit().putBoolean(KEY_SPATIAL_AUDIO, value).apply() }

    var roomAcousticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOM_ACOUSTICS, false)
        set(value) { prefs.edit().putBoolean(KEY_ROOM_ACOUSTICS, value).apply() }
}
