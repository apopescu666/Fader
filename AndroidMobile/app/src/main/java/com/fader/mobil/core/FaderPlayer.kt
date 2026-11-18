package com.fader.mobil.core

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player

/**
 * Façade over [EngineBackend]: everything the UI, the foreground service and the
 * car session need, expressed as primitives and callbacks.
 */
object FaderPlayer {

    fun interface Listener {
        fun onChanged()
    }

    private val listeners = mutableListOf<Listener>()
    private var bridge: EngineBackend.Listener? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var adapter: com.fader.mobil.engine.EngineAdapter? = null

    fun init(context: Context) {
        if (bridge != null) return
        appContext = context.applicationContext
        EngineBackend.init(context)
        val listener = EngineBackend.Listener {
            listeners.toList().forEach { runCatching { it.onChanged() } }
        }
        bridge = listener
        EngineBackend.addListener(listener)
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ---- transport -----------------------------------------------------

    fun toggle() = EngineBackend.toggle()
    fun play() = EngineBackend.play()
    fun pause() = EngineBackend.pause()
    fun resumeRestored() = EngineBackend.play()
    fun stop() = EngineBackend.stop()
    fun next() = EngineBackend.next()
    fun previous() = EngineBackend.previous()
    fun seekTo(ms: Int) = EngineBackend.seekTo(ms)

    val isPlaying: Boolean get() = EngineBackend.isPlaying
    val positionMs: Int get() = EngineBackend.positionMs
    val durationMs: Int get() = EngineBackend.durationMs

    val currentUrl: String? get() = EngineBackend.currentUrl
    val title: String? get() = EngineBackend.title
    val artist: String? get() = EngineBackend.artist
    val album: String? get() = EngineBackend.album
    val nextTitle: String? get() = EngineBackend.nextTitle

    val currentSize: Long get() = EngineBackend.currentSize
    val currentName: String get() = EngineBackend.currentName

    // ---- settings ------------------------------------------------------

    fun setCrossfade(seconds: Int) = EngineBackend.setCrossfade(seconds)
    fun applyCrossfade(seconds: Int) = EngineBackend.applyCrossfade(seconds)
    val crossfade: Int get() = EngineBackend.crossfade
    fun setGapless(enabled: Boolean) = EngineBackend.setGapless(enabled)

    fun setShuffle(enabled: Boolean) = EngineBackend.setShuffle(enabled)
    val shuffle: Boolean get() = EngineBackend.shuffle
    fun cycleRepeat() = EngineBackend.cycleRepeat()
    val repeatMode: Int get() = EngineBackend.repeat

    // ---- queue ---------------------------------------------------------

    fun queueEntries(): List<QueueEntry> = EngineBackend.queueSnapshot()

    fun queueSignature(): String = EngineBackend.queueSignature()

    val queueIndex: Int get() = EngineBackend.queueIndex

    fun userQueueEntries(): List<QueueEntry> = EngineBackend.userQueueSnapshot()

    fun playUserQueue(index: Int) = EngineBackend.playUserQueue(index)

    fun removeUserQueueAt(index: Int) = EngineBackend.removeUserQueueAt(index)

    fun addToUserQueue(urls: List<String>) {
        urls.forEach { url ->
            if (url.isBlank()) return@forEach
            EngineBackend.addToUserQueue(trackFor(url))
        }
    }

    /** Mirrors the saved queue into the player, so playback can start from it. */
    fun setUserQueue(items: List<com.fader.mobil.data.Favorite>) {
        EngineBackend.replaceUserQueue(items.map { favorite ->
            PlayTrack(
                url = favorite.url,
                remote = favorite.remote ||
                    favorite.url.startsWith("http://") ||
                    favorite.url.startsWith("https://"),
                title = favorite.title.ifBlank {
                    RemoteLibrary.decodeName(favorite.url.substringAfterLast('/'))
                },
                artist = favorite.artist,
                album = favorite.album,
                headers = EngineBackend.headersFor(favorite.url),
            )
        })
    }

    /** Adds tracks to the queue (or right after the current one). */
    fun enqueue(urls: List<String>, playNext: Boolean) {
        addToUserQueue(urls)
    }

    private fun trackFor(url: String): PlayTrack {
        val remote = url.startsWith("http://") || url.startsWith("https://")
        val stream = if (remote) RemoteLibrary.remoteClient() else LocalLibrary.localClient()
        if (stream != null) {
            return TrackItem(
                stream,
                Entry(RemoteLibrary.decodeName(url.substringAfterLast('/')), url, false, 0, 0),
            )
                .toPlayTrack()
        }
        return PlayTrack(
            url = url,
            remote = remote,
            title = RemoteLibrary.decodeName(
                url.substringAfterLast('/').substringBeforeLast('.'),
            ),
            artist = "Unknown artist",
            album = "",
            headers = EngineBackend.headersFor(url),
        )
    }

    // ---- session / car -------------------------------------------------

    fun mediaItemForSession(
        url: String,
        artworkUri: String?,
    ): androidx.media3.common.MediaItem? = SessionItems.item(
        url, null, null, null,
        runCatching { com.fader.mobil.data.CoverProvider.uriFor(url).toString() }
            .getOrDefault(artworkUri),
    )

    fun mediaItemForSession(
        url: String,
        title: String,
        artist: String,
        album: String,
        artworkUri: String?,
        container: String? = null,
    ): androidx.media3.common.MediaItem? = SessionItems.item(
        url, title, artist, album,
        runCatching { com.fader.mobil.data.CoverProvider.uriFor(url).toString() }
            .getOrDefault(artworkUri),
        container,
    )

    fun prepareExternalQueue() = EngineBackend.prepareExternalQueue()

    fun journal(): List<String> = EngineBackend.journalSnapshot

    /** Rebuilds the saved queue and starts at the saved position. */
    fun restoreSession(
        tracks: List<com.fader.mobil.data.SessionTrack>,
        index: Int,
        positionMs: Int,
    ): Boolean {
        val plays = EngineBackend.sessionTracks(tracks)
        if (plays.isEmpty()) return false
        return EngineBackend.restoreSession(plays, index, positionMs)
    }

    /** Puts the last played song back in the player, paused, after a restart. */
    fun restoreLastTrack(
        url: String,
        title: String,
        artist: String,
        album: String,
        positionMs: Int,
    ): Boolean {
        if (url.isBlank()) return false
        return EngineBackend.restoreLastTrack(
            PlayTrack(
                url = url,
                remote = url.startsWith("http://") || url.startsWith("https://"),
                title = title.ifBlank {
                    RemoteLibrary.decodeName(url.substringAfterLast('/'))
                },
                artist = artist,
                album = album,
                headers = EngineBackend.headersFor(url),
            ),
            positionMs,
        )
    }

    fun note(message: String) = EngineBackend.note(message)

    /** Late NAS connect: fill the auth headers of the restored queue. */
    fun refreshRemoteHeaders() = EngineBackend.refreshRemoteHeaders()
    fun refresh() = EngineBackend.refresh()

    val playingExternal: Boolean get() = EngineBackend.isPlayingExternal
    val inUserQueue: Boolean get() = EngineBackend.isInUserQueue
    val currentContainer: String? get() = EngineBackend.externalContainerUrl

    fun enterCarMode() = EngineBackend.enterCarMode()
    fun resetForCarPlayback() = EngineBackend.resetForCarPlayback()
    fun exitCarMode() = EngineBackend.exitCarMode()
    val carMode: Boolean get() = EngineBackend.isCarMode

    /** The Media3 player the car session binds to (a thin view over the engine). */
    val sessionPlayer: Player?
        get() {
            if (bridge == null) return null
            adapter?.let { return it }
            val created = runCatching { com.fader.mobil.engine.EngineAdapter() }.getOrNull() ?: return null
            adapter = created
            return created
        }

    /** Plays search hits, used by the voice command MEDIA_PLAY_FROM_SEARCH. */
    fun playSearchResults(hits: List<SearchHit>, index: Int = 0) {
        if (hits.isEmpty()) return
        TrackQueue.play(hits.map { it.remote }, hits.map { it.url }, index)
    }

    /** Keeps playback alive in the background, also when the car started it. */
    fun ensureBackgroundService() = startPlaybackService()

    /** Last engine error, cleared on the next successful load. */
    val lastError: String? get() = EngineBackend.lastError

    fun currentArt(): ArtworkRef? {
        val url = EngineBackend.currentTrack()?.url ?: return null
        return LocalLibrary.art(url) ?: RemoteLibrary.art(url)
    }

    /** Kept for the car session: the same adapter is the Media3 player. */
    internal fun engine(): Player? = sessionPlayer

    internal fun playQueue(items: List<TrackItem>, index: Int) {
        if (items.isEmpty()) return
        EngineBackend.playTracks(items.map { it.toPlayTrack() }, index)
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
