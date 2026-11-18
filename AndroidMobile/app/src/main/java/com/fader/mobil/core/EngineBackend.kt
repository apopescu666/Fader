package com.fader.mobil.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fader.mobil.data.SettingsStore
import com.fader.mobil.data.SessionTrack
import com.fader.mobil.engine.EngineSnapshot
import com.fader.mobil.engine.MixerEngine
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-wide playback over the custom [MixerEngine]: one queue, one output, the
 * same behaviour on the phone, in the notification and in Android Auto.
 *
 * This replaces the old ExoPlayer façade. It keeps the same public shape so the
 * UI, the foreground service and the car session need no changes.
 */
object EngineBackend {

    fun interface Listener {
        fun onChanged()
    }

    private val listeners = ArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    /** Engine loads happen off the main thread: opening a NAS track can block. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fader-engine-load").apply { isDaemon = true }
    }

    private const val TICK_MS = 500L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engineRef: MixerEngine? = null

    /**
     * Last engine state, refreshed off the main thread. Reading it never waits
     * for the mixer lock, which can be held while a NAS stream opens.
     */
    @Volatile
    private var cached = EngineSnapshot()

    /** Title of the track the engine would start next, refreshed off-main. */
    @Volatile
    private var cachedNextTitle: String? = null

    private val statePoller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fader-state").apply { isDaemon = true }
    }

    private val queue = ArrayList<PlayTrack>()
    /** Visible queue index; the engine catches up after it opens the stream. */
    private var index = 0
    private val userQueue = ArrayList<PlayTrack>()
    private var inUserQueue = false
    private var returnQueue: List<PlayTrack>? = null
    private var returnIndex = 0

    private var crossfadeSeconds = 0
    private var gaplessEnabled = true
    private var shuffleEnabled = false
    /** 0 = off, 1 = repeat queue, 2 = repeat one. */
    private var repeatMode = 1
    private var externalContainer: String? = null
    private var playingExternal = false
    private var carMode = false
    private var lastFailure: String? = null
    private val journal = ArrayDeque<String>()

    private var ticking = false

    // ---- lifecycle -----------------------------------------------------

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        crossfadeSeconds = runCatching { SettingsStore.fade(app) }.getOrDefault(0)
        shuffleEnabled = runCatching { SettingsStore.shuffle(app) }.getOrDefault(false)
        statePoller.scheduleWithFixedDelay(
            {
                val engine = engineRef ?: return@scheduleWithFixedDelay
                val snapshot = runCatching { engine.snapshot() }.getOrNull()
                if (snapshot != null) cached = snapshot
                cachedNextTitle = runCatching { engine.peekNext()?.title }.getOrNull()
            },
            0,
            400,
            TimeUnit.MILLISECONDS,
        )
        startTicker()
    }

    /** The engine instance, created on first use. */
    fun engine(): MixerEngine? {
        engineRef?.let { return it }
        val context = appContext ?: return null
        val created = runCatching { MixerEngine(context) }.getOrNull() ?: return null
        created.setCrossfadeMs(crossfadeSeconds * 1000)
        created.setRepeatMode(repeatMode)
        created.setShuffle(shuffleEnabled)
        engineRef = created
        return created
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        main.postDelayed(object : Runnable {
            override fun run() {
                if (listeners.isNotEmpty()) notifyChanged()
                main.postDelayed(this, TICK_MS)
            }
        }, TICK_MS)
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.toList().forEach { listener ->
            runCatching { listener.onChanged() }
        }
    }

    // ---- state ---------------------------------------------------------

    fun snapshot(): EngineSnapshot {
        val visible = currentIndex()
        val track = queue.getOrNull(visible) ?: return cached
        val sameQueue = cached.queueSize == queue.size
        return EngineSnapshot(
            source = track.toSource(),
            title = track.title.ifBlank {
                RemoteLibrary.decodeName(track.url.substringAfterLast('/'))
            },
            artist = track.artist.ifBlank { "Unknown artist" },
            positionMs = if (sameQueue) cached.positionMs else 0,
            durationMs = if (sameQueue && cached.durationMs > 0) {
                cached.durationMs
            } else {
                durationOf(track)
            },
            playing = cached.playing,
            index = visible,
            queueSize = queue.size,
            fading = if (sameQueue) cached.fading else false,
        )
    }

    private fun currentIndex(): Int {
        // Trust the engine only while it plays the queue we show.
        val visible = if (cached.queueSize == queue.size) cached.index else index
        return if (queue.isEmpty()) 0 else visible.coerceIn(0, queue.lastIndex)
    }

    private fun durationOf(track: PlayTrack): Long {
        val measured = runCatching { com.fader.mobil.engine.DurationReader.cached(track.url) }
            .getOrDefault(0)
        return when {
            measured > 0 -> measured
            track.durationMs > 0 -> track.durationMs
            else -> 0
        }
    }

    val isPlaying: Boolean
        get() = cached.playing

    val positionMs: Int
        get() = cached.positionMs.toInt().coerceAtLeast(0)

    val durationMs: Int
        get() {
            val current = currentTrack() ?: return 0
            val measured = if (cached.queueSize == queue.size) cached.durationMs else 0
            return (if (measured > 0) measured else durationOf(current))
                .toInt()
                .coerceAtLeast(0)
        }

    fun currentTrack(): PlayTrack? = queue.getOrNull(currentIndex())

    val currentUrl: String?
        get() = currentTrack()?.url

    val title: String?
        get() = currentTrack()?.title
            ?.let { RemoteLibrary.decodeName(it) }
            ?.takeIf { it.isNotBlank() }

    val artist: String?
        get() = currentTrack()?.artist
            ?.let { RemoteLibrary.decodeName(it) }
            ?.takeIf { it.isNotBlank() }

    val album: String?
        get() = currentTrack()?.album
            ?.let { RemoteLibrary.decodeName(it) }
            ?.takeIf { it.isNotBlank() }

    val nextTitle: String?
        get() {
            cachedNextTitle?.takeIf { it.isNotBlank() }?.let { return it }
            // With shuffle the list order says nothing about what plays next.
            if (shuffleEnabled) return null
            val local = queue.getOrNull(currentIndex() + 1) ?: return null
            return local.title.takeIf { it.isNotBlank() }
        }

    val currentSize: Long
        get() = currentTrack()?.sizeBytes ?: 0L

    /** File name (with extension), as the Info dialog expects. */
    val currentName: String
        get() {
            val url = currentTrack()?.url.orEmpty()
            val name = url.substringAfterLast('/')
            return if (name.contains('%')) {
                runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
            } else {
                name
            }
        }

    val lastError: String?
        get() = lastFailure ?: engineRef?.lastError()

    val journalSnapshot: List<String>
        get() = synchronized(journal) { journal.toList() }

    // ---- queue ---------------------------------------------------------

    private fun startQueue(tracks: List<PlayTrack>, index: Int, positionMs: Long, play: Boolean) {
        if (tracks.isEmpty()) return
        queue.clear()
        queue.addAll(tracks)
        val start = index.coerceIn(0, tracks.lastIndex)
        this.index = start
        lastFailure = null
        note("queue ${tracks.size}, start $start: ${tracks[start].title}")
        val engine = engine() ?: return
        val sources = tracks.map { it.toSource() }
        // Car list changes fade out/in instead of a hard cut.
        val carFade = playingExternal && engine.snapshot().playing
        worker.execute {
            runCatching {
                engine.setCrossfadeMs(crossfadeSeconds * 1000)
                engine.setRepeatMode(repeatMode)
                engine.setShuffle(shuffleEnabled)
                if (carFade) {
                    engine.switchQueueFading(
                        sources,
                        start,
                        positionMs.coerceAtLeast(0),
                        600,
                        play,
                    )
                    return@runCatching
                }
                engine.setQueue(sources, start, positionMs.coerceAtLeast(0))
                if (play) engine.play() else engine.pause()
                if (positionMs > 0 && !play) engine.seekTo(positionMs)
            }.onFailure { error ->
                lastFailure = error.message ?: "Playback failed"
                note("queue failed: ${error.javaClass.simpleName}")
            }
            main.post { notifyChanged() }
        }
        notifyChanged()
    }

    fun playTracks(tracks: List<PlayTrack>, index: Int) {
        inUserQueue = false
        returnQueue = null
        playingExternal = false
        externalContainer = null
        startQueue(tracks, index, 0, play = true)
    }

    /** Items handed over by Android Auto; they keep their own queue. */
    fun playCarTracks(tracks: List<PlayTrack>, index: Int, positionMs: Long, container: String?) {
        inUserQueue = false
        returnQueue = null
        playingExternal = true
        externalContainer = container
        startQueue(tracks, index, positionMs, play = true)
    }

    // ---- transport -----------------------------------------------------

    fun toggle() {
        val engine = engineRef ?: return
        note(if (engine.snapshot().playing) "pause" else "play")
        if (engine.snapshot().playing) engine.pause() else engine.play()
        notifyChanged()
    }

    fun play() {
        val engine = engineRef ?: return
        note("play")
        engine.play()
        notifyChanged()
    }

    fun pause() {
        val engine = engineRef ?: return
        note("pause")
        engine.pause()
        notifyChanged()
    }

    fun next() {
        note("next")
        engineRef?.next()
        notifyChanged()
    }

    fun previous() {
        note("previous")
        engineRef?.previous()
        notifyChanged()
    }

    fun seekTo(ms: Int) {
        engineRef?.seekTo(ms.toLong().coerceAtLeast(0))
        notifyChanged()
    }

    fun jumpTo(target: Int, positionMs: Long = 0) {
        val safe = target.coerceIn(0, (queue.lastIndex).coerceAtLeast(0))
        note("jump to $safe @${positionMs / 1000}s")
        index = safe
        val engine = engineRef
        if (engine == null || cached.queueSize != queue.size) {
            // The engine is still on another list (e.g. car items arrived
            // before the queue reached it): restart it at the target.
            startQueue(ArrayList(queue), safe, positionMs, play = true)
            return
        }
        engine.jumpTo(safe, positionMs)
        notifyChanged()
    }

    fun stop() {
        note("stop")
        val engine = engineRef ?: return
        engine.pause()
        controllerQueueClear()
        notifyChanged()
    }

    private fun controllerQueueClear() {
        queue.clear()
        index = 0
        userQueue.clear()
        inUserQueue = false
        returnQueue = null
        playingExternal = false
        externalContainer = null
        engineRef?.setQueue(emptyList(), 0, 0)
    }

    // ---- settings ------------------------------------------------------

    fun setCrossfade(seconds: Int) {
        crossfadeSeconds = seconds.coerceIn(0, 30)
        engineRef?.setCrossfadeMs(crossfadeSeconds * 1000)
        notifyChanged()
    }

    fun applyCrossfade(seconds: Int) {
        crossfadeSeconds = seconds.coerceIn(0, 30)
        engineRef?.setCrossfadeMs(crossfadeSeconds * 1000)
    }

    val crossfade: Int
        get() = crossfadeSeconds

    fun setGapless(enabled: Boolean) {
        gaplessEnabled = enabled
    }

    fun setShuffle(enabled: Boolean) {
        shuffleEnabled = enabled
        engineRef?.setShuffle(enabled)
        notifyChanged()
    }

    val shuffle: Boolean
        get() = shuffleEnabled

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        engineRef?.setRepeatMode(repeatMode)
        notifyChanged()
    }

    val repeat: Int
        get() = repeatMode

    // ---- user queue ----------------------------------------------------

    fun replaceUserQueue(tracks: List<PlayTrack>) {
        userQueue.clear()
        userQueue.addAll(tracks)
        if (!inUserQueue) notifyChanged()
    }

    fun addToUserQueue(track: PlayTrack) {
        if (userQueue.any { it.url == track.url }) return
        userQueue.add(track)
        if (inUserQueue) {
            queue.add(track)
            engineRef?.addSource(track.toSource())
            notifyChanged()
        }
    }

    fun userQueueSnapshot(): List<QueueEntry> = userQueue.map { it.toQueueEntry() }

    fun playUserQueue(index: Int) {
        if (userQueue.isEmpty()) return
        if (!inUserQueue) {
            returnQueue = ArrayList(queue)
            returnIndex = (currentIndex() + 1).coerceAtLeast(0)
        }
        inUserQueue = true
        playingExternal = false
        externalContainer = null
        startQueue(ArrayList(userQueue), index, 0, play = true)
    }

    fun removeUserQueueAt(at: Int) {
        if (at !in userQueue.indices) return
        userQueue.removeAt(at)
        if (inUserQueue) {
            val position = positionMs.toLong()
            val wasPlaying = isPlaying
            val visible = currentIndex()
            val cut = at.coerceIn(0, (queue.lastIndex).coerceAtLeast(0))
            if (cut in queue.indices) queue.removeAt(cut)
            if (queue.isNotEmpty()) {
                val next = visible.coerceIn(0, queue.lastIndex)
                startQueue(ArrayList(queue), next, position, play = wasPlaying)
            }
        }
        notifyChanged()
    }

    fun exitUserQueue() {
        inUserQueue = false
        val target = returnQueue
        returnQueue = null
        if (target.isNullOrEmpty()) {
            stop()
            return
        }
        startQueue(target, returnIndex, 0, play = true)
    }

    private fun PlayTrack.toQueueEntry() = QueueEntry(url, title, artist, album, remote)

    fun queueSnapshot(): List<QueueEntry> = queue.map { it.toQueueEntry() }

    /** Cheap fingerprint of the list, so big folders are not mapped every tick. */
    fun queueSignature(): String =
        "${queue.size}|${queue.firstOrNull()?.url.orEmpty()}|${queue.lastOrNull()?.url.orEmpty()}"

    /** Index of the track that is playing, without building the whole list. */
    val queueIndex: Int get() = currentIndex()

    val isInUserQueue: Boolean
        get() = inUserQueue

    val isPlayingExternal: Boolean
        get() = playingExternal

    val externalContainerUrl: String?
        get() = externalContainer

    // ---- restore -------------------------------------------------------

    fun restoreSession(tracks: List<PlayTrack>, index: Int, positionMs: Int): Boolean {
        if (tracks.isEmpty()) return false
        inUserQueue = false
        returnQueue = null
        playingExternal = false
        externalContainer = null
        startQueue(tracks, index, positionMs.coerceAtLeast(0).toLong(), play = true)
        return true
    }

    /** Brings the last song back, paused, at its saved position. */
    fun restoreLastTrack(track: PlayTrack, positionMs: Int): Boolean {
        inUserQueue = false
        returnQueue = null
        playingExternal = false
        externalContainer = null
        queue.clear()
        queue.add(track)
        index = 0
        val engine = engine() ?: return false
        worker.execute {
            runCatching {
                engine.setQueue(listOf(track.toSource()), 0, positionMs.coerceAtLeast(0).toLong())
                engine.setCrossfadeMs(crossfadeSeconds * 1000)
                engine.pause()
            }
            main.post { notifyChanged() }
        }
        note("restored paused: ${track.title}")
        notifyChanged()
        return true
    }

    // ---- car mode ------------------------------------------------------

    fun enterCarMode() {
        carMode = true
    }

    fun exitCarMode() {
        carMode = false
        playingExternal = false
    }

    val isCarMode: Boolean
        get() = carMode

    fun resetForCarPlayback() {
        engineRef?.setCrossfadeMs(crossfadeSeconds * 1000)
        note("car: settings reset")
        notifyChanged()
    }

    fun prepareExternalQueue() = Unit

    // ---- journal -------------------------------------------------------

    /** After a late NAS connect: let queued remote tracks authenticate. */
    fun refreshRemoteHeaders() {
        val header = runCatching { RemoteLibrary.authHeader }.getOrNull() ?: return
        engineRef?.updateRemoteHeaders(mapOf("Authorization" to header))
    }

    fun note(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(journal) {
            journal.addLast("$time  $message")
            while (journal.size > 120) journal.removeFirst()
        }
    }

    fun refresh() {
        notifyChanged()
    }

    /** Builds the queue from saved session rows (app start, headset, car). */
    fun sessionTracks(tracks: List<SessionTrack>): List<PlayTrack> = tracks.map { track ->
        PlayTrack(
            url = track.url,
            remote = track.remote || track.url.startsWith("http://") ||
                track.url.startsWith("https://"),
            title = track.title.ifBlank {
                RemoteLibrary.decodeName(track.url.substringAfterLast('/'))
            },
            artist = track.artist.ifBlank { "Unknown artist" },
            album = "",
            headers = headersFor(track.url),
        )
    }

    /** Auth headers for a saved remote URL, when the NAS is already connected. */
    fun headersFor(url: String): Map<String, String> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyMap()
        val header = runCatching { RemoteLibrary.authHeader }.getOrNull() ?: return emptyMap()
        return mapOf("Authorization" to header)
    }
}
