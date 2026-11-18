package com.fader.mobil.core

/**
 * Last folder listings, kept in memory so going back to a folder is instant.
 * A big folder is heavy in memory, so only a handful are kept (LRU).
 */
object ListingCache {

    private const val MAX_ENTRIES = 4
    private const val MAX_TRACKS = 120_000

    private var totalTracks = 0

    private val entries = object : LinkedHashMap<String, Pair<Long, Listing>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, Listing>>,
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(url: String): Listing? = entries[url]?.second

    /** Milliseconds since this folder was listed, or null when not cached. */
    @Synchronized
    fun age(url: String): Long? = entries[url]?.let { System.currentTimeMillis() - it.first }

    @Synchronized
    fun put(url: String, listing: Listing) {
        entries[url]?.let { totalTracks -= it.second.tracks.size }
        entries[url] = System.currentTimeMillis() to listing
        totalTracks += listing.tracks.size
        // A couple of 50 000-song folders must not sit in memory together.
        val iterator = entries.entries.iterator()
        while (totalTracks > MAX_TRACKS && entries.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            totalTracks -= eldest.value.second.tracks.size
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalTracks = 0
    }

    /** Cheap fingerprint: tells whether a background refresh changed anything. */
    fun signature(listing: Listing): String = buildString {
        append(listing.folders.size).append('|').append(listing.tracks.size)
        listing.folders.firstOrNull()?.let { append('|').append(it.url) }
        listing.folders.lastOrNull()?.let { append('|').append(it.url) }
        listing.tracks.firstOrNull()?.let { append('|').append(it.url) }
        listing.tracks.lastOrNull()?.let { append('|').append(it.url) }
    }
}
