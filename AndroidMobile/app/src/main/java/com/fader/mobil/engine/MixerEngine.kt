package com.fader.mobil.engine

import android.content.Context
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * M2: two decoders mixed into a single AudioTrack with a sample-accurate
 * equal-power envelope. Sources whose format differs from the mixer fall back
 * to a direct switch (no overlap) instead of mixing wrong audio.
 */
class MixerEngine(private val context: Context) : PlaybackEngine,
    android.media.AudioManager.OnAudioFocusChangeListener {

    private val lock = Any()
    private var queue: List<AudioSource> = emptyList()
    private var index = 0
    private var displayIndex = 0
    private var crossfadeMs = 5000
    private var repeatMode = 1
    private var shuffle = false
    private val shuffleOrder = ArrayList<Int>()
    private var playing = false
    private var running = true

    private var mixerRate = 0
    private var sink: AudioSink? = null
    private var a: PcmStream? = null
    private var b: PcmStream? = null
    private var fading = false
    private var fadeFrames = 0L
    private var fadePos = 0L
    private var pendingIndex = -1
    private var renderedFrames = 0L
    private var baseMs = 0L
    private var lastError: String? = null
    private var startRequestedAt = 0L
    private var firstAudioMs = 0L
    private var lastTraceMs = 0L
    private var peak = 0
    private var lastLevelMs = 0L
    private var silentMs = 0L
    private var endSilenceMs = 0L
    private val recoveredUrls = HashSet<String>()

    @Volatile
    private var nextSinkVolume = 1f

    private var preparedTarget = -1
    private var preparedStream: PcmStream? = null
    private var preparing = false

    private val blockFrames = 2048
    private val blockA = ShortArray(blockFrames * 2)
    private val blockB = ShortArray(blockFrames * 2)
    private val blockOut = ShortArray(blockFrames * 2)

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    private var resumeAfterFocus = false
    private val noisy = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                EngineTrace.add("headphones unplugged -> pause")
                pause()
            }
        }
    }

    init {
        runCatching {
            context.registerReceiver(
                noisy,
                android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            )
        }
        // The saved user setting, applied before anything plays.
        runCatching {
            setCrossfadeMs(com.fader.mobil.data.SettingsStore.fade(context) * 1000)
            shuffle = com.fader.mobil.data.SettingsStore.shuffle(context)
        }
    }

    private fun requestFocus(): Boolean {
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN,
        )
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(this)
            .setWillPauseWhenDucked(false)
            .build()
        return audioManager.requestAudioFocus(request) ==
            android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        runCatching {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN,
            ).setAudioAttributes(attrs).build()
            audioManager.abandonAudioFocusRequest(request)
        }
    }

    override fun onAudioFocusChange(change: Int) {
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                EngineTrace.add("focus lost")
                resumeAfterFocus = false
                pause()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                EngineTrace.add("focus transient")
                synchronized(lock) { resumeAfterFocus = playing }
                pause()
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                EngineTrace.add("focus gain")
                if (resumeAfterFocus) {
                    resumeAfterFocus = false
                    play()
                }
            }
        }
    }

    private val worker = Thread({ loop() }, "fader-mixer").apply {
        priority = Thread.NORM_PRIORITY + 1
        start()
    }

    private fun loop() {
        while (running) {
            try {
                var active: PcmStream? = null
                var incoming: PcmStream? = null
                var out: AudioSink? = null
                var isPlaying = false
                var isFading = false
                var fadeStart = 0L
                var fadeTotal = 0L
                var rate = 0
                synchronized(lock) {
                    active = a
                    incoming = b
                    out = sink
                    isPlaying = playing
                    isFading = fading
                    fadeStart = fadePos
                    fadeTotal = fadeFrames
                    rate = mixerRate
                }
                if (active == null || out == null || !isPlaying) {
                    sleepQuietly(20)
                    continue
                }

                if (isFading && incoming != null) {
                    val readA = active.readBlock(blockA, blockFrames)
                    val readB = incoming.readBlock(blockB, blockFrames)
                    val frames = if (readA > 0) readA else if (readB > 0) readB else 0
                    if (frames <= 0) {
                        finishFade()
                        continue
                    }
                    val progress = if (fadeTotal <= 0) 1f else (fadeStart.toFloat() / fadeTotal).coerceIn(0f, 1f)
                    val gainA = cos(progress * PI.toFloat() / 2f)
                    val gainB = sin(progress * PI.toFloat() / 2f)
                    for (i in 0 until frames * 2) {
                        val va = if (readA > 0 && i < readA * 2) blockA[i] else 0
                        val vb = if (readB > 0 && i < readB * 2) blockB[i] else 0
                        blockOut[i] = (va * gainA + vb * gainB).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    out.write(shortsToBytes(blockOut, frames * 2), frames * 4)
                    synchronized(lock) {
                        fadePos += frames
                        renderedFrames += frames
                        if (fadePos >= fadeFrames) {
                            // handled below, outside the lock
                        }
                    }
                    if (fadeStart + frames >= fadeTotal) finishFade()
                    continue
                }

                // Start the automatic fade when the current track is ending. The
                // incoming stream is opened a few seconds early so the fade never
                // waits for a NAS file at the exact moment it should overlap.
                val next = nextIndexLocked()
                if (next >= 0 && crossfadeMs > 0) {
                    val position = positionMsLocked()
                    val duration = durationMsLocked()
                    val remaining = if (duration > 0) duration - position else -1
                    if (remaining > 0 && remaining <= crossfadeMs + 6_000L) {
                        prepareAsync(next)
                    }
                    if (duration > 0 && remaining <= crossfadeMs && position >= crossfadeMs) {
                        if (startFadeLocked(next)) continue
                    }
                }

                val read = active.readBlock(blockA, blockFrames)
                if (read <= 0) {
                    onTrackEnded()
                    continue
                }
                out.write(shortsToBytes(blockA, read * 2), read * 4)
                if (firstAudioMs == 0L && startRequestedAt > 0L) {
                    firstAudioMs = System.currentTimeMillis()
                    EngineTrace.add("first audio after ${firstAudioMs - startRequestedAt} ms")
                }
                var blockPeak = 0
                for (i in 0 until read * 2) {
                    val v = kotlin.math.abs(blockA[i].toInt())
                    if (v > blockPeak) blockPeak = v
                }
                if (blockPeak > peak) peak = blockPeak
                val nowLevel = System.currentTimeMillis()
                val durationNow = durationMsLocked()
                val positionNow = positionMsLocked()
                val nearEnd = durationNow > 0 && positionNow > durationNow - 20_000
                if (blockPeak < 50) {
                    silentMs += read * 1000L / mixerRate.coerceAtLeast(1)
                    if (nearEnd) {
                        endSilenceMs += read * 1000L / mixerRate.coerceAtLeast(1)
                    } else {
                        endSilenceMs = 0
                    }
                } else {
                    silentMs = 0
                    endSilenceMs = 0
                }
                if (endSilenceMs > 2500) {
                    // Trailing silence: the music is over even if the file is longer.
                    endSilenceMs = 0
                    silentMs = 0
                    val next = nextIndexLocked()
                    if (next >= 0 && !fading && playing && queue.size > 1) {
                        if (startFadeLocked(next)) {
                            EngineTrace.add("silence end -> fade $next")
                            continue
                        }
                    }
                }
                if (nowLevel - lastLevelMs > 5000) {
                    lastLevelMs = nowLevel
                    EngineTrace.add("level=$peak pos=${positionMsLocked() / 1000}s")
                    peak = 0
                }
                if (silentMs > 12_000 && !nearEnd) {
                    // The decoder is emitting silence: reopen the same spot once.
                    silentMs = 0
                    val at = positionMsLocked()
                    EngineTrace.add("silent 12s -> reopen at ${at / 1000}s")
                    switchTo(index)
                    seekTo(at)
                }
                synchronized(lock) { renderedFrames += read }
                val nowTrace = System.currentTimeMillis()
                if (nowTrace - lastTraceMs > 15_000) {
                    lastTraceMs = nowTrace
                    EngineTrace.add(
                        "pos=${positionMsLocked() / 1000}s playing=$playing sinkPlaying=${sink?.isPlaying()}",
                    )
                }
            } catch (error: Throwable) {
                EngineTrace.add("mixer error: ${error.javaClass.simpleName}: ${error.message}")
                synchronized(lock) {
                    a?.release()
                    b?.release()
                    a = null
                    b = null
                    fading = false
                    pendingIndex = -1
                    playing = false
                }
                sleepQuietly(250)
            }
        }
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = shorts[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** Opens the next track in the background, reusing it when the fade starts. */
    private fun prepareAsync(target: Int) {
        synchronized(lock) {
            if (preparing || preparedTarget == target || target !in queue.indices) return
            preparing = true
        }
        val sources = queue
        val rate = mixerRate
        Thread {
            val stream = PcmStream(context, sources[target], rate, 2)
            val ok = stream.open() && stream.compatible
            synchronized(lock) {
                preparing = false
                if (ok) {
                    preparedStream?.release()
                    preparedStream = stream
                    preparedTarget = target
                    EngineTrace.add("preloaded ${sources[target].title}")
                } else {
                    stream.release()
                }
            }
        }.start()
    }

    private fun takePreparedLocked(target: Int): PcmStream? {
        val stream = preparedStream
        if (preparedTarget == target && stream != null) {
            preparedStream = null
            preparedTarget = -1
            return stream
        }
        return null
    }

    private fun discardPreparedLocked(keepFor: Int) {
        if (preparedTarget != keepFor) {
            preparedStream?.release()
            preparedStream = null
            preparedTarget = -1
        }
    }

    private fun startFadeLocked(target: Int): Boolean {
        val active = a ?: return false
        if (mixerRate == 0) return false
        // A second Next during the fade must not leak the pending stream.
        b?.release()
        b = null
        var stream = takePreparedLocked(target)
        if (stream == null) {
            stream = PcmStream(context, queue[target], mixerRate, 2)
            if (!stream.open() || !stream.compatible) {
                val channels = stream.channels
                stream.release()
                // One retry: a NAS open can fail on a transient network hiccup.
                stream = PcmStream(context, queue[target], mixerRate, 2)
                if (!stream.open() || !stream.compatible) {
                    stream.release()
                    EngineTrace.add("fade open failed: ch=$channels ${queue[target].title}")
                    switchTo(target)
                    return true
                }
                EngineTrace.add("fade open retry ok: ${queue[target].title}")
            }
        }
        b = stream
        displayIndex = target
        EngineTrace.add("fade start -> $target (${queue.getOrNull(target)?.title})")
        pendingIndex = target
        // Measure the incoming and the following track so the next automatic
        // fade also knows the real duration.
        queue.getOrNull(target)?.let { source ->
            val local = if (source.remote) {
                AudioDecoder.cachePath(context, source)?.absolutePath
            } else {
                source.url
            }
            DurationReader.ensure(source, local)
        }
        fading = true
        fadeFrames = (crossfadeMs.toLong() * mixerRate / 1000L).coerceAtLeast(1)
        fadePos = 0
        return true
    }

    private fun finishFade() {
        synchronized(lock) {
            val old = a
            a = b
            b = null
            // Prev and shuffle can land on a lower index: clamping to the old
            // one left the title on the previous track while the new audio
            // played. The pending index is always a valid queue index.
            index = if (pendingIndex in queue.indices) pendingIndex else index
            displayIndex = index
            pendingIndex = -1
            fading = false
            // The incoming track already played the whole fade: its position
            // starts at the fade length, not at zero.
            val fadeMs = fadeFrames * 1000L / mixerRate.coerceAtLeast(1)
            fadeFrames = 0
            fadePos = 0
            EngineTrace.add("fade done -> $index")
            old?.release()
            // Some devices pause the AudioTrack after a long underrun.
            sink?.setVolume(1f)
            sink?.play()
            baseMs = fadeMs
            renderedFrames = 0
            queue.getOrNull(index)?.let { DurationReader.ensure(it) }
            queue.getOrNull(index + 1)?.let { DurationReader.ensure(it) }
        }
    }

    private fun onTrackEnded() {
        synchronized(lock) {
            val position = positionMsLocked()
            val duration = durationMsLocked()
            val source = queue.getOrNull(index)
            if (source != null && duration > 0 && position < duration - 8000 &&
                !recoveredUrls.contains(source.url)
            ) {
                if (recoveredUrls.size > 200) recoveredUrls.clear()
                recoveredUrls.add(source.url)
                EngineTrace.add("early end at ${position / 1000}s, re-download")
                AudioDecoder.clearCache(context, source)
                switchTo(index)
                seekTo(position)
                return
            }
            if (repeatMode == 2) {
                a?.seekTo(0)
                renderedFrames = 0
                baseMs = 0
                return
            }
            val next = nextIndexLocked()
            if (next >= 0) {
                if (crossfadeMs > 0 && !fading && playing && queue.size > 1) {
                    // The overlap could not be timed (file end differs from the
                    // reported duration): fade the next track in from silence.
                    if (startFadeLocked(next)) {
                        EngineTrace.add("fade start at end -> $next")
                        return
                    }
                }
                switchTo(next)
            } else {
                playing = false
                sink?.pause()
            }
        }
    }

    private fun switchTo(target: Int) {
        discardPreparedLocked(target)
        val old = a
        b?.release()
        b = null
        fading = false
        pendingIndex = -1
        if (queue.isEmpty()) return
        val stream = PcmStream(context, queue[target], mixerRate, 2)
        if (!stream.open() || !stream.compatible) {
            stream.release()
            lastError = "cannot play ${queue[target].url}"
            EngineTrace.add("open failed: ${queue[target].title}")
            Log.w("FaderMixer", lastError.orEmpty())
            old?.release()
            a = null
            return
        }
        a = stream
        index = target
        displayIndex = target
        EngineTrace.add("switch -> $target (${queue.getOrNull(target)?.title})")
        renderedFrames = 0
        baseMs = 0
        old?.release()
        queue.getOrNull(target)?.let { source ->
            val local = if (source.remote) {
                AudioDecoder.cachePath(context, source)?.absolutePath
            } else {
                source.url
            }
            // Background only: measuring reads the file and must never delay
            // the audio that is about to start.
            DurationReader.ensure(source, local)
        }
        queue.getOrNull(target + 1)?.let { next ->
            // AIMP-style: preload the next NAS track once the current one is
            // safely playing, so transitions and seeks find it on disk.
            if (next.remote && AudioDecoder.cachePath(context, next) == null) {
                Thread {
                    sleepQuietly(15_000)
                    AudioDecoder.prefetch(context, next)
                }.start()
            } else {
                AudioDecoder.prefetch(context, next)
            }
        }
        if (playing) sink?.play()
    }

    override fun setQueue(items: List<AudioSource>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) {
            synchronized(lock) {
                queue = items
                a?.release()
                b?.release()
                a = null
                b = null
                index = 0
                displayIndex = 0
                fading = false
                pendingIndex = -1
                renderedFrames = 0
                baseMs = 0
            }
            return
        }
        val start = startIndex.coerceIn(0, items.lastIndex)
        // Format probe outside the lock: it reads only the header, no codec and
        // no end-of-file seek, so a NAS queue starts quickly.
        val probe = AudioDecoder.probe(context, items[start])
        val rate = probe?.getOrNull(0) ?: 44_100
        val channels = probe?.getOrNull(1) ?: 2
        synchronized(lock) {
            queue = items
            a?.release()
            b?.release()
            a = null
            b = null
            fading = false
            pendingIndex = -1
            mixerRate = rate
            EngineTrace.add("queue=${items.size} rate=$rate probe=${probe != null}")
            discardPreparedLocked(-1)
            sink?.release()
            sink = AudioSink(rate, if (channels == 1) 2 else channels)
            sink?.setVolume(nextSinkVolume)
            if (shuffle) rebuildShuffleOrder(start)
            switchTo(start)
            EngineTrace.add("index=${index} playing=$playing")
            if (startPositionMs > 0) seekTo(startPositionMs)
            if (playing) sink?.play()
        }
    }

    /** Last load/decoder failure, for the UI error line. */
    fun lastError(): String? = lastError

    /** Next track the engine would play, or null at the end of a non-repeating queue. */
    fun peekNext(): AudioSource? = synchronized(lock) {
        val next = nextIndexLocked()
        if (next < 0) null else queue.getOrNull(next)
    }

    /** Jumps to a queue index (car taps a song or presses Next/Prev). */
    fun jumpTo(target: Int, positionMs: Long = 0) {
        synchronized(lock) {
            if (target !in queue.indices) return
            // Next/Prev from the car must crossfade exactly like the phone.
            if (positionMs <= 0 && playing && crossfadeMs > 0 && !fading && target != index) {
                if (startFadeLocked(target)) {
                    EngineTrace.add("jumpTo fade -> $target")
                    return
                }
            }
            b?.release()
            b = null
            fading = false
            pendingIndex = -1
            if (target != index) {
                switchTo(target)
            }
            if (positionMs > 0) seekTo(positionMs)
            if (playing) sink?.play()
        }
    }

    /**
     * Car list change: fade the current track out, swap the queue and fade the
     * new track in, instead of the hard cut a plain [setQueue] would make.
     */
    fun switchQueueFading(
        items: List<AudioSource>,
        startIndex: Int,
        positionMs: Long,
        fadeMs: Int,
        play: Boolean,
    ) {
        Thread {
            val steps = 8
            val stepMs = (fadeMs / steps).coerceAtLeast(20)
            val old = synchronized(lock) { sink }
            for (i in steps downTo 0) {
                runCatching { old?.setVolume(i / steps.toFloat()) }
                sleepQuietly(stepMs.toLong())
            }
            synchronized(lock) {
                nextSinkVolume = 0f
                setQueue(items, startIndex, positionMs)
            }
            if (play) {
                play()
                val fresh = synchronized(lock) { sink }
                for (i in 0..steps) {
                    runCatching { fresh?.setVolume(i / steps.toFloat()) }
                    sleepQuietly(stepMs.toLong())
                }
                runCatching { fresh?.setVolume(1f) }
            } else {
                synchronized(lock) { sink?.setVolume(1f) }
            }
            nextSinkVolume = 1f
        }.start()
    }

    /** Fills auth headers that were missing when the queue was built (car/headset start). */
    fun updateRemoteHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) return
        synchronized(lock) {
            queue = queue.map { source ->
                if (source.remote && source.headers.isEmpty()) source.copy(headers = headers) else source
            }
        }
    }

    /** Appends one source without reloading the current one. */
    fun addSource(source: AudioSource) {
        synchronized(lock) {
            queue = queue + source
            if (shuffle) rebuildShuffleOrder(visibleIndexLocked())
        }
    }

    override fun play() {
        requestFocus()
        synchronized(lock) {
            playing = true
            sink?.play()
        }
    }

    override fun pause() {
        synchronized(lock) {
            playing = false
            sink?.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        synchronized(lock) {
            b?.release()
            b = null
            fading = false
            pendingIndex = -1
            a?.seekTo(positionMs)
            renderedFrames = 0
            baseMs = positionMs
            if (playing) sink?.play()
        }
    }

    override fun next() {
        Thread { synchronized(lock) { nextOnWorker() } }.start()
    }

    private fun nextOnWorker() {
        run {

            if (queue.isEmpty()) return
            val next = nextIndexLocked()
            EngineTrace.add("next: state=$index next=$next fading=$fading playing=$playing")
            if (next < 0) return
            if (crossfadeMs > 0 && !fading && playing && queue.size > 1) {
                if (startFadeLocked(next)) return
            }
            switchTo(next)
        }
    }

    override fun previous() {
        Thread { synchronized(lock) { previousOnWorker() } }.start()
    }

    private fun previousOnWorker() {
        run {
            if (queue.isEmpty()) return
            val target = previousIndexLocked()
            EngineTrace.add("previous: state=$index target=$target fading=$fading playing=$playing")
            if (target < 0) return
            if (crossfadeMs > 0 && !fading && playing && queue.size > 1 &&
                target != visibleIndexLocked()
            ) {
                if (startFadeLocked(target)) return
            }
            b?.release()
            b = null
            fading = false
            pendingIndex = -1
            switchTo(target)
        }
    }

    /** Index the UI should show as current (the incoming one during a fade). */
    private fun visibleIndexLocked(): Int = if (fading && b != null) displayIndex else index

    /**
     * Next track without side effects: the poller asks for it every few hundred
     * milliseconds, so it must not reshuffle anything. The order is fixed for a
     * whole cycle and simply wraps with repeat all, like AIMP's fixed order.
     */
    private fun nextIndexLocked(): Int {
        if (queue.isEmpty()) return -1
        val from = visibleIndexLocked()
        if (shuffle && queue.size > 1) {
            if (shuffleOrder.size != queue.size) rebuildShuffleOrder(from)
            val pos = shuffleOrder.indexOf(from)
            if (pos >= 0 && pos + 1 < shuffleOrder.size) return shuffleOrder[pos + 1]
            return if (repeatMode == 1) shuffleOrder.getOrElse(0) { from } else -1
        }
        if (from + 1 < queue.size) return from + 1
        return if (repeatMode == 1) 0 else -1
    }

    /** Same for going back; shuffle walks its fixed order backwards too. */
    private fun previousIndexLocked(): Int {
        if (queue.isEmpty()) return -1
        val from = visibleIndexLocked()
        if (shuffle && queue.size > 1) {
            if (shuffleOrder.size != queue.size) rebuildShuffleOrder(from)
            val pos = shuffleOrder.indexOf(from)
            if (pos > 0) return shuffleOrder[pos - 1]
            return if (repeatMode == 1) shuffleOrder.lastOrNull() ?: from else from
        }
        if (from > 0) return from - 1
        return if (repeatMode == 1) queue.lastIndex else 0
    }

    private fun rebuildShuffleOrder(start: Int) {
        shuffleOrder.clear()
        if (queue.isEmpty()) return
        val first = start.coerceIn(0, queue.lastIndex)
        shuffleOrder.add(first)
        val rest = (0 until queue.size).filter { it != first }.shuffled()
        shuffleOrder.addAll(rest)
    }

    private fun durationMsLocked(): Long {
        val source = queue.getOrNull(index)
        // A measured duration (frame count or the completed cache file) beats
        // the header estimate, which is often wrong for VBR MP3.
        // The decoder's own end-of-stream position is the most trustworthy:
        // it is what actually plays. Frame counting is the fallback for
        // streams that are still downloading.
        val fromDecoder = a?.measuredDurationMs ?: 0
        if (fromDecoder > 0) return fromDecoder
        val measured = source?.let { DurationReader.cached(it.url) } ?: 0
        if (measured > 0) return measured
        val fromStream = a?.durationMs ?: 0
        if (fromStream > 0) return fromStream
        return source?.durationMs ?: 0
    }

    override fun setCrossfadeMs(ms: Int) {
        crossfadeMs = ms.coerceIn(0, 15_000)
    }

    override fun setRepeatMode(mode: Int) {
        repeatMode = mode.coerceIn(0, 2)
    }

    override fun setShuffle(enabled: Boolean) {
        synchronized(lock) {
            shuffle = enabled
            shuffleOrder.clear()
            if (enabled) rebuildShuffleOrder(index)
        }
    }

    override fun snapshot(): EngineSnapshot {
        synchronized(lock) {
            // As soon as the next track starts fading in the screen follows it,
            // so the title matches what is starting to play. "Next" is computed
            // from the same visible index, so it points past it.
            val fadingNow = fading && b != null
            val visible = if (fadingNow) displayIndex else index
            val shown = queue.getOrNull(visible)
            return EngineSnapshot(
                source = shown,
                title = shown?.title.orEmpty(),
                artist = shown?.artist.orEmpty(),
                positionMs = if (fadingNow) {
                    fadePos * 1000L / mixerRate.coerceAtLeast(1)
                } else {
                    positionMsLocked()
                },
                durationMs = if (fadingNow) {
                    b?.durationMs ?: 0
                } else {
                    durationMsLocked()
                },
                playing = playing,
                index = visible,
                queueSize = queue.size,
                fading = fading,
            )
        }
    }

    private fun positionMsLocked(): Long {
        val rate = mixerRate.coerceAtLeast(1)
        return baseMs + renderedFrames * 1000L / rate
    }

    override fun release() {
        discardPreparedLocked(-1)
        running = false
        abandonFocus()
        runCatching { context.unregisterReceiver(noisy) }
        synchronized(lock) {
            a?.release()
            b?.release()
            a = null
            b = null
            sink?.release()
            sink = null
            playing = false
        }
        runCatching { worker.join(500) }
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }
}
