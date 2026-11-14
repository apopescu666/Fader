package com.fader.mobil.core

import android.content.Context

data class SavedShare(val url: String, val user: String, val password: String)

/**
 * Remembers the WebDAV share between launches. Stored in the app's private
 * preferences: the sandbox keeps other apps out, and unlike the Keystore path
 * this never fails silently.
 */
object WebDavCredentials {

    private const val PREFS = "fader_webdav"
    private const val KEY_URL = "url"
    private const val KEY_USER = "user"
    private const val KEY_PASSWORD = "password"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, url: String, user: String, password: String) {
        prefs(context).edit()
            .putString(KEY_URL, url)
            .putString(KEY_USER, user)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun load(context: Context): SavedShare? {
        val values = prefs(context)
        val url = values.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return SavedShare(
            url = url,
            user = values.getString(KEY_USER, "").orEmpty(),
            password = values.getString(KEY_PASSWORD, "").orEmpty(),
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
