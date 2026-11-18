package com.fader.mobil.engine

import android.media.MediaDataSource
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Streams a NAS track through a local cache file that grows while it plays.
 *
 * The download runs on its own thread; MediaExtractor reads from disk, so the
 * audio thread never waits on the network and seeking inside the downloaded
 * part is instant. When the download finishes the file becomes the normal
 * cache, so the next play (and the exact duration for the crossfade) is local.
 */
class RemoteCacheSource(
    private val url: String,
    private val headers: Map<String, String>,
    private val knownSize: Long,
    private val target: File,
    private val part: File,
) : MediaDataSource() {

    private val monitor = Object()
    private val readLock = Any()

    private var raf: RandomAccessFile? = null
    private var total = -1L
    private var downloaded = 0L
    private var done = false
    private var failed = false
    private var closed = false
    private var lastProgress = System.currentTimeMillis()
    private var bodyStream: InputStream? = null
    private var downloader: Thread? = null

    init {
        if (target.isFile && target.length() > 0) {
            done = true
            total = target.length()
            downloaded = total
            raf = runCatching { RandomAccessFile(target, "r") }.getOrNull()
        } else {
            runCatching {
                part.parentFile?.mkdirs()
                if (!part.exists()) part.createNewFile()
            }
            // Resume an interrupted download instead of starting from zero.
            val existing = runCatching { part.length() }.getOrDefault(0L)
            val start = if (existing > 0 && (knownSize <= 0 || existing < knownSize)) {
                existing
            } else {
                0L
            }
            if (start == 0L && existing > 0) {
                runCatching { RandomAccessFile(part, "rw").use { it.setLength(0) } }
            }
            synchronized(monitor) { downloaded = start }
            raf = runCatching { RandomAccessFile(part, "r") }.getOrNull()
            downloader = Thread({ download(start) }, "fader-nas-cache").apply {
                isDaemon = true
                start()
            }
        }
    }

    override fun getSize(): Long {
        var waited = 0
        synchronized(monitor) {
            while (total <= 0 && !done && !failed && !closed && waited < 3_000) {
                monitor.wait(100)
                waited += 100
            }
            if (total > 0) return total
            if (done) {
                total = downloaded
                return total
            }
        }
        // Last resort: some servers only expose the length to HEAD.
        val fromHead = resolveSize()
        synchronized(monitor) {
            if (total <= 0 && fromHead > 0) total = fromHead
            return total
        }
    }

    private fun resolveSize(): Long = runCatching {
        val request = Request.Builder().url(url).head()
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.contentLength() ?: -1L else -1L
        }
    }.getOrDefault(-1L)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (position < 0) return -1
        if (total > 0 && position >= total) return -1
        var count = 0
        synchronized(monitor) {
            while (true) {
                if (closed) return -1
                if (position < downloaded) {
                    count = minOf(length.toLong(), downloaded - position).toInt()
                    break
                }
                if (done || failed) return -1
                if (System.currentTimeMillis() - lastProgress > 30_000) {
                    failed = true
                    return -1
                }
                monitor.wait(200)
            }
        }
        if (count <= 0) return -1
        synchronized(readLock) {
            val file = raf ?: return -1
            return runCatching {
                file.seek(position)
                file.readFully(buffer, offset, count)
                count
            }.getOrDefault(-1)
        }
    }

    private fun download(start: Long) {
        var response: Response? = null
        try {
            val request = Request.Builder().url(url)
                .apply {
                    if (start > 0) header("Range", "bytes=$start-")
                    headers.forEach { (key, value) -> header(key, value) }
                }
                .build()
            response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                markFailed()
                return
            }
            val body = response.body
            if (body == null) {
                markFailed()
                return
            }
            // A server that ignores Range sends the whole file from byte 0.
            var begin = start
            if (start > 0 && response.code == 200) {
                begin = 0
                synchronized(monitor) {
                    downloaded = 0
                    runCatching { RandomAccessFile(part, "rw").use { it.setLength(0) } }
                }
            }
            val rangeTotal = response.header("Content-Range")
                ?.substringAfter('/', "")
                ?.toLongOrNull() ?: -1L
            synchronized(monitor) {
                if (total <= 0) {
                    total = rangeTotal.takeIf { it > 0 }
                        ?: body.contentLength().takeIf { it > 0 }?.let { begin + it }
                        ?: knownSize.takeIf { it > 0 }
                        ?: -1
                }
                monitor.notifyAll()
            }
            val input = body.byteStream()
            bodyStream = input
            FileOutputStream(part, begin > 0).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    if (closed) return
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                    synchronized(monitor) {
                        downloaded += read
                        lastProgress = System.currentTimeMillis()
                        monitor.notifyAll()
                    }
                }
            }
            val finished = synchronized(monitor) {
                if (closed) return
                if (downloaded <= 0) {
                    false
                } else {
                    if (target.exists()) target.delete()
                    part.renameTo(target)
                    done = true
                    // Trust the bytes we have: a wrong Content-Length must not
                    // throw away a file that plays fine.
                    if (total <= 0 || downloaded < total) total = downloaded
                    monitor.notifyAll()
                    true
                }
            }
            if (!finished) {
                markFailed()
                return
            }
            EngineTrace.add("nas cached: ${target.length() / 1024} KB")
            // Exact duration from the local file: this is what makes the
            // automatic crossfade start at the right moment on NAS too.
            runCatching {
                DurationReader.ensureBlocking(
                    AudioSource(url, true, headers),
                    target.absolutePath,
                )
            }
        } catch (_: Throwable) {
            markFailed()
        } finally {
            runCatching { response?.close() }
        }
    }

    private fun markFailed() {
        synchronized(monitor) {
            if (!closed) failed = true
            monitor.notifyAll()
        }
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            runCatching { bodyStream?.close() }
            monitor.notifyAll()
        }
        runCatching { raf?.close() }
        raf = null
        // The partial file is kept on purpose: the next attempt resumes the
        // download with a Range request instead of starting from zero.
    }

    companion object {
        /** Probe hands the already-downloading file to playback, so a NAS
         *  track is opened once instead of twice. */
        private val warm = java.util.concurrent.ConcurrentHashMap<String, RemoteCacheSource>()

        fun stash(source: RemoteCacheSource) {
            if (warm.size >= 3) return
            warm[source.url] = source
        }

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun create(context: android.content.Context, source: AudioSource): RemoteCacheSource {
            warm.remove(source.url)?.let { return it }
            val dir = File(context.cacheDir, "engine-cache").apply { mkdirs() }
            val hash = java.security.MessageDigest.getInstance("SHA-1")
                .digest(source.url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return RemoteCacheSource(
                url = source.url,
                headers = source.headers,
                knownSize = source.sizeBytes,
                target = File(dir, hash),
                part = File(dir, "$hash.part"),
            )
        }
    }
}
