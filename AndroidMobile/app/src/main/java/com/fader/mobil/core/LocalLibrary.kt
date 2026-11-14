package com.fader.mobil.core

import android.content.Context

data class FolderEntry(val name: String, val url: String)

data class TrackEntry(
    val name: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val url: String,
    val artKey: String? = null,
    val artUri: String? = null,
)

/** Where a cover comes from: cache key + content URI to decode it. */
data class ArtworkRef(val key: String, val uri: String)

data class Listing(
    val url: String,
    val name: String,
    val folders: List<FolderEntry>,
    val tracks: List<TrackEntry>,
    val remote: Boolean = false,
    val coverUrl: String? = null,
)

/**
 * Local library backed by MediaStore. The tree is built once on a background
 * thread; afterwards listings are pure in-memory reads, which is what keeps
 * big folders instant.
 */
object LocalLibrary {

    const val ROOT = "/local/"

    /** System sound folders are never music. */
    private val systemFolders = setOf("ringtones", "notifications", "alarms", "ui")


    @Volatile
    private var client: LocalClient? = null

    val isReady: Boolean get() = client != null

    internal fun localClient(): LocalClient? = client

    /** Builds the MediaStore tree. Call from a background thread. */
    fun load(context: Context): Int {
        val local = LocalClient(context.applicationContext)
        client = local
        return try {
            local.countAudio(ROOT, null, null)
        } catch (e: Exception) {
            0
        }
    }

    fun root(): Listing = listing(ROOT, "On this phone")

    /**
     * Where "On this phone" should open: the Music folder when one exists, otherwise
     * the first volume that actually holds something.
     */
    fun musicRoot(): FolderEntry {
        val local = client ?: return FolderEntry(ROOT, "On this phone")
        val volumes = local.quickLibrary(ROOT, "On this phone").folders
        if (volumes.isEmpty()) return FolderEntry(ROOT, "On this phone")

        for (volume in volumes) {
            val musicUrl = volume.url + "Music/"
            val music = local.quickLibrary(musicUrl, "Music")
            if (music.audio.isNotEmpty() || music.folders.isNotEmpty()) {
                return FolderEntry("Music", musicUrl)
            }
        }
        for (volume in volumes) {
            val listing = local.quickLibrary(volume.url, volume.name)
            if (listing.audio.isNotEmpty() || listing.folders.isNotEmpty()) {
                return FolderEntry(volume.name, volume.url)
            }
        }
        return FolderEntry(ROOT, "On this phone")
    }

    /**
     * Volume roots ("Internal", "Media") hold Music, Ringtones, Alarms… Opening the
     * volume should land straight in its Music folder when there is one.
     */
    fun redirect(folder: FolderEntry): FolderEntry {
        val local = client ?: return folder
        if (folder.url == ROOT) {
            // "On this phone" should land in the music folders, not on the volume list.
            val root = local.quickLibrary(ROOT, "On this phone")
            val volume = root.folders.firstOrNull { it.name.equals("Internal", ignoreCase = true) }
                ?: root.folders.firstOrNull()
                ?: return folder
            return redirectVolume(local, FolderEntry(volume.name, volume.url))
        }
        return redirectVolume(local, folder)
    }

    private fun redirectVolume(local: LocalClient, folder: FolderEntry): FolderEntry {
        val relative = folder.url.removePrefix(ROOT).trimEnd('/')
        if (relative.contains('/')) return folder
        val musicUrl = folder.url + "Music/"
        val music = local.quickLibrary(musicUrl, "Music")
        return if (music.folders.isNotEmpty() || music.audio.isNotEmpty()) {
            FolderEntry("Music", musicUrl)
        } else {
            folder
        }
    }

    fun listing(url: String, name: String): Listing {
        val local = client ?: return Listing(url, name, emptyList(), emptyList())
        val library = local.quickLibrary(url, name)
        val folders = library.folders
            .filter { it.name.lowercase(java.util.Locale.ROOT) !in systemFolders }
            .map { FolderEntry(it.name, it.url) }
        val tracks = library.audio.map { entry ->
            val parent = entry.url.substringBeforeLast('/', "")
            val parsed = MetaParser.fromName(entry.name, parent.ifEmpty { entry.url })
            TrackEntry(
                name = entry.name,
                title = entry.mediaTitle?.takeIf { it.isNotBlank() }
                    ?: if (parsed.nonEmptyTitle()) parsed.title else entry.name.substringBeforeLast('.'),
                artist = entry.mediaArtist?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: if (parsed.nonEmptyArtist()) parsed.artist else "Unknown artist",
                album = entry.mediaAlbum?.takeIf { it.isNotBlank() }
                    ?: if (parsed.nonEmptyAlbum()) parsed.album else name,
                durationMs = entry.durationMs,
                url = entry.url,
                artKey = artKeyFor(local, entry.url),
                artUri = local.contentUri(entry.url)?.toString(),
            )
        }
        return Listing(url, name, folders, tracks)
    }

    /** Local tracks matching the text, with their folder. */
    fun search(query: String, limit: Int = 200): List<SearchHit> {
        val local = client ?: return emptyList()
        return local.search(query, limit).map { entry ->
            val folderUrl = entry.url.substringBeforeLast('/', "") + "/"
            SearchHit(
                url = entry.url,
                name = entry.name,
                folderUrl = folderUrl,
                folderName = folderUrl.trimEnd('/').substringAfterLast('/'),
                remote = false,
            )
        }
    }

    private fun artKeyFor(local: LocalClient, url: String): String? {
        if (local.contentUri(url) == null) return null
        val albumId = local.albumId(url)
        return if (albumId >= 0) "album:$albumId" else "track:$url"
    }

    /** Artwork for a local track URL, if the file is indexed. */
    fun art(url: String): ArtworkRef? {
        val local = client ?: return null
        val uri = local.contentUri(url)?.toString() ?: return null
        val albumId = local.albumId(url)
        return ArtworkRef(if (albumId >= 0) "album:$albumId" else "track:$url", uri)
    }

    /** Queues the folder's songs from [trackIndex]. Call on the main thread. */
    fun play(url: String, name: String, trackIndex: Int) {
        val local = client ?: return
        val library = local.quickLibrary(url, name)
        if (library.audio.isEmpty()) return
        val items = library.audio.map { entry ->
            TrackItem(local, entry).apply {
                entry.mediaTitle?.takeIf { it.isNotBlank() }?.let { title = it }
                entry.mediaArtist?.takeIf { it.isNotBlank() && it != "<unknown>" }?.let { artist = it }
                entry.mediaAlbum?.takeIf { it.isNotBlank() }?.let { album = it }
            }
        }
        val index = trackIndex.coerceIn(0, items.lastIndex)
        FaderPlayer.playQueue(items, index)
    }
}
