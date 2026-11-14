package com.fader.mobil.data

import android.content.Context

data class LastFolder(val url: String, val name: String, val remote: Boolean)

data class SavedPlayback(
    val trackUrl: String,
    val positionMs: Int,
    val folderUrl: String,
    val folderName: String,
    val remote: Boolean,
    val title: String = "",
    val artist: String = "",
)

/** Remembers the folder the user was in, so the app reopens there. */
object UiState {

    private const val PREFS = "fader_ui"
    private const val KEY_URL = "folder_url"
    private const val KEY_NAME = "folder_name"
    private const val KEY_REMOTE = "folder_remote"
    private const val KEY_TRACK = "track_url"
    private const val KEY_POSITION = "track_position"
    private const val KEY_TRACK_FOLDER = "track_folder"
    private const val KEY_TRACK_FOLDER_NAME = "track_folder_name"
    private const val KEY_TRACK_REMOTE = "track_remote"
    private const val KEY_TRACK_TITLE = "track_title"
    private const val KEY_TRACK_ARTIST = "track_artist"
    private const val KEY_SOURCE_URL = "source_url"
    private const val KEY_SOURCE_NAME = "source_name"
    private const val KEY_SOURCE_REMOTE = "source_remote"

    fun saveFolder(context: Context, folder: LastFolder) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, folder.url)
            .putString(KEY_NAME, folder.name)
            .putBoolean(KEY_REMOTE, folder.remote)
            .apply()
    }

    fun saveSource(context: Context, source: LastFolder) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE_URL, source.url)
            .putString(KEY_SOURCE_NAME, source.name)
            .putBoolean(KEY_SOURCE_REMOTE, source.remote)
            .apply()
    }

    fun source(context: Context): LastFolder? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_SOURCE_URL, null) ?: return null
        return LastFolder(
            url = url,
            name = prefs.getString(KEY_SOURCE_NAME, "").orEmpty(),
            remote = prefs.getBoolean(KEY_SOURCE_REMOTE, false),
        )
    }

    fun savePlayback(context: Context, playback: SavedPlayback) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRACK, playback.trackUrl)
            .putInt(KEY_POSITION, playback.positionMs)
            .putString(KEY_TRACK_FOLDER, playback.folderUrl)
            .putString(KEY_TRACK_FOLDER_NAME, playback.folderName)
            .putBoolean(KEY_TRACK_REMOTE, playback.remote)
            .putString(KEY_TRACK_TITLE, playback.title)
            .putString(KEY_TRACK_ARTIST, playback.artist)
            .apply()
    }

    fun playback(context: Context): SavedPlayback? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_TRACK, null)?.takeIf { it.isNotBlank() } ?: return null
        return SavedPlayback(
            trackUrl = url,
            positionMs = prefs.getInt(KEY_POSITION, 0),
            folderUrl = prefs.getString(KEY_TRACK_FOLDER, "").orEmpty(),
            folderName = prefs.getString(KEY_TRACK_FOLDER_NAME, "").orEmpty(),
            remote = prefs.getBoolean(KEY_TRACK_REMOTE, false),
            title = prefs.getString(KEY_TRACK_TITLE, "").orEmpty(),
            artist = prefs.getString(KEY_TRACK_ARTIST, "").orEmpty(),
        )
    }

    fun folder(context: Context): LastFolder? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return LastFolder(
            url = url,
            name = prefs.getString(KEY_NAME, "") ?: "",
            remote = prefs.getBoolean(KEY_REMOTE, false),
        )
    }
}
