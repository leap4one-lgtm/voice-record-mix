package com.voicerecordmix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SamplePackTest {
    @Test
    fun recognisesStrokeFileNames() {
        assertEquals(Stroke.DHA, SamplePack.strokeForFileName("Dha.wav"))
        assertEquals(Stroke.GE, SamplePack.strokeForFileName("Dholak_Ge-02.wav"))
        assertEquals(Stroke.DHI, SamplePack.strokeForFileName("tabla dhin.mp3"))
        assertEquals(Stroke.TIN, SamplePack.strokeForFileName("TIN.WAV"))
        assertEquals(Stroke.TI, SamplePack.strokeForFileName("te.wav"))
        assertEquals(Stroke.NA, SamplePack.strokeForFileName("tabla_na_1.wav"))
        assertEquals(Stroke.KICK, SamplePack.strokeForFileName("kick 1.wav"))
        assertEquals(Stroke.HAT, SamplePack.strokeForFileName("shaker.wav"))
        assertNull(SamplePack.strokeForFileName("tabla.wav"))
        assertNull(SamplePack.strokeForFileName("snarey.wav"))
    }

    private fun song(useSamples: Boolean = true) = Song(
        "s", "s", SongKind.GENERATED, listOf(Section("P", chords = "N")),
        gen = GenSettings(bpm = 120, rhythm = Rhythm.KEHERWA, padVol = 0f, bassVol = 0f, tanpuraVol = 0f,
            useSamples = useSamples),
    )

    /** A recognisable "hit": constant 0.5 for 100 frames. */
    private fun hit() = FloatArray(200) { 0.5f }

    @Test
    fun synthPlaysSampleForStroke() {
        val pack = SamplePack(mapOf(Stroke.DHA to hit()))
        val out = FloatArray(400)
        SynthSource(song(), pack).render(0, 0, out, 0, 200)
        // The bar starts with Dha: the sample (flat 0.5 * gain) appears straight away.
        assertTrue(out[0] > 0.1f)
        assertEquals(out[0], out[2 * 50], 1e-6f)
        assertEquals(0f, out[2 * 150], 1e-6f) // sample over, nothing synthesized on top
    }

    @Test
    fun compoundBolBuiltFromHalves() {
        val pack = SamplePack(mapOf(Stroke.GE to hit(), Stroke.NA to hit()))
        // Reference: a single sampled hit at the same velocity.
        val one = FloatArray(40)
        SynthSource(song(), SamplePack(mapOf(Stroke.DHA to hit()))).render(0, 0, one, 0, 20)
        val both = FloatArray(40)
        SynthSource(song(), pack).render(0, 0, both, 0, 20)
        assertEquals(one[0] * 2, both[0], 1e-5f)
    }

    @Test
    fun samplesCanBeTurnedOffPerSong() {
        val out = FloatArray(400)
        SynthSource(song(useSamples = false), SamplePack(mapOf(Stroke.DHA to hit()))).render(0, 0, out, 0, 200)
        // Synthesized Dha starts from silence rather than the flat sample value.
        assertTrue(abs(out[0]) < 0.05f)
    }
}

class CalibrationTest {
    private fun clickTrack(): FloatArray {
        val n = Calibration.totalFrames.toInt()
        val out = FloatArray(n * 2)
        Calibration.ClickSource().render(0, 0, out, 0, n)
        return out
    }

    @Test
    fun clicksAreWhereExpected() {
        val out = clickTrack()
        for (k in 0 until Calibration.CLICKS) {
            val f = Calibration.clickFrame(k).toInt()
            assertEquals(0f, out[(f - 1) * 2], 0f)
            assertTrue(abs(out[(f + 5) * 2]) > 0.1f)
        }
    }

    /** Simulated mic recording: the clicks arrive [lead] + [extra] frames after the music frames. */
    private fun recording(lead: Int, extra: Int, noise: Int = 60, gain: Float = 0.3f): ArrayPcm {
        val music = clickTrack()
        val frames = music.size / 2 + lead + extra + SAMPLE_RATE
        var seed = 1234567
        val v = ShortArray(frames) {
            seed = seed * 1103515245 + 12345
            ((seed ushr 16) % (2 * noise + 1) - noise).toShort()
        }
        for (m in 0 until music.size / 2) {
            val i = m + lead + extra
            v[i] = (v[i] + music[m * 2] * gain * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return ArrayPcm(v, 1)
    }

    @Test
    fun measuresRemainingDelay() {
        val extra = SAMPLE_RATE * 37 / 1000 // 37 ms beyond the automatic lead (e.g. Bluetooth)
        val ms = Calibration.measure(recording(lead = 3000, extra = extra), lead = 3000)!!
        assertEquals(37f, ms, 1f)
    }

    @Test
    fun negativeDelayToo() {
        val ms = Calibration.measure(recording(lead = 5000, extra = -SAMPLE_RATE * 20 / 1000), lead = 5000)!!
        assertEquals(-20f, ms, 1f)
    }

    @Test
    fun silenceGivesNoResult() {
        assertNull(Calibration.measure(recording(lead = 0, extra = 0, gain = 0f), lead = 0))
    }
}

class PresetTest {
    @Test
    fun everyPresetPlaysInEveryKey() {
        for (p in Preset.entries) for (key in listOf(0, 2, 7, 11)) for (changes in listOf(false, true)) {
            val song = p.song(key, chordChanges = changes)
            assertEquals(key, ((song.gen.key + song.gen.transpose) % 12 + 12) % 12)
            val engine = LoopEngine(SynthSource(song), song.sections.map { it.hold }.toBooleanArray())
            val buf = FloatArray(960 * 2)
            var energy = 0.0
            repeat(SAMPLE_RATE * 3 / 960) {
                engine.render(buf, 960)
                for (x in buf) { assertTrue(!x.isNaN() && abs(x) < 3f); energy += x * x }
            }
            assertTrue("$p silent", energy > 0.5)
        }
    }

    @Test
    fun droneUsesSaPaChord() {
        val c = Chords.parse("C5")!!
        assertTrue(c.intervals.contentEquals(intArrayOf(0, 7, 12)))
    }
}
