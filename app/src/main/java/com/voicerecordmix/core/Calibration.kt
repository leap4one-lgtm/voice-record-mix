package com.voicerecordmix.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Loopback sync calibration: play clicks while an earbud (or the speaker) is held at the mic,
 * record them, and measure how far the recorded clicks are from where the automatic sync
 * predicts. That remaining delay becomes the default voice-sync correction for that output.
 */
object Calibration {
    const val CLICKS = 8
    const val INTERVAL = SAMPLE_RATE / 2 // 0.5 s
    private const val CLICK_LEN = SAMPLE_RATE / 200 // 5 ms

    /** Music frame of click k (0-based). The first click is after 0.5 s of silence. */
    fun clickFrame(k: Int): Long = (k + 1L) * INTERVAL

    /** Length of the whole calibration run in frames (clicks plus a short tail). */
    val totalFrames: Long = clickFrame(CLICKS) + INTERVAL

    /** A one-section source that plays the calibration clicks. */
    class ClickSource : MusicSource {
        override val sectionCount = 1
        override val crossfadeFrames = 0
        override fun sectionLength(i: Int) = totalFrames
        override fun isContiguous(from: Int, to: Int) = false

        override fun render(i: Int, pos: Long, out: FloatArray, off: Int, n: Int) {
            for (k in 0 until n) {
                val f = pos + k
                if (f < INTERVAL || f >= clickFrame(CLICKS - 1) + CLICK_LEN) continue
                val inClick = ((f - INTERVAL) % INTERVAL).toInt()
                if (inClick >= CLICK_LEN) continue
                val t = inClick.toDouble() / SAMPLE_RATE
                val v = (sin(2 * PI * 2000 * t) * exp(-t * 900) * 0.9).toFloat()
                out[(off + k) * 2] += v
                out[(off + k) * 2 + 1] += v
            }
        }
    }

    /**
     * Returns the extra delay in ms between where the clicks were expected in the voice
     * recording (music frame + [lead]) and where they were found, or null if fewer than half
     * the clicks were heard clearly.
     */
    fun measure(voice: PcmReader, lead: Int): Float? {
        val before = SAMPLE_RATE / 4
        val after = SAMPLE_RATE * 35 / 100
        val win = ShortArray(before + after)
        val residuals = ArrayList<Int>()
        for (k in 0 until CLICKS) {
            val expected = clickFrame(k) + lead
            voice.read(expected - before, win, win.size)
            var peak = 0
            for (x in win) peak = maxOf(peak, abs(x.toInt()))
            if (peak < 600) continue // too quiet to trust (about -35 dB)
            // Ambient noise level: median of the window must be well below the click.
            val sorted = win.map { abs(it.toInt()) }.sorted()
            if (sorted[sorted.size / 2] * 4 > peak) continue
            val threshold = peak * 0.3
            val onset = win.indexOfFirst { abs(it.toInt()) >= threshold }
            residuals.add(onset - before)
        }
        if (residuals.size < CLICKS / 2) return null
        residuals.sort()
        val median = residuals[residuals.size / 2]
        // Reject runs where the clicks disagree a lot (talking, knocks, echoes).
        val spread = residuals.count { abs(it - median) > SAMPLE_RATE / 100 }
        if (spread > residuals.size / 3) return null
        return median * 1000f / SAMPLE_RATE
    }
}
