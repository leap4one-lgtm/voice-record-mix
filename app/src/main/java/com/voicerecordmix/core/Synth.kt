package com.voicerecordmix.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

private const val TABLE = 4096

private object Wave {
    val sine = FloatArray(TABLE + 1) { sin(2 * PI * it / TABLE).toFloat() }

    /** Soft band-limited saw (7 harmonics) for pads. */
    val pad = FloatArray(TABLE + 1) { i ->
        var v = 0.0
        for (h in 1..7) v += sin(2 * PI * h * i / TABLE) / h
        (v * 0.55).toFloat()
    }

    fun read(table: FloatArray, phase: Double): Float {
        val x = phase * TABLE
        val i = x.toInt()
        val f = (x - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * f
    }
}

fun midiHz(m: Double) = 440.0 * 2.0.pow((m - 69) / 12)

private fun decayPerSample(seconds: Double) = exp(-1.0 / (seconds * SAMPLE_RATE)).toFloat()

/** Tiny deterministic noise source (xorshift). */
private class Noise(seed: Int = 0x2545F491) {
    private var s = seed
    fun next(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return (s and 0xFFFF) / 32768f - 1f
    }
}

private abstract class Voice {
    /** Adds [n] frames into [out] at frame [off]. Returns false once the voice is silent. */
    abstract fun render(out: FloatArray, off: Int, n: Int): Boolean
    open fun release() {}
}

private class Partial(val ratio: Double, val amp: Float, val decay: Double)

private class StrokeSpec(
    val partials: List<Partial>,
    val glideFrom: Double = 1.0,
    val glideTime: Double = 0.04,
    val noiseAmp: Float = 0f,
    val noiseDecay: Double = 0.01,
    /** One-pole coefficient: low values = darker noise. */
    val noiseColor: Float = 0.5f,
    val highpassNoise: Boolean = false,
)

private class DrumVoice(
    spec: StrokeSpec, private val freq: Double, private val gain: Float, pan: Float, private val noise: Noise,
) : Voice() {
    private val parts = spec.partials
    private val phases = DoubleArray(parts.size)
    private val amps = FloatArray(parts.size) { parts[it].amp }
    private val decays = FloatArray(parts.size) { decayPerSample(parts[it].decay) }
    private var glide = spec.glideFrom - 1.0
    private val glideMul = exp(-1.0 / (spec.glideTime * SAMPLE_RATE))
    private var nAmp = spec.noiseAmp
    private val nDecay = decayPerSample(spec.noiseDecay)
    private val nColor = spec.noiseColor
    private val hp = spec.highpassNoise
    private var lp = 0f
    private val gl = gain * (1 - pan) * 0.9f
    private val gr = gain * (1 + pan) * 0.9f

    override fun render(out: FloatArray, off: Int, n: Int): Boolean {
        var alive = false
        for (k in 0 until n) {
            val f = freq * (1 + glide)
            glide *= glideMul
            var v = 0f
            for (p in parts.indices) {
                if (amps[p] < 1e-4f) continue
                v += Wave.read(Wave.sine, phases[p]) * amps[p]
                phases[p] += f * parts[p].ratio / SAMPLE_RATE
                if (phases[p] >= 1.0) phases[p] -= phases[p].toInt()
                amps[p] *= decays[p]
            }
            if (nAmp > 1e-4f) {
                val x = noise.next()
                lp += nColor * (x - lp)
                v += (if (hp) x - lp else lp) * nAmp
                nAmp *= nDecay
            }
            out[(off + k) * 2] += v * gl
            out[(off + k) * 2 + 1] += v * gr
        }
        for (a in amps) if (a >= 1e-4f) alive = true
        return alive || nAmp >= 1e-4f
    }
}

enum class Stroke { GE, KA, NA, TIN, TI, DHA, DHI, KICK, SNARE, HAT }

private class Hit(val half: Int, val stroke: Stroke, val vel: Float)

private fun pattern(rhythm: Rhythm): List<Hit> = when (rhythm) {
    // Dha Ge Na Ti | Na Ka Dhi Na
    Rhythm.KEHERWA -> listOf(
        Hit(0, Stroke.DHA, 1f), Hit(2, Stroke.GE, 0.7f), Hit(4, Stroke.NA, 0.8f), Hit(6, Stroke.TI, 0.6f),
        Hit(8, Stroke.NA, 0.8f), Hit(10, Stroke.KA, 0.6f), Hit(11, Stroke.GE, 0.45f),
        Hit(12, Stroke.DHI, 0.9f), Hit(14, Stroke.NA, 0.7f), Hit(13, Stroke.TI, 0.3f),
    )
    // Dha Dhi Na | Dha Ti Na
    Rhythm.DADRA -> listOf(
        Hit(0, Stroke.DHA, 1f), Hit(2, Stroke.DHI, 0.8f), Hit(4, Stroke.NA, 0.75f), Hit(5, Stroke.GE, 0.4f),
        Hit(6, Stroke.DHA, 0.85f), Hit(8, Stroke.TI, 0.6f), Hit(10, Stroke.NA, 0.7f),
    )
    // Dholak bhajan feel: Dha . Ge Na | Dhin . Ge Na Ka
    Rhythm.BHAJAN -> listOf(
        Hit(0, Stroke.DHA, 1f), Hit(3, Stroke.GE, 0.45f), Hit(4, Stroke.NA, 0.7f),
        Hit(6, Stroke.DHI, 0.85f), Hit(8, Stroke.GE, 0.55f), Hit(10, Stroke.NA, 0.7f), Hit(11, Stroke.KA, 0.4f),
    )
    // Soft kick, rim on 3, quiet eighth-note hats.
    Rhythm.SLOW -> buildList {
        for (m in 0 until 8) add(Hit(m * 2, Stroke.HAT, if (m % 2 == 0) 0.25f else 0.15f))
        add(Hit(0, Stroke.KICK, 0.9f)); add(Hit(10, Stroke.KICK, 0.5f)); add(Hit(8, Stroke.SNARE, 0.5f))
    }
    Rhythm.POP -> buildList {
        for (m in 0 until 8) add(Hit(m * 2, Stroke.HAT, if (m % 2 == 0) 0.55f else 0.3f))
        add(Hit(0, Stroke.KICK, 1f)); add(Hit(8, Stroke.KICK, 0.9f)); add(Hit(10, Stroke.KICK, 0.6f))
        add(Hit(4, Stroke.SNARE, 0.9f)); add(Hit(12, Stroke.SNARE, 0.9f))
    }
}

/** Half-matra positions (within a bar) where the bass plays the chord root. */
private fun bassHits(rhythm: Rhythm): IntArray = when (rhythm) {
    Rhythm.KEHERWA -> intArrayOf(0, 8, 14)
    Rhythm.DADRA -> intArrayOf(0, 6)
    Rhythm.BHAJAN -> intArrayOf(0, 6)
    Rhythm.SLOW -> intArrayOf(0, 8)
    Rhythm.POP -> intArrayOf(0, 6, 8)
}

/** Plays a recorded drum hit (stereo interleaved, 48 kHz). */
private class SampleVoice(private val data: FloatArray, gain: Float, pan: Float) : Voice() {
    private var pos = 0
    private val gl = gain * (1 - pan)
    private val gr = gain * (1 + pan)

