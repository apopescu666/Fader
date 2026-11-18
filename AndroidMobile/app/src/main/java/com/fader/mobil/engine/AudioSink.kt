package com.fader.mobil.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** One AudioTrack that the mixer writes PCM into. */
class AudioSink(sampleRate: Int, channels: Int) {

    private var track: AudioTrack = build(sampleRate, channels)
    private var writtenFrames = 0L
    private var baseMs = 0L

    private fun build(rate: Int, channels: Int): AudioTrack {
        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(
            rate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 3, 48 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun play() {
        runCatching { track.play() }
    }

    fun pause() {
        runCatching { track.pause() }
    }

    fun flush() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        writtenFrames = 0
    }

    fun write(bytes: ByteArray, count: Int): Int = runCatching {
        val n = track.write(bytes, 0, count)
        if (n < 0) EngineTrace.add("audio write error $n")
        if (n > 0) writtenFrames += n / (PcmMixer.BYTES_PER_SAMPLE * PcmMixer.CHANNELS)
        n
    }.getOrDefault(0)

    fun isPlaying(): Boolean =
        runCatching { track.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false)

    fun positionMs(rate: Int): Long {
        val head = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(0L)
        val frames = (writtenFrames - head).coerceAtLeast(0L)
        return baseMs + frames * 1000L / rate.coerceAtLeast(1)
    }

    fun setBase(ms: Long) {
        baseMs = ms
        writtenFrames = 0
    }

    fun setVolume(value: Float) {
        runCatching { track.setVolume(value.coerceIn(0f, 1f)) }
    }

    fun release() {
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
