package com.voicerecordmix.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders a full simulated session (loops, ×2, Next, fade-out, voice, mix) to WAV files in
 * build/demo so the sound can be inspected by ear or with audio tools.
 */
class DemoRenderTest {
    private val outDir = File("build/demo").apply { mkdirs() }

    private fun writeWav(name: String, interleaved: FloatArray, channels: Int) {
        val frames = interleaved.size / channels
        val bb = ByteBuffer.allocate(interleaved.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in interleaved) bb.putShort(toPcm16(x))
        File(outDir, name).outputStream().use { it.write(Wav.header(frames.toLong(), channels)); it.write(bb.array()) }
    }

    /** Runs [engine] for [seconds], calling [actions] at the given second marks. */
    private fun perform(engine: LoopEngine, seconds: Int, actions: Map<Int, (LoopEngine) -> Unit>): FloatArray {
        val block = 960
        val out = FloatArray(seconds * SAMPLE_RATE * 2)
        val buf = FloatArray(block * 2)
        var f = 0
        var lastSec = -1
        while (f + block <= seconds * SAMPLE_RATE) {
            val sec = f / SAMPLE_RATE
            if (sec != lastSec) { actions[sec]?.invoke(engine); lastSec = sec }
            engine.render(buf, block)
            System.arraycopy(buf, 0, out, f * 2, block * 2)
            f += block
        }
        return out
    }

    private val song = Song(
        id = "demo", title = "Demo", kind = SongKind.GENERATED,
        sections = listOf(
            Section("Intro", chords = "C G", hold = false),
            Section("Pallavi", chords = "C C F C"),
            Section("Interlude", chords = "Am F", hold = false),
            Section("Charanam 1", chords = "F G C Am", hold = true),
            Section("Ending", chords = "C", hold = false),
        ),
        gen = GenSettings(bpm = 96, rhythm = Rhythm.KEHERWA),
    )

    @Test
    fun rendersGeneratedSession() {
        val engine = LoopEngine(SynthSource(song), song.sections.map { it.hold }.toBooleanArray())
        // Bar = 2.5 s. Intro 5 s, Pallavi 10 s held: Next during 2nd pass, Charanam ×2 then Next.
        val music = perform(engine, 75, mapOf(
            18 to { e -> e.next() },
            36 to { e -> e.repeat(1) },
            68 to { e -> e.finish() },
        ))
        writeWav("generated_session.wav", music, 2)
        val peak = music.maxOf { abs(it) }
        assertTrue("peak $peak", peak in 0.05f..1.2f)
    }

    @Test
    fun rendersImportedLoopAndMix() {
        // Use a straight synth render as a stand-in "karaoke track", then loop it like an imported file.
        val straight = Song("t", "t", SongKind.GENERATED,
            listOf(Section("All", chords = "C C F C G G C C", hold = false)), gen = GenSettings(bpm = 96))
        val trackF = perform(LoopEngine(SynthSource(straight), booleanArrayOf(false)), 20, emptyMap())
        val track = ShortArray(trackF.size) { toPcm16(trackF[it]) }
        val bar = SAMPLE_RATE * 5 / 2L
        val src = TrackSource(ArrayPcm(track, 2), listOf(0L, bar * 2, bar * 4))
        val engine = LoopEngine(src, booleanArrayOf(false, true, false))
        val music = perform(engine, 40, mapOf(25 to { e -> e.next() }))
        writeWav("imported_loops.wav", music, 2)

        // Fake "voice": a sung-like tone per beat, recorded 120 ms late, mixed back in sync.
        val lead = SAMPLE_RATE * 120 / 1000
        val frames = music.size / 2
        val voice = ShortArray(frames + lead) { i ->
            val t = (i - lead).toDouble() / SAMPLE_RATE
            if (t < 0) 0 else {
                val beatPhase = (t * 96 / 60) % 1.0
                val env = if (beatPhase < 0.6) sin(PI * beatPhase / 0.6) else 0.0
                (sin(2 * PI * 330 * t) * env * 9000).toInt().toShort()
            }
        }
        val musicPcm = ShortArray(music.size) { toPcm16(music[it]) }
        val mixer = Mixer(ArrayPcm(musicPcm, 2), ArrayPcm(voice, 1), lead)
        mixer.settings = MixSettings(musicVol = 0.8f, voiceVol = 1f, reverb = 0.3f)
        val mix = FloatArray(frames * 2)
        mixer.render(0, frames, mix)
        writeWav("mixed.wav", mix, 2)
        assertTrue(mix.all { abs(it) <= 1f })
    }

    @Test
    fun rendersReadyMusicSamples() {
        for (p in Preset.entries) {
            val song = p.song(key = 2) // D
            val engine = LoopEngine(SynthSource(song), song.sections.map { it.hold }.toBooleanArray())
            engine.start(1)
            val music = perform(engine, 14, mapOf(11 to { e -> e.finish() }))
            writeWav("ready_${p.name.lowercase()}.wav", music, 2)
        }
    }
}
