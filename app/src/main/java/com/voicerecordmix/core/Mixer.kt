package com.voicerecordmix.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/** Gentle limiter: linear below 0.8, smoothly approaches 1.0 above. */
fun softClip(x: Float): Float {
    val a = abs(x)
    if (a <= 0.8f) return x
    val y = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
    return if (x < 0) -y else y
}

fun toPcm16(x: Float): Short = (softClip(x) * 32767f).roundToInt().toShort()

/** Classic Freeverb (Jezar), mono in, stereo out. */
class Freeverb {
    private val scale = SAMPLE_RATE / 44100.0
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTunings = intArrayOf(556, 441, 341, 225)
    private val spread = 23

    private class Comb(size: Int) {
        val buf = FloatArray(size)
        var idx = 0
        var store = 0f
        fun process(x: Float, feedback: Float, damp: Float): Float {
            val out = buf[idx]
            store = out * (1 - damp) + store * damp
            buf[idx] = x + store * feedback
            if (++idx == buf.size) idx = 0
            return out
        }
    }

    private class Allpass(size: Int) {
        val buf = FloatArray(size)
        var idx = 0
        fun process(x: Float): Float {
            val b = buf[idx]
            buf[idx] = x + b * 0.5f
            if (++idx == buf.size) idx = 0
            return b - x
        }
    }

    private val combL = combTunings.map { Comb((it * scale).toInt()) }
    private val combR = combTunings.map { Comb(((it + spread) * scale).toInt()) }
    private val apL = allpassTunings.map { Allpass((it * scale).toInt()) }
    private val apR = allpassTunings.map { Allpass(((it + spread) * scale).toInt()) }

    var feedback = 0.86f
    var damp = 0.3f

    fun clear() {
        for (c in combL + combR) { c.buf.fill(0f); c.store = 0f }
        for (a in apL + apR) a.buf.fill(0f)
    }

    /** Returns the wet signal; left in [outLR][0], right in [outLR][1]. */
    fun process(x: Float, outLR: FloatArray) {
        val input = x * 0.015f
        var l = 0f
        var r = 0f
        for (c in combL) l += c.process(input, feedback, damp)
        for (c in combR) r += c.process(input, feedback, damp)
        for (a in apL) l = a.process(l)
        for (a in apR) r = a.process(r)
        outLR[0] = l
        outLR[1] = r
    }
}

/**
 * Mixes a take's music stem (stereo) with its voice stem (mono).
 * Output frame m uses music[m] and voice[m + lead], where lead = auto sync + manual offset.
 */
class Mixer(private val music: PcmReader, private val voice: PcmReader, private val autoLead: Int) {
    @Volatile
    var settings = MixSettings()

    val totalFrames: Long get() = music.frames

    private val reverb = Freeverb()
    private val wet = FloatArray(2)
    private var mbuf = ShortArray(0)
    private var vbuf = ShortArray(0)
    private var hpPrevX = 0f
    private var hpY = 0f
    // One-pole high-pass around 90 Hz to remove handling rumble and plosive thumps.
    private val hpA = 1f / (1f + 2f * Math.PI.toFloat() * 90f / SAMPLE_RATE)

    fun leadFrames(s: MixSettings = settings) = autoLead + (s.syncMs * SAMPLE_RATE / 1000f).roundToInt()

    /** Call before rendering from a new position. */
    fun reset() {
        reverb.clear()
        hpPrevX = 0f
        hpY = 0f
    }

    /** Renders [n] interleaved stereo frames starting at output frame [start] into [out]. */
    fun render(start: Long, n: Int, out: FloatArray) {
        val s = settings
        if (mbuf.size < n * 2) mbuf = ShortArray(n * 2)
        if (vbuf.size < n) vbuf = ShortArray(n)
        music.read(start, mbuf, n)
        voice.read(start + leadFrames(s), vbuf, n)
        val k32 = 1f / 32768f
        val wetGain = s.reverb * 2.5f
        for (k in 0 until n) {
            val x = vbuf[k] * k32
            hpY = hpA * (hpY + x - hpPrevX)
            hpPrevX = x
            val v = hpY * s.voiceVol
            var l = v
            var r = v
            if (wetGain > 0f) {
                reverb.process(v, wet)
                l += wet[0] * wetGain
                r += wet[1] * wetGain
            }
            out[k * 2] = softClip(mbuf[k * 2] * k32 * s.musicVol + l)
            out[k * 2 + 1] = softClip(mbuf[k * 2 + 1] * k32 * s.musicVol + r)
        }
    }
}

object Wav {
    /** 44-byte header for 16-bit PCM. */
    fun header(frames: Long, channels: Int, rate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = frames * channels * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(rate); putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
    }
}
