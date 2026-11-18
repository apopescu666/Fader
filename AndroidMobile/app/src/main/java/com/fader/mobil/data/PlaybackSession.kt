package com.fader.mobil.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One queued song, saved so playback can resume exactly where it stopped. */
data class SessionTrack(
    val url: String,
    val title: String,
    val artist: String,
    val remote: Boolean,
)

/** The whole playback list plus the position, persisted across restarts. */
data class PlaybackSession(
    val folderUrl: String,
    val folderName: String,
    val remote: Boolean,
    val tracks: List<SessionTrack>,
    val index: Int,
    val positionMs: Int,
)

object PlaybackSessionStore {

    private const val PREFS = "fader_session"
    private const val KEY_LEGACY = "session"
    private const val KEY_QUEUE = "queue.v2"
    private const val KEY_POSITION = "position.v2"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The track list changes rarely, so it is written only when it changes.
     * Rewriting a 10 000-entry JSON every few seconds was stalling the UI.
     */
    fun saveQueue(
        context: Context,
        folderUrl: String,
        folderName: String,
        remote: Boolean,
        tracks: List<SessionTrack>,
    ) {
        if (tracks.isEmpty()) return
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(
                JSONObject().apply {
                    put("url", track.url)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("remote", track.remote)
                },
            )
        }
        val json = JSONObject().apply {
            put("folderUrl", folderUrl)
            put("folderName", folderName)
            put("remote", remote)
            put("tracks", array)
        }
        prefs(context).edit().putString(KEY_QUEUE, json.toString()).apply()
    }

    /** Two integers, cheap enough for the five-second ticker. */
    fun savePosition(context: Context, index: Int, positionMs: Int) {
        val json = JSONObject().apply {
            put("index", index)
            put("positionMs", positionMs)
        }
        prefs(context).edit().putString(KEY_POSITION, json.toString()).apply()
    }

    fun save(context: Context, session: PlaybackSession) {
        saveQueue(context, session.folderUrl, session.folderName, session.remote, session.tracks)
        savePosition(context, session.index, session.positionMs)
    }

    fun load(context: Context): PlaybackSession? {
        val preferences = prefs(context)
        val raw = preferences.getString(KEY_QUEUE, null)
            ?: preferences.getString(KEY_LEGACY, null)
            ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray("tracks") ?: return null
            val tracks = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                SessionTrack(
                    url = item.optString("url"),
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    remote = item.optBoolean("remote"),
                )
            }.filter { it.url.isNotBlank() }
            if (tracks.isEmpty()) return null
            val position = preferences.getString(KEY_POSITION, null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val index = (position?.optInt("index", -1) ?: -1)
                .takeIf { it >= 0 }
                ?: json.optInt("index", 0)
            val positionMs = (position?.optLong("positionMs", -1L) ?: -1L)
                .takeIf { it >= 0 }
                ?: json.optLong("positionMs", 0L)
            PlaybackSession(
                folderUrl = json.optString("folderUrl"),
                folderName = json.optString("folderName"),
                remote = json.optBoolean("remote"),
                tracks = tracks,
                index = index.coerceIn(0, tracks.lastIndex),
                positionMs = positionMs.coerceAtLeast(0).toInt(),
            )
        }.getOrNull()
    }
}
