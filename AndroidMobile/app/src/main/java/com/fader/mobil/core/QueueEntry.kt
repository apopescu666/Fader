package com.fader.mobil.core

/** One row of the playback queue, as the engine actually holds it. */
data class QueueEntry(
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val remote: Boolean,
)
