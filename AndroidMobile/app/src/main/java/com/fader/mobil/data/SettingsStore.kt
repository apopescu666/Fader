package com.fader.mobil.data

import android.content.Context

/** Small persisted settings: fade length and the accessibility toggles. */
object SettingsStore {

    private const val PREFS = "fader_settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fade(context: Context): Int = prefs(context).getInt("fade", 0)

    fun setFade(context: Context, seconds: Int) {
        prefs(context).edit().putInt("fade", seconds).apply()
    }

    fun flag(context: Context, key: String, defaultValue: Boolean): Boolean =
        prefs(context).getBoolean(key, defaultValue)

    fun setFlag(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }
}
