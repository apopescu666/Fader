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
    private const val KEY = "session"

    fun save(context: Context, session: PlaybackSession) {
        if (session.tracks.isEmpty()) return
        val tracks = JSONArray()
        session.tracks.forEach { track ->
            tracks.put(
                JSONObject().apply {
                    put("url", track.url)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("remote", track.remote)
                },
            )
        }
        val json = JSONObject().apply {
            put("folderUrl", session.folderUrl)
            put("folderName", session.folderName)
            put("remote", session.remote)
            put("tracks", tracks)
            put("index", session.index)
            put("positionMs", session.positionMs)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString())
            .apply()
    }

    fun load(context: Context): PlaybackSession? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
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
            PlaybackSession(
                folderUrl = json.optString("folderUrl"),
                folderName = json.optString("folderName"),
                remote = json.optBoolean("remote"),
                tracks = tracks,
                index = json.optInt("index", 0).coerceIn(0, tracks.lastIndex),
                positionMs = json.optInt("positionMs", 0),
            )
        }.getOrNull()
    }
}
