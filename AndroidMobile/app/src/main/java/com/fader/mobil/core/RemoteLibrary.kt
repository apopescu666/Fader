package com.fader.mobil.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One WebDAV share over HTTPS. Listings run on IO, playback streams straight
 * from the server with auth headers, and each track's artwork is pulled out of
 * the file with a small HTTP range read.
 */
object RemoteLibrary {

    private const val DEFAULT_READ_BYTES = 1024 * 1024
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var client: LibraryClient? = null

    @Volatile
    private var lastUrl: String? = null

    @Volatile
    private var lastEntries: List<Entry> = emptyList()

    /** track url -> cached cover file ("file:/...") */
    private val trackCovers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** folder url -> cover image url found in that folder */
    private val folderCovers = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val extraction = Semaphore(3)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val isConnected: Boolean get() = client != null

    internal fun remoteClient(): LibraryClient? = client

    val rootUrl: String? get() = client?.rootUrl()

    /** "nas.example.com" out of "https://nas.example.com/music/". */
    val hostLabel: String?
        get() = runCatching {
            java.net.URI(rootUrl ?: return@runCatching null).host
        }.getOrNull()?.removePrefix("www.")

    /** Basic auth header, reused by the artwork loader. */
    val authHeader: String?
        get() = (client as? WebDavClient)?.authHeader()

