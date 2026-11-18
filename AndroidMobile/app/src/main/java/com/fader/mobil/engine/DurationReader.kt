package com.fader.mobil.engine

import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Exact MP3 duration by counting frames (same idea as ffprobe). Needed because
 * files without a Xing/Info header report a wrong duration to the platform.
 */
object DurationReader {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Long>()
    private val tried = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cached(url: String): Long = cache[url] ?: 0

    /** Measures now (cheap for a cached file) so the fade timing is exact. */
    fun ensureBlocking(source: AudioSource, localPath: String?) {
        if (cache.containsKey(source.url)) return
        val ms = runCatching {
            if (localPath != null) measureFile(localPath) else measure(source)
        }.getOrDefault(0)
        if (ms > 0) cache[source.url] = ms
    }

    /**
     * Measures on a worker thread. Never call this from the audio thread: it
     * reads the whole file (or the MP3 frame table) and would cut the sound
     * exactly when the crossfade should start.
     */
    fun ensure(source: AudioSource, localPath: String? = null) {
        val url = source.url
        if (cache.containsKey(url)) return
        // Never download a NAS file just to measure it; the cache completes
        // first and then calls [ensureBlocking] on its own thread.
        if (localPath == null && source.remote) return
        if (!tried.add(url)) return
        scope.launch {
            val ms = runCatching {
                if (localPath != null) measureFile(localPath) else measure(source)
            }.getOrDefault(0)
            if (ms > 0) cache[url] = ms
        }
    }

    /** Exact duration of a local file: MP3 by frame count, anything else by extractor. */
    private fun measureFile(path: String): Long {
        val mp3 = runCatching { File(path).inputStream().use { scanMp3(it) } }.getOrDefault(0)
        if (mp3 > 0) return mp3
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(path)
            var result = 0L
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                if (format.getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    ex.selectTrack(i)
                    ex.seekTo(Long.MAX_VALUE, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val us = ex.sampleTime
                    if (us > 0) result = us / 1000
                    break
                }
            }
            result
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun measure(source: AudioSource): Long {
        val stream: InputStream = if (source.remote) {
            val request = Request.Builder()
                .url(source.url)
                .apply { source.headers.forEach { (k, v) -> header(k, v) } }
                .build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return 0
            }
            response.body?.byteStream() ?: return 0
        } else {
            File(source.url).inputStream()
        }
        stream.use { return scanMp3(it) }
    }

    private fun scanMp3(raw: InputStream): Long {
        java.io.BufferedInputStream(raw, 64 * 1024).use { input ->
            val stream = java.io.PushbackInputStream(input, 10)
            skipId3(stream)
            var frames = 0L
            var sampleRate = 44_100
            var samples = 1152
            var first = true
            val header = ByteArray(4)
            val bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
            while (true) {
                var read = 0
                while (read < 4) {
                    val n = stream.read(header, read, 4 - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 4) break
                val b0 = header[0].toInt() and 0xFF
                val b1 = header[1].toInt() and 0xFF
                val b2 = header[2].toInt() and 0xFF
                if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) {
                    stream.unread(header, 1, 3)
                    if (stream.read() < 0) break
                    continue
                }
                val version = (b1 shr 3) and 0x03
                val layer = (b1 shr 1) and 0x03
                val brIndex = (b2 shr 4) and 0x0F
                val srIndex = (b2 shr 2) and 0x03
                val padding = (b2 shr 1) and 0x01
                if (layer != 1 || brIndex == 0 || brIndex == 15 || srIndex == 3) {
                    stream.unread(header, 1, 3)
                    if (stream.read() < 0) break
                    continue
                }
                val rates = when (version) {
                    3 -> intArrayOf(44100, 48000, 32000)
                    2 -> intArrayOf(22050, 24000, 16000)
                    else -> intArrayOf(11025, 12000, 8000)
                }
                val bitrate = bitrates[brIndex]
                val rate = rates[srIndex]
                if (first) {
                    sampleRate = rate
                    samples = if (version == 3) 1152 else 576
                    first = false
                }
                val length = if (version == 3) {
                    (144000 * bitrate / rate) + padding
                } else {
                    (72000 * bitrate / rate) + padding
                }
                var skip = length - 4
                while (skip > 0) {
                    val n = stream.skip(skip.toLong())
                    if (n > 0) skip -= n.toInt()
                    else if (stream.read() < 0) break
                    else skip--
                }
                frames++
            }
            if (frames <= 0) return 0
            return frames * samples * 1000L / sampleRate
        }
    }

    private fun skipId3(stream: java.io.PushbackInputStream) {
        val head = ByteArray(10)
        var read = 0
        while (read < 10) {
            val n = stream.read(head, read, 10 - read)
            if (n <= 0) break
            read += n
        }
        if (read == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val size = ((head[6].toInt() and 0x7F) shl 21) or
                ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or
                (head[9].toInt() and 0x7F)
            val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
            var skip = (size + footer).toLong()
            while (skip > 0) {
                val n = stream.skip(skip)
                if (n > 0) skip -= n else if (stream.read() < 0) return else skip--
            }
        } else if (read > 0) {
            stream.unread(head, 0, read)
        }
    }
}
