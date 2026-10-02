package com.voicerecordmix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writes (section + 1) into every left sample so tests can read back what played. */
private class FakeSource(private val lengths: LongArray) : MusicSource {
    override val sectionCount = lengths.size
    override val crossfadeFrames = 0
    override fun sectionLength(i: Int) = lengths[i]
    override fun isContiguous(from: Int, to: Int) = to == from + 1
    override fun render(i: Int, pos: Long, out: FloatArray, off: Int, n: Int) {
        for (k in 0 until n) out[(off + k) * 2] += (i + 1).toFloat()
    }
}

class LoopEngineTest {
    /** Renders in small blocks, running [atFrame] hooks, and returns the section played per frame (-1 = silence). */
    private fun play(
        engine: LoopEngine, frames: Int, block: Int = 10,
        hooks: Map<Int, (LoopEngine) -> Unit> = emptyMap(),
    ): IntArray {
        val res = IntArray(frames)
        val buf = FloatArray(block * 2)
        var f = 0
        while (f < frames) {
            hooks[f]?.invoke(engine)
            engine.render(buf, block)
            for (k in 0 until block) if (f + k < frames) res[f + k] = buf[k * 2].toInt() - 1
            f += block
        }
        return res
    }

    /** Collapses per-frame sections into runs: [section, length]. */
    private fun runs(a: IntArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (s in a) {
            if (out.isNotEmpty() && out.last().first == s) out[out.size - 1] = s to out.last().second + 1
            else out.add(s to 1)
        }
        return out
    }

    @Test
    fun playsStraightThroughWithoutHold() {
        val e = LoopEngine(FakeSource(longArrayOf(100, 200, 150)), booleanArrayOf(false, false, false))
        val r = runs(play(e, 600))
        assertEquals(listOf(0 to 100, 1 to 200, 2 to 150, -1 to 150), r)
        assertTrue(e.state.ended)
    }

    @Test
    fun heldSectionLoopsUntilNext() {
        val e = LoopEngine(FakeSource(longArrayOf(100, 200, 150)), booleanArrayOf(false, true, false))
        // Next pressed during the third pass of section 1 (frames 500..700).
        val r = runs(play(e, 1000, hooks = mapOf(550 to { it.next() })))
        assertEquals(listOf(0 to 100, 1 to 600, 2 to 150, -1 to 150), r)
    }

    @Test
    fun repeatTimesThenAdvances() {
        val e = LoopEngine(FakeSource(longArrayOf(100, 100)), booleanArrayOf(false, false))
        val r = runs(play(e, 500, hooks = mapOf(20 to { it.repeat(2) })))
        assertEquals(listOf(0 to 300, 1 to 100, -1 to 100), r)
    }

    @Test
    fun repeatOverridesHoldAndStillAdvances() {
        val e = LoopEngine(FakeSource(longArrayOf(100, 100)), booleanArrayOf(true, false))
        val r = runs(play(e, 600, hooks = mapOf(150 to { it.repeat(2) })))
        // pass 1 (0-100), pass 2 held (100-200), then 2 more passes, then next.
        assertEquals(listOf(0 to 400, 1 to 100, -1 to 100), r)
    }

    @Test
    fun queuedSectionPlaysAfterCurrentPass() {
        val e = LoopEngine(FakeSource(longArrayOf(100, 100, 100)), booleanArrayOf(false, false, false))
        val r = runs(play(e, 600, hooks = mapOf(250 to { it.queue(0) })))
        assertEquals(listOf(0 to 100, 1 to 100, 2 to 100, 0 to 100, 1 to 100, 2 to 100), r)
    }

    @Test
    fun tappingNextTwiceCancels() {
        val e = LoopEngine(FakeSource(longArrayOf(100, 100)), booleanArrayOf(true, false))
        val r = runs(play(e, 300, hooks = mapOf(20 to { it.next() }, 40 to { it.next() })))
        assertEquals(listOf(0 to 300), r)
    }

    @Test
    fun passCounterAndStateTrackLoops() {
        val e = LoopEngine(FakeSource(longArrayOf(100, 100)), booleanArrayOf(true, false))
        play(e, 250)
        assertEquals(0, e.state.section)
        assertEquals(3, e.state.pass)
        assertTrue(e.state.hold)
    }

    @Test
    fun finishFadesOutAndEnds() {
        val e = LoopEngine(FakeSource(longArrayOf(SAMPLE_RATE * 10L)), booleanArrayOf(true))
        val buf = FloatArray(960 * 2)
        e.render(buf, 960)
        e.finish()
        var last = 1f
        repeat(SAMPLE_RATE * 5 / 960) {
            e.render(buf, 960)
            assertTrue(buf[0] <= last + 1e-6f)
            last = buf[0]
        }
        assertTrue(e.state.ended)
        assertEquals(0f, buf[0])
    }

    @Test
    fun seekJumpsImmediately() {
        val e = LoopEngine(FakeSource(longArrayOf(100, 100, 100)), booleanArrayOf(false, false, false))
        val r = runs(play(e, 200, hooks = mapOf(30 to { it.seek(2, 50) })))
        assertEquals(listOf(0 to 30, 2 to 50, -1 to 120), r)
    }

    @Test
    fun trackSourceCrossfadesLoopSeam() {
        // A ramp signal: a hard loop from frame 1000 back to 0 would jump from ~1.0 to 0.
        val frames = 2000
        val data = ShortArray(frames * 2) { (((it / 2) * 30000L) / frames).toShort() }
        val src = TrackSource(ArrayPcm(data, 2), listOf(0L, 1000L))
        val e = LoopEngine(src, booleanArrayOf(true, false))
        val buf = FloatArray(1500 * 2)
        e.render(buf, 1500)
        var maxJump = 0f
        for (k in 1 until 1500) maxJump = maxOf(maxJump, kotlin.math.abs(buf[k * 2] - buf[(k - 1) * 2]))
        // The seam is spread over 480 frames, so no single step is large.
        assertTrue("max jump $maxJump", maxJump < 0.01f)
        assertEquals(0, e.state.section)
        assertEquals(2, e.state.pass)
    }
}
