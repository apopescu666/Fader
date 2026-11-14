package com.fader.mobil.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchHit(
    val url: String,
    val name: String,
    val folderUrl: String,
    val folderName: String,
    val remote: Boolean,
)

/** Searches both sources so the results feel like one library. */
object Search {
    suspend fun query(text: String, limit: Int = 200): List<SearchHit> {
        if (text.trim().length < 2) return emptyList()
        return withContext(Dispatchers.IO) {
            val local = runCatching { LocalLibrary.search(text, limit) }.getOrDefault(emptyList())
            val remote = runCatching { RemoteLibrary.search(text, limit) }.getOrDefault(emptyList())
            (local + remote).take(limit)
        }
    }
}