    override fun render(out: FloatArray, off: Int, n: Int): Boolean {
        val frames = data.size / 2
        val m = minOf(n, frames - pos)
        for (k in 0 until m) {
            out[(off + k) * 2] += data[(pos + k) * 2] * gl
            out[(off + k) * 2 + 1] += data[(pos + k) * 2 + 1] * gr
        }
        pos += m
        return pos < frames
    }
}

/**
 * Recorded tabla/dholak/kit hits, one per [Stroke]. Strokes without a sample fall back to
 * the synthesized sound (Dha and Dhin are built from Ge + Na/Tin samples if those exist).
 */
class SamplePack(val hits: Map<Stroke, FloatArray>) {
    companion object {
        private val ALIASES = linkedMapOf(
            Stroke.DHA to listOf("dha", "dhaa"),
            Stroke.DHI to listOf("dhi", "dhin", "dhim"),
            Stroke.TIN to listOf("tin", "tun", "thun", "tu"),
            Stroke.TI to listOf("ti", "te", "tit", "tete", "re"),
            Stroke.NA to listOf("na", "naa", "ta", "taa"),
            Stroke.GE to listOf("ge", "ghe", "ga", "gha", "ghi", "bayan"),
            Stroke.KA to listOf("ka", "ke", "kat", "ki", "kath"),
            Stroke.KICK to listOf("kick", "bd", "kik", "bassdrum"),
            Stroke.SNARE to listOf("snare", "sd", "rim", "clap"),
            Stroke.HAT to listOf("hat", "hh", "hihat", "shaker", "chh"),
        )

        /** Which stroke a file name like "Dholak_Ge-02.wav" is for, or null. */
        fun strokeForFileName(name: String): Stroke? {
            val tokens = name.substringBeforeLast('.').lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            for (t in tokens) for ((stroke, names) in ALIASES) if (t in names) return stroke
            return null
        }

        /** File names the pack understands, for help text. */
        val EXPECTED = "dha, dhin, na, tin, ti, ge, ka (or kick, snare, hat)"
    }
}

private class PadVoice(midi: Double, private val gain: Float) : Voice() {
    private val inc1 = midiHz(midi + 0.07) / SAMPLE_RATE
    private val inc2 = midiHz(midi - 0.07) / SAMPLE_RATE
    private var p1 = 0.0
    private var p2 = 0.37
    private var env = 0f
    private val attack = 1f / (0.25f * SAMPLE_RATE)
    private val rel = decayPerSample(0.25)
    private var releasing = false
    private var lpL = 0f
    private var lpR = 0f
    private val lpc = 0.18f

