package com.fader.mobil.core

import android.content.Context
import kotlinx.coroutines.runBlocking

/**
 * Low-priority background reader for NAS folders (Kodi/Navidrome style).
 *
 * When a folder is opened its subfolders are queued here, so clicking into them
 * later is instant. The worker steps aside while the user is opening a folder:
 * navigation always wins over background indexing.
 */
object NasScanner {

    private const val MAX_QUEUE = 200
    private const val FRESH_MS = 24 * 60 * 60 * 1000L

    private val monitor = Object()
    private val queue = ArrayDeque<Pair<String, String>>()
    private val queued = HashSet<String>()

    @Volatile
    private var userBusy = false

    @Volatile
    private var appContext: Context? = null

    private var worker: Thread? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        startWorker()
    }

    /** Called around a user-visible folder load, so background work steps aside. */
    fun setUserBusy(busy: Boolean) {
        userBusy = busy
        if (!busy) synchronized(monitor) { monitor.notifyAll() }
    }

    /** Queue the subfolders of a freshly read folder for a low-priority scan. */
    fun prescan(context: Context, listing: Listing) {
        if (!listing.remote || listing.folders.isEmpty()) return
        init(context)
        synchronized(monitor) {
            listing.folders.take(MAX_QUEUE).forEach { folder ->
                if (folder.url.isBlank()) return@forEach
                if (queued.contains(folder.url)) return@forEach
                if (queue.size >= MAX_QUEUE) return@forEach
                queued.add(folder.url)
                queue.addLast(folder.url to folder.name)
            }
            monitor.notifyAll()
        }
    }

    private fun takeNext(): Pair<String, String> = synchronized(monitor) {
        while (true) {
            if (userBusy) {
                monitor.wait(300)
                continue
            }
            val next = queue.removeFirstOrNull()
            if (next != null) return next
            monitor.wait(1500)
        }
        @Suppress("UNREACHABLE_CODE")
        return "" to ""
    }

    private fun startWorker() {
        if (worker != null) return
        worker = Thread({
            while (true) {
                val next = takeNext()
                val context = appContext ?: continue
                runCatching {
                    // A recently scanned folder is skipped: the database keeps
                    // its contents across restarts.
                    val scanned = NasStore.scannedAt(context, next.first)
                    val fresh = scanned != null &&
                        System.currentTimeMillis() - scanned < FRESH_MS
                    if (!fresh) {
                        val listing = runBlocking { RemoteLibrary.listing(next.first, next.second) }
                        NasStore.saveListing(context, listing)
                    }
                }
                synchronized(monitor) { queued.remove(next.first) }
            }
        }, "fader-nas-scan").apply {
            isDaemon = true
            start()
        }
    }
}
