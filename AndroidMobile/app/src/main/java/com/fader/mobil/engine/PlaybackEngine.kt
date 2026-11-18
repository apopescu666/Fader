package com.fader.mobil.engine

/** One playable input: a local file or a remote URL with auth headers. */
data class AudioSource(
    val url: String,
    val remote: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    /** File size known from the folder listing; 0 when unknown. */
    val sizeBytes: Long = 0,
)

/** What the UI and the media session need to know. */
data class EngineSnapshot(
    val source: AudioSource? = null,
    val title: String = "",
    val artist: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val index: Int = 0,
    val queueSize: Int = 0,
    val fading: Boolean = false,
)

/**
 * Own playback engine: decodes two sources, mixes them in one AudioTrack and
 * applies sample-accurate volume envelopes (gapless + crossfade).
 */
interface PlaybackEngine {

    fun setQueue(items: List<AudioSource>, startIndex: Int, startPositionMs: Long = 0)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun next()

    fun previous()

    fun setCrossfadeMs(ms: Int)

    fun setRepeatMode(mode: Int)

    fun setShuffle(enabled: Boolean)

    fun snapshot(): EngineSnapshot

    fun release()
}