    override fun release() { releasing = true }

    override fun render(out: FloatArray, off: Int, n: Int): Boolean {
        for (k in 0 until n) {
            if (releasing) env *= rel else if (env < 1f) env = minOf(1f, env + attack)
            val a = Wave.read(Wave.pad, p1)
            val b = Wave.read(Wave.pad, p2)
            p1 += inc1; if (p1 >= 1) p1 -= 1
            p2 += inc2; if (p2 >= 1) p2 -= 1
            lpL += lpc * ((a * 0.7f + b * 0.3f) - lpL)
            lpR += lpc * ((b * 0.7f + a * 0.3f) - lpR)
            out[(off + k) * 2] += lpL * env * gain
            out[(off + k) * 2 + 1] += lpR * env * gain
        }
        return !(releasing && env < 1e-4f)
    }
}

private class BassVoice(midi: Double, private val gain: Float) : Voice() {
    private val inc = midiHz(midi) / SAMPLE_RATE
    private var p = 0.0
    private var env = 0f
    private var peak = true
    private val atk = 1f / (0.005f * SAMPLE_RATE)
    private var decay = decayPerSample(0.9)

    override fun release() { decay = decayPerSample(0.02) }

    override fun render(out: FloatArray, off: Int, n: Int): Boolean {
        for (k in 0 until n) {
            if (peak) { env += atk; if (env >= 1f) { env = 1f; peak = false } } else env *= decay
            val v = Wave.read(Wave.sine, p) + 0.35f * Wave.read(Wave.sine, (p * 2) % 1.0) +
                0.12f * Wave.read(Wave.sine, (p * 3) % 1.0)
            p += inc; if (p >= 1) p -= 1
            val s = v * env * gain
            out[(off + k) * 2] += s
            out[(off + k) * 2 + 1] += s
        }
        return peak || env > 1e-4f
    }
}

/** Karplus-Strong tanpura: four strings plucked in the classic Pa Sa Sa Sa(low) cycle. */
private class Tanpura(sa: Double, private val gain: Float, private val noise: Noise) : Voice() {
    private val notes = doubleArrayOf(sa - 5, sa, sa, sa - 12)
    private val strings = notes.map { DoubleArray(maxOf(2, (SAMPLE_RATE / midiHz(it)).toInt())) }
    private val idx = IntArray(4)
    private val pans = floatArrayOf(-0.4f, 0.1f, 0.3f, -0.1f)
    private val interval = (0.65 * SAMPLE_RATE).toLong()
    private var clock = 0L
    private var nextPluck = 0
    private var nextAt = 0L

    private fun pluck(s: Int) {
        val buf = strings[s]
        var lp = 0.0
        for (i in buf.indices) {
            lp += 0.5 * (noise.next() - lp)
            buf[i] += lp * 0.6
        }
    }

