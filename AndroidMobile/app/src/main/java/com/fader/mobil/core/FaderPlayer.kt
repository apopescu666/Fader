package com.fader.mobil.core

import android.content.Context
import android.content.Intent

/**
 * Thin façade over the ported ExoPlayer wrapper. Everything the UI needs is a
 * primitive or a callback, so package-private engine types never leak out.
 */
object FaderPlayer {

    fun interface Listener {
        fun onChanged()
    }

    private val listeners = mutableListOf<Listener>()
    private var bridge: PlayerCore.Listener? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (bridge != null) return
        appContext = context.applicationContext
        PlayerCore.init(context)
        val b = object : PlayerCore.Listener {
            override fun onChanged() {
                listeners.toList().forEach { it.onChanged() }
            }
        }
        bridge = b
        PlayerCore.INSTANCE.addListener(b)
    }

    private val core: PlayerCore get() = PlayerCore.INSTANCE

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun toggle() = core.toggle()
    fun setCrossfade(seconds: Int) = core.setCrossfade(seconds)
    fun setGapless(enabled: Boolean) = core.setGapless(enabled)
    fun play() = core.play()
    fun resumeRestored() = core.resumeRestored()
    fun pause() = core.pause()
    fun setShuffle(enabled: Boolean) = core.setShuffle(enabled)
    val shuffle: Boolean get() = core.isShuffle
    fun cycleRepeat() = core.cycleRepeat()
    val repeatMode: Int get() = core.repeat
    val currentSize: Long get() = core.currentSize()
    val currentName: String get() = core.currentName()
    val crossfade: Int get() = core.crossfade
    fun stop() = core.stop()
    fun next() = core.next()
    fun previous() = core.prev()
    fun seekTo(ms: Int) = core.seekTo(ms)

    val isPlaying: Boolean get() = core.isPlaying
    val positionMs: Int get() = core.position
    val durationMs: Int get() = core.duration

    val currentUrl: String? get() = core.current()?.entry?.url

    val title: String? get() = core.current()?.title
    val artist: String? get() = core.current()?.artist
    val album: String? get() = core.current()?.album

    val nextTitle: String? get() = core.peek(1)?.title

    /** Media item for the car session, with the auth handled by the data source. */
    fun queueEntries(): List<QueueEntry> = core.queueSnapshot()

    fun userQueueEntries(): List<QueueEntry> = core.userQueueSnapshot()

    fun playUserQueue(index: Int) = core.playUserQueue(index)

    fun removeUserQueueAt(index: Int) = core.removeUserQueueAt(index)

    fun addToUserQueue(urls: List<String>) {
        urls.forEach { url ->
            val remote = url.startsWith("http://") || url.startsWith("https://")
            val stream = if (remote) RemoteLibrary.remoteClient() else LocalLibrary.localClient()
            if (stream != null) core.addToUserQueue(stream, url)
        }
    }

    /** Mirrors the saved queue into the engine, so playback can start from it. */
    fun setUserQueue(items: List<com.fader.mobil.data.Favorite>) {
        val built = items.mapNotNull { favorite ->
            val remote = favorite.url.startsWith("http://") || favorite.url.startsWith("https://")
            val stream = if (remote) RemoteLibrary.remoteClient() else LocalLibrary.localClient()
            stream?.let {
                TrackItem(it, Entry(favorite.url.substringAfterLast('/'), favorite.url, false, 0, 0))
            }
        }
        core.replaceUserQueue(built)
    }

    /** Adds tracks to the queue (or right after the current one). */
    fun enqueue(urls: List<String>, playNext: Boolean) {
        urls.forEach { url ->
            val remote = url.startsWith("http://") || url.startsWith("https://")
            val stream = if (remote) RemoteLibrary.remoteClient() else LocalLibrary.localClient()
            if (stream != null) core.enqueue(stream, url, playNext)
        }
    }

    fun mediaItemForSession(url: String, artworkUri: String?): androidx.media3.common.MediaItem? {
        val remote = url.startsWith("http://") || url.startsWith("https://")
        val stream = if (remote) RemoteLibrary.remoteClient() else LocalLibrary.localClient()
        return stream?.let { PlayerCore.sessionItem(it, url, artworkUri) }
    }

    fun mediaItemForSession(
        url: String,
        title: String,
        artist: String,
        album: String,
        artworkUri: String?,
    ): androidx.media3.common.MediaItem? {
        val remote = url.startsWith("http://") || url.startsWith("https://")
        val stream = if (remote) RemoteLibrary.remoteClient() else LocalLibrary.localClient()
        return stream?.let { PlayerCore.sessionItem(it, url, title, artist, album, artworkUri) }
    }

    fun prepareExternalQueue() = core.prepareExternalQueue()

    fun journal(): List<String> = core.journalSnapshot()

    /** Rebuilds the saved queue and starts at the saved position. */
    fun restoreSession(tracks: List<com.fader.mobil.data.SessionTrack>, index: Int, positionMs: Int): Boolean {
        val items = tracks.mapNotNull { track ->
            val stream = if (track.remote) RemoteLibrary.remoteClient() else LocalLibrary.localClient()
            stream?.let {
                val item = TrackItem(it, Entry(track.url.substringAfterLast('/'), track.url, false, 0, 0))
                if (track.title.isNotBlank()) item.title = track.title
                if (track.artist.isNotBlank()) item.artist = track.artist
                item
            }
        }
        if (items.isEmpty()) return false
        core.play(items, index.coerceIn(0, items.lastIndex))
        core.seekTo(positionMs.coerceAtLeast(0))
        return true
    }

    /** Puts the last played song back in the player, paused, after a restart. */
    fun restoreLastTrack(
        url: String,
        title: String,
        artist: String,
        album: String,
        positionMs: Int,
    ): Boolean {
        val remote = url.startsWith("http://") || url.startsWith("https://")
        val stream = if (remote) RemoteLibrary.remoteClient() else LocalLibrary.localClient()
            ?: return false
        return core.restoreLastTrack(stream, url, title, artist, album, positionMs)
    }

    /** Lets the service record lifecycle events in the same journal. */
    fun note(message: String) = core.noteEvent(message)
    fun refresh() = core.refresh()

    val playingExternal: Boolean get() = core.isPlayingExternal
    val inUserQueue: Boolean get() = core.isInUserQueue()
    val currentContainer: String? get() = core.externalContainer

    fun enterCarMode() = core.enterCarMode()
    fun exitCarMode() = core.exitCarMode()
    val carMode: Boolean get() = core.isCarMode
    val sessionPlayer: androidx.media3.common.Player? get() = core.sessionPlayer()

    /** Plays search hits, used by the voice command MEDIA_PLAY_FROM_SEARCH. */
    fun playSearchResults(hits: List<SearchHit>, index: Int = 0) {
        if (hits.isEmpty()) return
        TrackQueue.play(hits.map { it.remote }, hits.map { it.url }, index)
    }

    /** Keeps playback alive in the background, also when the car started it. */
    fun ensureBackgroundService() = startPlaybackService()

    /** Last engine error, cleared on the next successful load. */
    val lastError: String? get() = core.lastError

    fun currentArt(): ArtworkRef? {
        val url = core.current()?.entry?.url ?: return null
        return LocalLibrary.art(url) ?: RemoteLibrary.art(url)
    }

    /** The ExoPlayer instance, for the MediaSession. */
    internal fun engine(): androidx.media3.common.Player? =
        if (bridge != null) core.engine() else null

    internal fun playQueue(items: List<TrackItem>, index: Int) {
        core.play(items, index)
        startPlaybackService()
    }

    /** Brings up the foreground media service so playback survives backgrounding. */
    private fun startPlaybackService() {
        val context = appContext ?: return
        runCatching {
            context.startForegroundService(Intent(context, com.fader.mobil.playback.PlaybackService::class.java))
        }
    }
}
