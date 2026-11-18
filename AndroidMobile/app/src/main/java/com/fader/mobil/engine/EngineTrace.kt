package com.fader.mobil.engine

/** Tiny on-screen journal so playback decisions can be checked on a device. */
object EngineTrace {

    private val lines = ArrayDeque<String>()

    fun add(message: String) {
        synchronized(lines) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            lines.addLast("$time  $message")
            while (lines.size > 10) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}
