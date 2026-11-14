package com.fader.mobil.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val folderUrl: String,
    val folderName: String,
    val remote: Boolean,
)

/** Favorites kept as a small JSON list in private preferences. */
private fun percentDecoded(value: String): String =
    if (!value.contains('%')) value
    else runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

object FavoritesStore {

    private const val PREFS = "fader_favorites"
    private const val KEY = "items"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Favorite> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Favorite(
                    url = item.optString("url"),
                    title = percentDecoded(item.optString("title")),
                    artist = percentDecoded(item.optString("artist")),
                    album = percentDecoded(item.optString("album")),
                    folderUrl = item.optString("folderUrl"),
                    folderName = percentDecoded(item.optString("folderName")),
                    remote = item.optBoolean("remote"),
                )
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun isFavorite(context: Context, url: String): Boolean = all(context).any { it.url == url }

    /** Adds or removes the track and returns true when it is now a favorite. */
    fun toggle(context: Context, favorite: Favorite): Boolean {
        val items = all(context).toMutableList()
        val existing = items.indexOfFirst { it.url == favorite.url }
        val nowFavorite = existing < 0
        if (nowFavorite) items.add(favorite) else items.removeAt(existing)
        save(context, items)
        return nowFavorite
    }

    fun addAll(context: Context, items: List<Favorite>) {
        if (items.isEmpty()) return
        val current = all(context).toMutableList()
        items.forEach { item -> if (current.none { it.url == item.url }) current.add(item) }
        save(context, current)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    fun remove(context: Context, url: String) {
        save(context, all(context).filterNot { it.url == url })
    }

    private fun save(context: Context, items: List<Favorite>) {
        val array = JSONArray()
        items.forEach { favorite ->
            array.put(
                JSONObject()
                    .put("url", favorite.url)
                    .put("title", favorite.title)
                    .put("artist", favorite.artist)
                    .put("album", favorite.album)
                    .put("folderUrl", favorite.folderUrl)
                    .put("folderName", favorite.folderName)
                    .put("remote", favorite.remote),
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}