    override fun render(out: FloatArray, off: Int, n: Int): Boolean {
        for (k in 0 until n) {
            if (clock >= nextAt) {
                pluck(nextPluck)
                nextPluck = (nextPluck + 1) % 4
                nextAt = clock + if (nextPluck == 0) interval * 2 else interval
            }
            var l = 0f
            var r = 0f
            for (s in 0 until 4) {
                val buf = strings[s]
                val i = idx[s]
                val j = if (i + 1 == buf.size) 0 else i + 1
                val y = 0.4985 * (buf[i] + buf[j]) + 0.0012 * buf[i] // slight brightness
                buf[i] = y
                idx[s] = j
                val v = y.toFloat()
                l += v * (1 - pans[s])
                r += v * (1 + pans[s])
            }
            out[(off + k) * 2] += l * gain
            out[(off + k) * 2 + 1] += r * gain
            clock++
        }
        return true
    }
}

/**
 * Generated accompaniment: tabla/dholak (or a pop kit), chord pads, bass and tanpura,
 * following each section's chord line. Section lengths are whole bars, so loops stay on beat.
 */
class SynthSource(song: Song, samples: SamplePack? = null) : MusicSource {
    private val pack = samples?.takeIf { song.gen.useSamples && it.hits.isNotEmpty() }
    private val g = song.gen
    private val shift = g.transpose
    private val bars: List<List<Bar>> = song.sections.map { Chords.parseBars(it.chords) }
    private val matras = g.rhythm.matras
    private val halvesPerBar = matras * 2
    private val half: Double = SAMPLE_RATE * 60.0 / g.bpm.coerceIn(30, 240) * g.rhythm.beatsPerBar / matras / 2
    private val hits = pattern(g.rhythm)
    private val bassAt = bassHits(g.rhythm)
    private val noise = Noise()
    private val voices = ArrayList<Voice>()
    private val pads = ArrayList<Voice>()
    private var bassVoice: Voice? = null
    private var chord: Chord? = null
    private var needChord = true
    private val sa = 48.0 + ((g.key + shift) % 12 + 12) % 12
    private val trebleHz = midiHz(sa + 12)

    init {
        if (g.tanpuraVol > 0f) voices.add(Tanpura(sa, 0.25f * g.tanpuraVol, noise))
    }

    override val sectionCount = song.sections.size
    override val crossfadeFrames = 0

    private fun halves(i: Int) = bars[i].size * halvesPerBar
    private fun frameOf(h: Int): Long = (h * half).roundToLong()

    override fun sectionLength(i: Int): Long = frameOf(halves(i))
    override fun isContiguous(from: Int, to: Int) = true

    override fun onSeek(i: Int, pos: Long) {
        for (p in pads) p.release()
        pads.clear()
        bassVoice?.release()
        chord = null
        needChord = true
    }

    private fun chordAt(i: Int, h: Int): Chord? {
        val bar = bars[i][(h / halvesPerBar).coerceIn(0, bars[i].size - 1)]
        val inBar = h % halvesPerBar
        val idx = (inBar * bar.size / halvesPerBar).coerceIn(0, bar.size - 1)
        return bar[idx]?.let { Chords.transpose(it, shift) }
    }

    private fun setChord(c: Chord?) {
        if (c == chord && pads.isNotEmpty()) return
        chord = c
        for (p in pads) p.release()
        pads.clear()
        if (c == null || g.padVol <= 0f) return
        for (iv in c.intervals) {
            // Keep the pad in a warm middle register (G3..F#4).
            var m = 55 + ((c.root + iv - 55) % 12 + 12) % 12
            if (iv >= 12) m += 12
            val v = PadVoice(m.toDouble(), 0.09f * g.padVol)
            pads.add(v); voices.add(v)
        }
    }

    private fun strokeVoices(s: Stroke, vel: Float) {
        val dg = 0.5f * g.drumsVol * vel
        if (dg <= 0f) return
        pack?.let { p ->
            val pan = if (s == Stroke.HAT) 0.2f else 0f
            p.hits[s]?.let { voices.add(SampleVoice(it, dg * 1.3f, pan)); return }
            // Compound bols from their halves when only those were provided.
            val parts = when (s) {
                Stroke.DHA -> listOf(Stroke.GE, Stroke.NA)
                Stroke.DHI -> listOf(Stroke.GE, Stroke.TIN)
                else -> emptyList()
            }
            if (parts.isNotEmpty() && parts.all { it in p.hits }) {
                parts.forEach { strokeVoices(it, vel) }
                return
            }
        }
        fun add(spec: StrokeSpec, f: Double, pan: Float) = voices.add(DrumVoice(spec, f, dg, pan, noise))
        when (s) {
            Stroke.DHA -> { add(GE, 98.0, 0f); add(NA, trebleHz, 0.15f) }
            Stroke.DHI -> { add(GE, 98.0, 0f); add(TIN, trebleHz, 0.15f) }
            Stroke.GE -> add(GE, 98.0, -0.05f)
            Stroke.KA -> add(KA, 120.0, -0.05f)
            Stroke.NA -> add(NA, trebleHz, 0.15f)
            Stroke.TIN -> add(TIN, trebleHz, 0.15f)
            Stroke.TI -> add(TI, trebleHz, 0.2f)
            Stroke.KICK -> add(KICK, 52.0, 0f)
            Stroke.SNARE -> add(SNARE, 190.0, 0.05f)
            Stroke.HAT -> add(HAT, 1.0, 0.25f)
        }
    }

