package com.fader.mobil.engine

import android.content.Context
import kotlin.math.floor

/**
 * Decodes one source and resamples it to the mixer format (48 kHz stereo by
 * default) with linear interpolation, so two songs with different rates can be
 * mixed without pitch changes. Mono is expanded to stereo.
 */
class PcmStream(
    context: Context,
    val source: AudioSource,
    private val mixerRate: Int,
    private val mixerChannels: Int,
) {

    private val decoder = AudioDecoder(context, source)
    private var src = ShortArray(0)          // stereo, at the source rate
    private var srcFrames = 0
    private var srcPos = 0.0
    private var eos = false
    private val bytes = ByteArray(16 * 1024)
    private val temp = ShortArray(8192)

    var sampleRate: Int = 0
        private set
    var channels: Int = 0
        private set
    var compatible: Boolean = false
        private set

    val durationMs: Long get() = decoder.durationMs
    val measuredDurationMs: Long get() = decoder.measuredDurationMs

    fun open(): Boolean {
        if (!decoder.open()) return false
        sampleRate = decoder.sampleRate
        channels = decoder.channels
        compatible = channels in 1..2 && mixerChannels == 2 && sampleRate > 0
        src = ShortArray(0)
        srcFrames = 0
        srcPos = 0.0
        eos = false
        return true
    }

    /** Fills [out] with [frames] stereo frames; returns frames or -1 at the end. */
    fun readBlock(out: ShortArray, frames: Int): Int {
        if (!compatible) return -1
        var produced = 0
        val step = sampleRate.toDouble() / mixerRate
        while (produced < frames) {
            val index = floor(srcPos).toInt()
            if (index + 1 >= srcFrames) {
                if (!fill()) {
                    EngineTrace.add("stream end")
                    break
                }
                continue
            }
            val frac = (srcPos - index).toFloat()
            val base = index * 2
            val next = base + 2
            for (ch in 0 until 2) {
                val a = src[base + ch].toFloat()
                val b = src[next + ch].toFloat()
                out[produced * 2 + ch] = (a + (b - a) * frac).toInt().toShort()
            }
            produced++
            srcPos += step
        }
        if (produced == 0 && eos) return -1
        compact()
        return produced
    }

    /** Decodes more PCM, converting it to stereo at the source rate. */
    private fun fill(): Boolean {
        if (eos) return false
        while (true) {
            val count = decoder.readPcm(bytes)
            if (count < 0) {
                eos = true
                return false
            }
            if (count == 0) return true
            val samples = count / 2
            if (samples <= 0) continue
            val mono = channels == 1
            val addFrames = if (mono) samples else samples / 2
            val needed = (srcFrames + addFrames) * 2
            if (needed > src.size) {
                src = src.copyOf(maxOf(needed, src.size * 2))
            }
            var srcIndex = srcFrames * 2
            if (mono) {
                for (i in 0 until samples) {
                    val v = readShort(bytes, i * 2)
                    src[srcIndex++] = v
                    src[srcIndex++] = v
                }
            } else {
                for (i in 0 until addFrames * 2) {
                    src[srcIndex++] = readShort(bytes, i * 2)
                }
            }
            srcFrames += addFrames
            return true
        }
    }

    private fun readShort(data: ByteArray, offset: Int): Short {
        val lo = data[offset].toInt() and 0xFF
        val hi = data[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }

    /** Drops already-consumed frames so the buffer does not grow forever. */
    private fun compact() {
        val drop = floor(srcPos).toInt()
        if (drop < 4096) return
        val remaining = srcFrames - drop
        if (remaining > 0) {
            System.arraycopy(src, drop * 2, src, 0, remaining * 2)
        }
        srcFrames = maxOf(0, remaining)
        srcPos -= drop
    }

    fun seekTo(ms: Long) {
        decoder.seekTo(ms)
        src = ShortArray(0)
        srcFrames = 0
        srcPos = 0.0
        eos = false
    }

    fun release() {
        decoder.release()
    }
}
