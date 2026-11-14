package com.fader.mobil.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Live UI settings, mirrored into preferences. */
object UiSettings {

    var announceTrack by mutableStateOf(true)
        private set
    var gapless by mutableStateOf(true)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("fader_settings", Context.MODE_PRIVATE)
        announceTrack = prefs.getBoolean("announce", true)
        gapless = prefs.getBoolean("gapless", true)
    }

    fun setAnnounce(context: Context, value: Boolean) {
        announceTrack = value
        save(context, "announce", value)
    }

    fun setGapless(context: Context, value: Boolean) {
        gapless = value
        save(context, "gapless", value)
    }

    private fun save(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences("fader_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }
}