    suspend fun connect(url: String, user: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                disconnect()
                val normalized = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    else -> "https://$url"
                }
                val created = WebDavClient(normalized, user, password)
                // Probe once so wrong credentials fail here, not on the first folder.
                created.quickLibrary(created.rootUrl(), "Share")
                client = created
            }
        }

    suspend fun listing(url: String, name: String): Listing = withContext(Dispatchers.IO) {
        val active = client ?: return@withContext Listing(url, name, emptyList(), emptyList(), remote = true)
        val library = active.quickLibrary(url, name)
        lastUrl = url
        lastEntries = library.audio
        library.cover?.url?.let { folderCovers[url] = it }

        val folders = library.folders.map { FolderEntry(it.name, it.url) }
        val tracks = library.audio.map { entry ->
            val parent = entry.url.substringBeforeLast('/', "")
            val parsed = MetaParser.fromName(entry.name, parent.ifEmpty { entry.url })
            TrackEntry(
                name = entry.name,
                title = if (parsed.nonEmptyTitle()) parsed.title else entry.name.substringBeforeLast('.'),
                artist = if (parsed.nonEmptyArtist()) parsed.artist else "Unknown artist",
                album = if (parsed.nonEmptyAlbum()) parsed.album else name,
                durationMs = 0,
                url = entry.url,
                artKey = "track:" + entry.url,
                artUri = "remote:" + entry.url,
            )
        }
        Listing(url, name, folders, tracks, remote = true, coverUrl = library.cover?.url)
    }

    suspend fun play(url: String, name: String, index: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val active = client ?: error("No share connected")
            val entries = if (url == lastUrl && lastEntries.isNotEmpty()) {
                lastEntries
            } else {
                active.quickLibrary(url, name).audio
            }
            if (entries.isEmpty()) error("No songs in this folder")
            val items = entries.map { TrackItem(active, it) }
            val safeIndex = index.coerceIn(0, items.lastIndex)
            withContext(Dispatchers.Main) { FaderPlayer.playQueue(items, safeIndex) }
        }
    }

    /** Walks the share (bounded) and returns tracks whose name matches. */
    suspend fun search(query: String, limit: Int = 200): List<SearchHit> = withContext(Dispatchers.IO) {
        val active = client ?: return@withContext emptyList()
        val needle = query.lowercase()
        val hits = ArrayList<SearchHit>()
        val seen = HashSet<String>()
        val pending = ArrayDeque<String>()
        pending.add(active.rootUrl())
        while (pending.isNotEmpty() && seen.size < 250 && hits.size < limit) {
            val folder = pending.removeFirst()
            if (!seen.add(folder)) continue
            val listing = runCatching { active.quickLibrary(folder, "") }.getOrNull() ?: continue
            listing.folders.forEach { pending.add(it.url) }
            listing.audio.forEach { entry ->
                if (entry.name.lowercase().contains(needle)) {
                    hits.add(
                        SearchHit(
                            url = entry.url,
                            name = entry.name,
                            folderUrl = folder,
                            folderName = folder.trimEnd('/').substringAfterLast('/'),
                            remote = true,
                        ),
                    )
                }
            }
        }
        hits
    }

    /** Bitmap for the car: the track's own cover, falling back to the folder cover. */
    suspend fun coverBitmapFor(url: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val path = runCatching { coverPath(url) }.getOrNull()
        path?.let { decodeCoverFile(it) }?.let { return@withContext it }
        folderCover(url)?.let { folder -> fetchCoverImage(folder) }?.let { return@withContext it }
        null
    }

    /** Decodes a cover already extracted to the cache. */
    fun decodeCoverFile(path: String): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        android.graphics.BitmapFactory.decodeFile(
            path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    private fun fetchCoverImage(url: String): android.graphics.Bitmap? = runCatching {
        val request = Request.Builder()
            .url(url)
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()
        val bytes = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        } ?: return@runCatching null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    /** cover.jpg of the folder that holds this track, when the folder had one. */
    fun folderCover(url: String): String? {
        val folder = url.substringBeforeLast('/', "") + "/"
        return folderCovers[folder]
    }

    /** Cover file already extracted for this track, if any. */
    fun cachedCoverPath(url: String): String? = trackCovers[url]?.removePrefix("file:")

    /** Artwork for a remote track: resolved lazily, so every song gets its own picture. */
    fun art(url: String): ArtworkRef = ArtworkRef("track:" + url, "remote:" + url)

    /** Extracts (once) and returns the cover file path for a track. */
    suspend fun coverPath(url: String): String? = withContext(Dispatchers.IO) {
        trackCovers[url]?.let { return@withContext it.removePrefix("file:") }

        coverFile(url)?.takeIf { it.isFile }?.let { file ->
            trackCovers[url] = "file:" + file.absolutePath
            return@withContext file.absolutePath
        }

        extraction.withPermit {
            runCatching {
                val bytes = fetchHead(url) ?: return@withPermit null
                val image = EmbeddedArt.fromBytes(bytes) ?: return@withPermit null
                val file = saveCover(url, image) ?: return@withPermit null
                trackCovers[url] = "file:" + file.absolutePath
                file.absolutePath
            }.getOrNull()
        }
    }

    /** Reads the first megabyte of the file; the parser needs only the tag. */
    /**
     * Reads the bytes that actually contain the tag. The ID3v2 header declares
     * its own size, so a 2 MB embedded PNG is read completely instead of being
     * cut at a fixed window.
     */
    private fun fetchHead(url: String): ByteArray? {
        val prefix = rangeBytes(url, 16) ?: return null
        val wanted = EmbeddedArt.desiredReadSize(
            prefix,
            DEFAULT_READ_BYTES,
            MAX_TAG_BYTES,
        )
        if (wanted <= prefix.size) return prefix
        return rangeBytes(url, wanted) ?: prefix
    }

    private fun rangeBytes(url: String, count: Int): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${count - 1}")
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val buffer = ByteArray(count)
            var read = 0
            body.byteStream().use { input ->
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
            if (read <= 0) null else buffer.copyOf(read)
        }
    }

    private fun saveCover(key: String, bytes: ByteArray): File? {
        val file = coverFile(key) ?: return null
        return runCatching { file.writeBytes(bytes); file }.getOrNull()
    }

    private fun coverFile(key: String): File? {
        val context = appContext ?: return null
        return runCatching {
            val dir = File(context.cacheDir, "covers").apply { mkdirs() }
            val hash = MessageDigest.getInstance("SHA-1")
                .digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
            File(dir, "$hash.cover")
        }.getOrNull()
    }

    fun disconnect() {
        runCatching { client?.close() }
        client = null
        lastUrl = null
        lastEntries = emptyList()
        trackCovers.clear()
        folderCovers.clear()
    }
}
