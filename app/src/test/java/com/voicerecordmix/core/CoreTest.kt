package com.voicerecordmix.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ChordsTest {
    @Test
    fun parsesCommonChords() {
        val am = Chords.parse("Am")!!
        assertEquals(9, am.root)
        assertArrayEquals(intArrayOf(0, 3, 7), am.intervals)
        assertEquals(10, Chords.parse("Bb")!!.root)
        assertEquals(6, Chords.parse("F#m7")!!.root)
        val slash = Chords.parse("D/F#")!!
        assertEquals(2, slash.root)
        assertEquals(6, slash.bass)
        assertNull(Chords.parse("H"))
    }

    @Test
    fun parsesBarsWithSplitsRepeatsAndRests() {
        val bars = Chords.parseBars("C  F,G - N")
        assertEquals(4, bars.size)
        assertEquals(2, bars[1].size)
        assertEquals(7, bars[2][0]!!.root) // "-" repeats the last chord (G)
        assertNull(bars[3][0])
        assertEquals(1, Chords.parseBars("").size)
    }

    @Test
    fun transposeWraps() {
        assertEquals(1, Chords.transpose(Chords.parse("B")!!, 2).root)
    }
}

class MarkersTest {
    @Test
    fun snapsToBeatGridThroughAnchor() {
        val bpm = 120.0 // 24000 frames per beat
        val anchor = 10_000L
        val snapped = Markers.snapToBeat(listOf(0L, anchor, 10_000L + 24_000 * 4 + 900), anchor, bpm)
        assertEquals(listOf(0L, anchor, anchor + 96_000), snapped)
    }

    @Test
    fun tempoFromTaps() {
        assertEquals(100.0, Markers.bpmFromTaps(listOf(0, 600, 1200, 1800)), 0.01)
        assertEquals(0.0, Markers.bpmFromTaps(listOf(0, 600)), 0.0)
    }
}

class ResamplerTest {
    private fun sine(n: Int, rate: Int, hz: Double) = FloatArray(n * 2) { i -> sin(2 * PI * hz * (i / 2) / rate).toFloat() }

    @Test
    fun resamples44kTo48kAccurately() {
        val inN = 44100
        val input = sine(inN, 44100, 440.0)
        val out = Resampler(44100, 48000, 2).process(input, inN)
        val frames = out.size / 2
        assertTrue("frames=$frames", abs(frames - 48000) <= 2)
        var maxErr = 0.0
        for (k in 0 until frames - 1) {
            val expect = sin(2 * PI * 440.0 * k / 48000)
            maxErr = maxOf(maxErr, abs(out[k * 2] - expect))
        }
        assertTrue("err=$maxErr", maxErr < 0.01)
    }

    @Test
    fun chunkedEqualsWhole() {
        val n = 10_000
        val input = sine(n, 44100, 1000.0)
        val whole = Resampler(44100, 48000, 2).process(input, n)
        val r = Resampler(44100, 48000, 2)
        val parts = ArrayList<Float>()
        var i = 0
        val sizes = intArrayOf(1, 7, 1000, 333, 4096)
        var s = 0
        while (i < n) {
            val c = minOf(sizes[s++ % sizes.size], n - i)
            parts.addAll(r.process(input.copyOfRange(i * 2, (i + c) * 2), c).toList())
            i += c
        }
        assertEquals(whole.size, parts.size)
        for (k in whole.indices) assertEquals(whole[k], parts[k], 1e-5f)
    }
}

class MixerTest {
    @Test
    fun voiceIsShiftedByLeadFrames() {
        val frames = 4000
        val music = ArrayPcm(ShortArray(frames * 2), 2)
        val v = ShortArray(frames).also { it[1000] = 16000 }
        val mixer = Mixer(music, ArrayPcm(v, 1), autoLead = 200)
        mixer.settings = MixSettings(musicVol = 1f, voiceVol = 1f, reverb = 0f, syncMs = 0f)
        val out = FloatArray(frames * 2)
        mixer.render(0, frames, out)
        val peak = (0 until frames).maxByOrNull { out[it * 2] }!!
        assertEquals(800, peak)

        // A manual +10 ms (480 frames) correction moves it earlier again.
        mixer.settings = mixer.settings.copy(syncMs = 10f)
        mixer.reset()
        mixer.render(0, frames, out)
        assertEquals(320, (0 until frames).maxByOrNull { out[it * 2] }!!)
    }

    @Test
    fun loudInputIsSoftClipped() {
        val frames = 100
        val m = ShortArray(frames * 2) { Short.MAX_VALUE }
        val v = ShortArray(frames) { Short.MAX_VALUE }
        val mixer = Mixer(ArrayPcm(m, 2), ArrayPcm(v, 1), 0)
        val out = FloatArray(frames * 2)
        mixer.render(0, frames, out)
        assertTrue(out.all { abs(it) <= 1f })
    }

    @Test
    fun wavHeaderSizes() {
        val h = Wav.header(48000, 2)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        val data = (h[40].toInt() and 0xff) or ((h[41].toInt() and 0xff) shl 8) or
            ((h[42].toInt() and 0xff) shl 16) or ((h[43].toInt() and 0xff) shl 24)
        assertEquals(48000 * 4, data)
    }
}

class SynthTest {
    private fun song(rhythm: Rhythm, bpm: Int, vararg chords: String) = Song(
        id = "t", title = "t", kind = SongKind.GENERATED,
        sections = chords.mapIndexed { i, c -> Section("S$i", chords = c) },
        gen = GenSettings(bpm = bpm, rhythm = rhythm),
    )

    @Test
    fun sectionLengthIsWholeBars() {
        // Keherwa at 80 bpm: 4 beats per bar = 3 s = 144000 frames.
        val src = SynthSource(song(Rhythm.KEHERWA, 80, "C F G C"))
        assertEquals(144_000L * 4, src.sectionLength(0))
        val dadra = SynthSource(song(Rhythm.DADRA, 90, "C G"))
        assertEquals(SAMPLE_RATE * 2L * 2, dadra.sectionLength(0))
    }

    @Test
    fun rendersAudibleBoundedAudioAcrossLoops() {
        for (r in Rhythm.entries) {
            val src = SynthSource(song(r, 100, "C Am", "F,G C"))
            val engine = LoopEngine(src, booleanArrayOf(true, false))
            val buf = FloatArray(960 * 2)
            var peak = 0f
            var energy = 0.0
            repeat(SAMPLE_RATE * 12 / 960) {
                engine.render(buf, 960)
                for (x in buf) {
                    assertTrue(!x.isNaN())
                    peak = maxOf(peak, abs(x))
                    energy += x * x
                }
            }
            assertTrue("$r silent", energy > 1.0)
            assertTrue("$r peak $peak", peak < 2.5f)
            assertTrue(engine.state.pass >= 2)
        }
    }

    @Test
    fun chordParserFeedsSynthWithoutChords() {
        val src = SynthSource(song(Rhythm.POP, 120, "N N"))
        assertNotNull(src)
        val out = FloatArray(4800 * 2)
        src.render(0, 0, out, 0, 4800)
        assertTrue(out.any { it != 0f }) // drums still play
    }
}
