package com.voicerecordmix.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Random access to 16-bit interleaved PCM. Reads outside the data return zeros. */
interface PcmReader {
    val channels: Int
    val frames: Long
    fun read(frame: Long, dst: ShortArray, n: Int)
}

/** In-memory [PcmReader], used by tests and small buffers. */
class ArrayPcm(private val data: ShortArray, override val channels: Int) : PcmReader {
    override val frames: Long get() = (data.size / channels).toLong()

    override fun read(frame: Long, dst: ShortArray, n: Int) {
        for (k in 0 until n * channels) {
            val idx = frame * channels + k
            dst[k] = if (idx >= 0 && idx < data.size) data[idx.toInt()] else 0
        }
    }
}

/**
 * An imported backing track split into sections by start markers. Sections are contiguous:
 * each runs until the next marker, and the last runs to the end of the track.
 */
class TrackSource(private val pcm: PcmReader, starts: List<Long>) : MusicSource {
    private val starts = starts.sorted().toLongArray().also { require(it.isNotEmpty()) }
    private var buf = ShortArray(0)

    override val sectionCount get() = starts.size
    override val crossfadeFrames = SAMPLE_RATE / 100 // 10 ms

    fun sectionStart(i: Int) = starts[i]

    override fun sectionLength(i: Int): Long {
        val end = if (i + 1 < starts.size) starts[i + 1] else pcm.frames
        return maxOf(1, end - starts[i])
    }

    override fun isContiguous(from: Int, to: Int) = to == from + 1

    override fun render(i: Int, pos: Long, out: FloatArray, off: Int, n: Int) {
        val ch = pcm.channels
        if (buf.size < n * ch) buf = ShortArray(n * ch)
        pcm.read(starts[i] + pos, buf, n)
        val s = 1f / 32768f
        for (k in 0 until n) {
            val o = (off + k) * 2
            if (ch == 1) {
                val v = buf[k] * s
                out[o] += v
                out[o + 1] += v
            } else {
                out[o] += buf[k * ch] * s
                out[o + 1] += buf[k * ch + 1] * s
            }
        }
    }
}

object Markers {
    /** Frames per beat at [bpm]. */
    fun beatFrames(bpm: Double) = SAMPLE_RATE * 60.0 / bpm

    /**
     * Moves every marker except the first (always at 0) and the [anchor] to the nearest beat of
     * a grid passing through [anchor]. Markers that collide are dropped.
     */
    fun snapToBeat(starts: List<Long>, anchor: Long, bpm: Double): List<Long> =
        starts.map { snap(it, anchor, bpm) }.distinct().sorted()

    /** Nearest beat to [frame] on a grid through [anchor]. Frame 0 and the anchor stay put. */
    fun snap(frame: Long, anchor: Long, bpm: Double): Long {
        if (bpm <= 0 || frame == 0L || frame == anchor) return frame
        val beat = beatFrames(bpm)
        return (anchor + Math.round((frame - anchor) / beat) * beat).roundToLong().coerceAtLeast(1)
    }

    /** Tempo from a list of tap times in milliseconds (uses the last 8 taps). */
    fun bpmFromTaps(tapsMs: List<Long>): Double {
        val t = tapsMs.takeLast(8)
        if (t.size < 3) return 0.0
        val avg = (t.last() - t.first()).toDouble() / (t.size - 1)
        return if (avg <= 0) 0.0 else 60000.0 / avg
    }

    fun nearest(starts: List<Long>, frame: Long): Int =
        starts.indices.minByOrNull { abs(starts[it] - frame) } ?: -1
}

/**
 * Streaming linear-interpolation resampler for interleaved float audio. Keeps state between
 * calls so a file can be converted chunk by chunk.
 */
class Resampler(private val inRate: Int, private val outRate: Int, private val channels: Int) {
    private val step = inRate.toDouble() / outRate
    private val prev = FloatArray(channels)
    private var havePrev = false
    /** Position of the next output sample; 0 = `prev`, k = k-th new input frame. */
    private var t = 0.0

    /** Feeds [frames] input frames and returns the resampled interleaved output. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        if (inRate == outRate) return input.copyOf(frames * channels)
        if (frames == 0) return FloatArray(0)
        var skip = 0
        if (!havePrev) {
            for (c in 0 until channels) prev[c] = input[c]
            havePrev = true
            skip = 1
        }
        val avail = frames - skip // highest usable position
        val out = FloatArray(((avail + 1) / step + 2).toInt() * channels)
        var o = 0
        while (t.toInt() + 1 <= avail) {
            val i0 = t.toInt()
            val frac = (t - i0).toFloat()
            for (c in 0 until channels) {
                val a = if (i0 == 0) prev[c] else input[(skip + i0 - 1) * channels + c]
                val b = input[(skip + i0) * channels + c]
                out[o++] = a + (b - a) * frac
            }
            t += step
        }
        if (avail > 0) {
            for (c in 0 until channels) prev[c] = input[(frames - 1) * channels + c]
            t -= avail
        }
        return out.copyOf(o)
    }
}