    private fun fire(i: Int, h: Int) {
        val inBar = h % halvesPerBar
        val c = chordAt(i, h)
        if (needChord || c != chord) { setChord(c); needChord = false }
        for (hit in hits) if (hit.half == inBar) strokeVoices(hit.stroke, hit.vel)
        if (g.bassVol > 0f && inBar in bassAt) {
            chord?.let { ch ->
                bassVoice?.release()
                val m = 36 + ((ch.bass - 36) % 12 + 12) % 12
                BassVoice(m.toDouble(), 0.22f * g.bassVol).also { bassVoice = it; voices.add(it) }
            }
        }
    }

    private fun renderVoices(out: FloatArray, off: Int, n: Int) {
        if (n <= 0) return
        val iter = voices.iterator()
        while (iter.hasNext()) {
            if (!iter.next().render(out, off, n)) iter.remove()
        }
    }

    override fun render(i: Int, pos: Long, out: FloatArray, off: Int, n: Int) {
        val total = halves(i)
        var cur = pos
        val end = pos + n
        if (needChord) {
            val h = (pos / half).toInt().coerceIn(0, total - 1)
            setChord(chordAt(i, h)); needChord = false
        }
        while (cur < end) {
            var h = maxOf(0, (cur / half).toInt() - 1)
            while (h < total && frameOf(h) < cur) h++
            if (h < total && frameOf(h) == cur) {
                fire(i, h)
                h++
            }
            val nextEvt = if (h < total) frameOf(h) else Long.MAX_VALUE
            val segEnd = minOf(end, nextEvt)
            renderVoices(out, off + (cur - pos).toInt(), (segEnd - cur).toInt())
            cur = segEnd
        }
    }

    private companion object {
        val GE = StrokeSpec(listOf(Partial(1.0, 1f, 0.32), Partial(2.0, 0.15f, 0.08)),
            glideFrom = 0.82, glideTime = 0.06, noiseAmp = 0.15f, noiseDecay = 0.006, noiseColor = 0.1f)
        val KA = StrokeSpec(listOf(Partial(1.0, 0.4f, 0.03)),
            noiseAmp = 0.7f, noiseDecay = 0.025, noiseColor = 0.08f)
        val NA = StrokeSpec(listOf(Partial(1.0, 0.7f, 0.22), Partial(2.0, 0.35f, 0.15),
            Partial(3.0, 0.2f, 0.09), Partial(4.2, 0.1f, 0.05)),
            noiseAmp = 0.3f, noiseDecay = 0.006, highpassNoise = true)
        val TIN = StrokeSpec(listOf(Partial(1.0, 0.8f, 0.55), Partial(2.0, 0.35f, 0.35),
            Partial(3.0, 0.15f, 0.18)), noiseAmp = 0.15f, noiseDecay = 0.005, highpassNoise = true)
        val TI = StrokeSpec(listOf(Partial(1.0, 0.35f, 0.03), Partial(2.7, 0.3f, 0.02)),
            noiseAmp = 0.45f, noiseDecay = 0.02, noiseColor = 0.6f, highpassNoise = true)
        val KICK = StrokeSpec(listOf(Partial(1.0, 1.1f, 0.3)), glideFrom = 3.0, glideTime = 0.025,
            noiseAmp = 0.2f, noiseDecay = 0.003)
        val SNARE = StrokeSpec(listOf(Partial(1.0, 0.4f, 0.07)),
            noiseAmp = 0.75f, noiseDecay = 0.12, noiseColor = 0.5f, highpassNoise = true)
        val HAT = StrokeSpec(emptyList(), noiseAmp = 0.35f, noiseDecay = 0.03,
            noiseColor = 0.7f, highpassNoise = true)
    }
}
