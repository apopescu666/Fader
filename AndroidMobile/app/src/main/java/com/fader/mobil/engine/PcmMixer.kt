package com.fader.mobil.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-power crossfade gains, computed from the rendered frame position so the
 * envelope is sample-accurate instead of timer-based.
 */
object PcmMixer {

    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2

    fun fadeGainIn(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    fun fadeGainOut(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    /**
     * Mixes 16-bit stereo PCM from two sources into [out].
     * Gains are applied per frame; both buffers must have the same length.
     */
    fun mix(
        a: ShortArray,
        b: ShortArray,
        out: ShortArray,
        gainA: Float,
        gainB: Float,
        frames: Int,
    ) {
        val samples = frames * CHANNELS
        for (i in 0 until samples) {
            val mixed = a[i] * gainA + b[i] * gainB
            out[i] = mixed.toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
