package com.fader.mobil.data

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Favorite>,
)

/** Playlists are folders of saved tracks, kept in private preferences. */
private fun percentDecoded(value: String): String =
    if (!value.contains('%')) value
    else runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

object PlaylistStore {

    private const val PREFS = "fader_playlists"
    private const val KEY = "playlists"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Playlist> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val tracks = item.optJSONArray("tracks") ?: JSONArray()
                Playlist(
                    id = item.optString("id"),
                    name = percentDecoded(item.optString("name")),
                    tracks = (0 until tracks.length()).map { position ->
                        val track = tracks.getJSONObject(position)
                        Favorite(
                            url = track.optString("url"),
                            title = percentDecoded(track.optString("title")),
                            artist = percentDecoded(track.optString("artist")),
                            album = percentDecoded(track.optString("album")),
                            folderUrl = track.optString("folderUrl"),
                            folderName = percentDecoded(track.optString("folderName")),
                            remote = track.optBoolean("remote"),
                        )
                    },
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun create(context: Context, name: String): Playlist {
        val playlists = all(context).toMutableList()
        val playlist = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "Playlist" }, emptyList())
        playlists.add(playlist)
        save(context, playlists)
        return playlist
    }

    fun add(context: Context, playlistId: String, tracks: List<Favorite>) {
        if (tracks.isEmpty()) return
        val playlists = all(context).toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return
        val existing = playlists[index].tracks.toMutableList()
        tracks.forEach { track -> if (existing.none { it.url == track.url }) existing.add(track) }
        playlists[index] = playlists[index].copy(tracks = existing)
        save(context, playlists)
    }

    fun removeTrack(context: Context, playlistId: String, url: String) {
        val playlists = all(context).toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return
        playlists[index] = playlists[index].copy(tracks = playlists[index].tracks.filterNot { it.url == url })
        save(context, playlists)
    }

    fun removeTracks(context: Context, playlistId: String, urls: Collection<String>) {
        if (urls.isEmpty()) return
        val playlists = all(context).toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return
        val drop = urls.toSet()
        playlists[index] = playlists[index].copy(
            tracks = playlists[index].tracks.filterNot { it.url in drop },
        )
        save(context, playlists)
    }

    fun rename(context: Context, playlistId: String, name: String) {
        val playlists = all(context).toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return
        playlists[index] = playlists[index].copy(name = name.trim().ifEmpty { playlists[index].name })
        save(context, playlists)
    }

    fun delete(context: Context, playlistId: String) {
        save(context, all(context).filterNot { it.id == playlistId })
    }

    private fun save(context: Context, playlists: List<Playlist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val tracks = JSONArray()
            playlist.tracks.forEach { track ->
                tracks.put(
                    JSONObject()
                        .put("url", track.url)
                        .put("title", track.title)
                        .put("artist", track.artist)
                        .put("album", track.album)
                        .put("folderUrl", track.folderUrl)
                        .put("folderName", track.folderName)
                        .put("remote", track.remote),
                )
            }
            array.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("tracks", tracks))
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}
