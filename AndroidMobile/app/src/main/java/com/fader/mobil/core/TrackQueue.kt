package com.fader.mobil.core

/**
 * Builds a queue straight from saved tracks (favorites or a playlist), so playing
 * keeps walking that list instead of jumping into the source folder.
 */
object TrackQueue {

    fun play(remoteFlags: List<Boolean>, urls: List<String>, index: Int): Boolean {
        if (urls.isEmpty()) return false
        val remote = RemoteLibrary.remoteClient()
        val local = LocalLibrary.localClient()

        val items = urls.mapIndexed { position, url ->
            val entry = Entry(url.substringAfterLast('/'), url, false, 0, 0)
            // Older saves sometimes carry a wrong remote flag, so the URL wins.
            val isRemote = url.startsWith("http://") || url.startsWith("https://") ||
                remoteFlags.getOrElse(position) { false }
            if (isRemote) {
                val client = remote ?: return false
                TrackItem(client, entry)
            } else {
                val client = local ?: return false
                TrackItem(client, entry)
            }
        }
        val safeIndex = index.coerceIn(0, items.lastIndex)
        FaderPlayer.playQueue(items, safeIndex)
        return true
    }
}
