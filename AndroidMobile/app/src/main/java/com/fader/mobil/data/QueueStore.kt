package com.fader.mobil.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The user's own play queue: a standalone list, saved across launches. */
object QueueStore {

    private const val PREFS = "fader_queue"
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
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    album = item.optString("album"),
                    folderUrl = item.optString("folderUrl"),
                    folderName = item.optString("folderName"),
                    remote = item.optBoolean("remote"),
                )
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<Favorite>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("url", item.url)
                    put("title", item.title)
                    put("artist", item.artist)
                    put("album", item.album)
                    put("folderUrl", item.folderUrl)
                    put("folderName", item.folderName)
                    put("remote", item.remote)
                },
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    /** Adds songs, skipping any that are already queued. */
    fun addAll(context: Context, items: List<Favorite>): List<Favorite> {
        val current = all(context).toMutableList()
        items.forEach { item ->
            if (current.none { it.url == item.url }) current.add(item)
        }
        save(context, current)
        return current
    }

    fun remove(context: Context, url: String): List<Favorite> {
        val items = all(context).filterNot { it.url == url }
        save(context, items)
        return items
    }

    fun removeAt(context: Context, index: Int): List<Favorite> {
        val items = all(context).toMutableList()
        if (index in items.indices) items.removeAt(index)
        save(context, items)
        return items
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
