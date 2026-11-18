package com.fader.mobil.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.fader.mobil.core.LocalLibrary
import java.nio.ByteBuffer

/**
 * Decodes one [AudioSource] into 16-bit PCM. Local files and HTTP(S) URLs are
 * both supported (NAS URLs go through the headers passed with the source).
 */
class AudioDecoder(private val context: Context, private val source: AudioSource) {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var trackIndex = -1
    private var pending: ByteArray = ByteArray(0)
    private var pendingOffset = 0
    private var inputDone = false
    private var outputDone = false
    private var descriptor: android.os.ParcelFileDescriptor? = null
    private var streamSource: MediaDataSource? = null

    var sampleRate: Int = 44_100
        private set
    var channels: Int = 2
        private set

    private var realDurationMs = 0L

    /** Duration measured from the last decoded sample; 0 for a remote stream. */
    val measuredDurationMs: Long get() = realDurationMs

    val durationMs: Long
        get() {
            if (realDurationMs > 0) return realDurationMs
            return runCatching {
                val format = extractor?.getTrackFormat(trackIndex)
                val us = format?.getLong(MediaFormat.KEY_DURATION) ?: 0L
                if (us > 0) us / 1000 else source.durationMs
            }.getOrDefault(source.durationMs)
        }

    fun open(): Boolean {
        val ex = MediaExtractor()
        val opened = runCatching {
            val attached = attachSource(context, ex, source)
            descriptor = attached.descriptor
            streamSource = attached.stream
        }
            .onFailure {
                EngineTrace.add("decoder: ${it.javaClass.simpleName} ${it.message}".take(70))
            }
            .isSuccess
        if (!opened) {
            ex.release()
            return false
        }
        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val candidate = ex.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                audioTrack = i
                format = candidate
                break
            }
        }
        if (format == null || audioTrack < 0) {
            ex.release()
            return false
        }
        sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
            .getOrDefault(44_100)
        channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
            .getOrDefault(2)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        val decoder = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull() ?: run {
            ex.release()
            return false
        }
        val configured = runCatching {
            decoder.configure(format, null, null, 0)
            decoder.start()
        }.isSuccess
        if (!configured) {
            EngineTrace.add("codec configure failed: ${format.getString(MediaFormat.KEY_MIME)}")
            runCatching { decoder.release() }
            ex.release()
            return false
        }
        ex.selectTrack(audioTrack)

        // Real length from the last sample, but only when the bytes are local
        // (file or cache). On a NAS stream this seek would restart the network
        // read-ahead window just to look at the end of the file.
        val localBytes = !source.remote || cachePath(context, source) != null
        if (localBytes) {
            realDurationMs = runCatching {
                ex.seekTo(Long.MAX_VALUE, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val lastUs = ex.sampleTime
                ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (lastUs > 0) lastUs / 1000 else 0L
            }.getOrDefault(0L)
        }

        extractor = ex
        codec = decoder
        trackIndex = audioTrack
        inputDone = false
        outputDone = false
        pending = ByteArray(0)
        pendingOffset = 0
        return true
    }

    companion object {
        /**
         * Rate and channels only: no codec, no end-of-file seek. Used to pick
         * the mixer rate at queue start, which keeps NAS startup quick.
         */
        fun probe(context: Context, source: AudioSource): IntArray? {
            val ex = MediaExtractor()
            var attached: Attached? = null
            var handedOver = false
            return try {
                attached = attachSource(context, ex, source)
                for (i in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                        val rate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                            .getOrDefault(44_100)
                        val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(2)
                        (attached.stream as? RemoteCacheSource)?.let {
                            RemoteCacheSource.stash(it)
                            handedOver = true
                        }
                        return intArrayOf(rate, channels)
                    }
                }
                null
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { attached?.descriptor?.close() }
                if (!handedOver) runCatching { attached?.stream?.close() }
                ex.release()
            }
        }

        private class Attached(
            val descriptor: android.os.ParcelFileDescriptor?,
            val stream: MediaDataSource?,
        )

        /** Shared data-source setup for [open] and [probe]. */
        private fun attachSource(
            context: Context,
            ex: MediaExtractor,
            source: AudioSource,
        ): Attached {
            if (source.remote) {
                // Prefetched tracks play from disk; otherwise stream with a
                // read-ahead buffer, and fall back to a cached download.
                val cached = cachePath(context, source)
                if (cached != null) {
                    ex.setDataSource(cached.absolutePath)
                    return Attached(null, null)
                }
                // Play through a cache file that grows while the download runs,
                // like AIMP: the audio thread only touches the disk.
                val stream = RemoteCacheSource.create(context, source)
                val opened = runCatching { ex.setDataSource(stream as MediaDataSource) }.isSuccess
                if (!opened) {
                    EngineTrace.add("nas cache open failed")
                    runCatching { stream.close() }
                    ex.setDataSource(cacheFile(context, source).absolutePath)
                    return Attached(null, null)
                }
                return Attached(null, stream)
            }
            if (source.url.startsWith("content://")) {
                ex.setDataSource(context, Uri.parse(source.url), source.headers)
                return Attached(null, null)
            }
            val raw = source.url.removePrefix("file://")
            val path = if (raw.contains("%")) {
                runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            } else {
                raw
            }
            return try {
                ex.setDataSource(path)
                Attached(null, null)
            } catch (first: Throwable) {
                EngineTrace.add("path: ${path.take(90)}")
                // Scoped storage: retry through the MediaStore content URI.
                val art = LocalLibrary.art(source.url)
                if (art != null) {
                    val pfd = context.contentResolver.openFileDescriptor(Uri.parse(art.uri), "r")
                    if (pfd != null) {
                        ex.setDataSource(pfd.fileDescriptor)
                        Attached(pfd, null)
                    } else {
                        throw first
                    }
                } else {
                    throw first
                }
            }
        }

        /** Downloads a NAS track into the cache without blocking the caller. */
        fun prefetch(context: Context, source: AudioSource) {
            if (!source.remote) return
            Thread {
                runCatching { cacheFile(context, source) }
            }.start()
        }

        /** True when a remote track can start without waiting for the network. */
        fun hasCachedFile(context: Context, source: AudioSource): Boolean {
            if (!source.remote) return false
            val dir = java.io.File(context.cacheDir, "engine-cache")
            val hash = java.security.MessageDigest.getInstance("SHA-1")
                .digest(source.url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val target = java.io.File(dir, hash)
            if (target.isFile && target.length() > 0) return true
            val part = java.io.File(dir, "$hash.part")
            return part.isFile && part.length() > 512 * 1024
        }

        /** Existing cached file for a remote track, without downloading. */
        fun cachePath(context: Context, source: AudioSource): java.io.File? {
            if (!source.remote) return null
            val dir = java.io.File(context.cacheDir, "engine-cache")
            val hash = java.security.MessageDigest.getInstance("SHA-1")
                .digest(source.url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val file = java.io.File(dir, hash)
            return if (file.isFile && file.length() > 0) file else null
        }

        /** Removes a possibly truncated cached download. */
        fun clearCache(context: Context, source: AudioSource) {
            if (!source.remote) return
            runCatching {
                val dir = java.io.File(context.cacheDir, "engine-cache")
                val hash = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(source.url.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                java.io.File(dir, hash).delete()
            }
        }

        /** Downloads a NAS track once; safe on a worker thread only. */
        fun cacheFile(context: Context, source: AudioSource): java.io.File {
        val dir = java.io.File(context.cacheDir, "engine-cache").apply { mkdirs() }
        val hash = java.security.MessageDigest.getInstance("SHA-1")
            .digest(source.url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = java.io.File(dir, hash)
        if (target.isFile && target.length() > 0) return target
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url(source.url)
            .apply { source.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} for ${source.url}")
            }
            val body = response.body ?: throw java.io.IOException("empty body")
            val declared = body.contentLength()
            val temp = java.io.File(dir, "$hash.dl")
            body.byteStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0) throw java.io.IOException("empty download")
            if (declared > 0 && temp.length() != declared) {
                temp.delete()
                throw java.io.IOException("truncated download ${temp.length()}/$declared")
            }
            temp.renameTo(target)
        }
        return target
    }
    }

    fun seekTo(positionMs: Long) {
        val ex = extractor ?: return
        ex.seekTo(positionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        runCatching { codec?.flush() }
        pending = ByteArray(0)
        pendingOffset = 0
        inputDone = false
        outputDone = false
    }

    /** Fills [out] with PCM bytes; returns the count or -1 when the track ended. */
    fun readPcm(out: ByteArray): Int {
        if (outputDone) return -1
        if (pendingOffset < pending.size) {
            return copyPending(out)
        }
        val ex = extractor ?: return -1
        val decoder = codec ?: return -1
        val info = MediaCodec.BufferInfo()

        while (true) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer: ByteBuffer = decoder.getInputBuffer(inIndex) ?: continue
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                        .getOrDefault(sampleRate)
                    channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                        .getOrDefault(channels)
                }
                outIndex >= 0 -> {
                    val buffer = decoder.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buffer.get(data)
                        decoder.releaseOutputBuffer(outIndex, false)
                        pending = data
                        pendingOffset = 0
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        return copyPending(out)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                        return -1
                    }
                }
                else -> {
                    if (inputDone && outputDone) return -1
                    if (inputDone && outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && pending.isEmpty()) {
                        // keep waiting for the decoder to flush its last frames
                    }
                }
            }
        }
    }

    private fun copyPending(out: ByteArray): Int {
        val available = pending.size - pendingOffset
        if (available <= 0) return -1
        val count = minOf(available, out.size)
        System.arraycopy(pending, pendingOffset, out, 0, count)
        pendingOffset += count
        if (pendingOffset >= pending.size) {
            pending = ByteArray(0)
            pendingOffset = 0
        }
        return count
    }

    fun release() {
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { streamSource?.close() }
        streamSource = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
    }
}
