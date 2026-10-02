package com.voicerecordmix.core

import java.util.concurrent.ConcurrentLinkedQueue

/** Something that can play the sections of a song: a decoded track or the accompaniment synth. */
interface MusicSource {
    val sectionCount: Int
    fun sectionLength(i: Int): Long

    /** True if playing [to] right after [from] is just the natural continuation (no seam). */
    fun isContiguous(from: Int, to: Int): Boolean

    /**
     * Adds [n] stereo frames of section [i], starting at [pos] frames into it, into the
     * interleaved buffer [out] beginning at frame [off]. [pos] may run past the section end
     * (used for crossfade tails).
     */
    fun render(i: Int, pos: Long, out: FloatArray, off: Int, n: Int)

    /** Called on a hard jump (seek), so stateful sources can reset. Not called on loops. */
    fun onSeek(i: Int, pos: Long) {}

    /** Crossfade length used at non-contiguous joins. 0 = source handles joins itself. */
    val crossfadeFrames: Int
}

data class EngineState(
    val section: Int = 0,
    val pos: Long = 0,
    val sectionLength: Long = 1,
    /** Which time through this section we are on (1 = first). */
    val pass: Int = 1,
    /** Keeps looping this section until Next. */
    val hold: Boolean = false,
    /** Extra passes left from a ×2 / ×4 tap. */
    val repeatsLeft: Int = 0,
    val nextRequested: Boolean = false,
    /** Section queued to play after this pass, or -1. */
    val jumpTarget: Int = -1,
    val finishing: Boolean = false,
    val ended: Boolean = false,
)

/**
 * Plays a [MusicSource] section by section and decides at each section end what comes next.
 * Every decision lands exactly on a section boundary, so repeats stay in time with the music.
 *
 * Control methods may be called from any thread; they are applied on the next [render].
 */
class LoopEngine(
    private val source: MusicSource,
    private val holdDefaults: BooleanArray,
    autoHold: Boolean = true,
) {
    private sealed interface Cmd {
        data object Next : Cmd
        data class Repeat(val times: Int) : Cmd
        data object ToggleHold : Cmd
        data class SetHold(val on: Boolean) : Cmd
        data class Queue(val section: Int) : Cmd
        data class Seek(val section: Int, val pos: Long, val hold: Boolean?) : Cmd
        data object Finish : Cmd
        data class AutoHold(val on: Boolean) : Cmd
    }

    private val cmds = ConcurrentLinkedQueue<Cmd>()

    private var autoHold = autoHold
    private var section = 0
    private var pos = 0L
    private var pass = 1
    private var hold = false
    private var repeatsLeft = 0
    private var nextRequested = false
    private var jumpTarget = -1
    private var ended = false

    private var tailSection = 0
    private var tailPos = 0L
    private var tailLeft = 0
    private var tailBuf = FloatArray(0)

    private var fadeTotal = 0L
    private var fadeLeft = -1L

    @Volatile
    var state = EngineState()
        private set

    /** Absolute frame counter of everything rendered so far. */
    var framesRendered = 0L
        private set

    init {
        enter(0, 0)
        publish()
    }

    fun next() = cmds.add(Cmd.Next)
    fun repeat(times: Int) = cmds.add(Cmd.Repeat(times))
    fun toggleHold() = cmds.add(Cmd.ToggleHold)
    fun setHold(on: Boolean) = cmds.add(Cmd.SetHold(on))
    /** Play [section] after the current pass ends. */
    fun queue(section: Int) = cmds.add(Cmd.Queue(section))
    /** Jump immediately (with a short crossfade). [hold] overrides the section default. */
    fun seek(section: Int, pos: Long, hold: Boolean? = null) = cmds.add(Cmd.Seek(section, pos, hold))
    /** Fade the music out over [seconds]. */
    fun finish() = cmds.add(Cmd.Finish)
    fun setAutoHold(on: Boolean) = cmds.add(Cmd.AutoHold(on))

    /** Starts from [section] at [pos]; only call before rendering begins. */
    fun start(section: Int, pos: Long = 0) {
        enter(section.coerceIn(0, source.sectionCount - 1), pos)
        source.onSeek(this.section, this.pos)
        publish()
    }

    private fun enter(target: Int, startPos: Long) {
        section = target
        pos = startPos
        pass = 1
        hold = autoHold && holdDefaults.getOrElse(target) { false }
        repeatsLeft = 0
    }

    private fun applyCommands() {
        while (true) {
            when (val c = cmds.poll() ?: return) {
                Cmd.Next -> {
                    nextRequested = !nextRequested || jumpTarget >= 0
                    jumpTarget = -1
                    if (nextRequested) repeatsLeft = 0
                }
                is Cmd.Repeat -> {
                    repeatsLeft = c.times
                    hold = false
                    nextRequested = false
                    jumpTarget = -1
                }
                Cmd.ToggleHold -> {
                    hold = !hold
                    if (hold) { nextRequested = false; repeatsLeft = 0 }
                }
                is Cmd.SetHold -> hold = c.on
                is Cmd.Queue -> {
                    jumpTarget = if (jumpTarget == c.section) -1 else c.section
                    nextRequested = false
                }
                is Cmd.Seek -> {
                    if (ended) continue
                    startTail()
                    enter(c.section.coerceIn(0, source.sectionCount - 1),
                        c.pos.coerceIn(0, source.sectionLength(c.section) - 1))
                    c.hold?.let { hold = it }
                    nextRequested = false
                    jumpTarget = -1
                    source.onSeek(section, pos)
                }
                Cmd.Finish -> if (fadeLeft < 0) {
                    fadeTotal = SAMPLE_RATE * 4L
                    fadeLeft = fadeTotal
                }
                is Cmd.AutoHold -> autoHold = c.on
            }
        }
    }

    private fun startTail() {
        if (source.crossfadeFrames <= 0) return
        tailSection = section
        tailPos = pos
        tailLeft = source.crossfadeFrames
    }

    /** Called when the current section has played to its end. */
    private fun onSectionEnd() {
        val target: Int
        var fresh = true
        when {
            jumpTarget >= 0 -> { target = jumpTarget; jumpTarget = -1 }
            nextRequested -> target = section + 1
            repeatsLeft > 0 -> { repeatsLeft--; target = section; fresh = false }
            hold -> { target = section; fresh = false }
            else -> target = section + 1
        }
        nextRequested = false
        if (target >= source.sectionCount) {
            ended = true
            return
        }
        val contiguous = target == section + 1 && source.isContiguous(section, target)
        if (!contiguous) startTail()
        if (fresh) {
            enter(target, 0)
        } else {
            pos = 0
            pass++
        }
    }

    /** Renders [n] stereo frames into [out] (overwriting). After the song ends it outputs silence. */
    fun render(out: FloatArray, n: Int) {
        java.util.Arrays.fill(out, 0, n * 2, 0f)
        applyCommands()
        var done = 0
        while (done < n && !ended) {
            val left = source.sectionLength(section) - pos
            if (left <= 0) {
                onSectionEnd()
                continue
            }
            var chunk = minOf((n - done).toLong(), left).toInt()
            if (tailLeft > 0) chunk = minOf(chunk, tailLeft)
            source.render(section, pos, out, done, chunk)
            if (tailLeft > 0) mixTail(out, done, chunk)
            pos += chunk
            done += chunk
        }
        if (fadeLeft >= 0) applyFade(out, n)
        framesRendered += n
        publish()
    }

    private fun mixTail(out: FloatArray, off: Int, n: Int) {
        if (tailBuf.size < n * 2) tailBuf = FloatArray(n * 2)
        java.util.Arrays.fill(tailBuf, 0, n * 2, 0f)
        source.render(tailSection, tailPos, tailBuf, 0, n)
        val total = source.crossfadeFrames.toFloat()
        val startIdx = source.crossfadeFrames - tailLeft
        for (k in 0 until n) {
            val g = (startIdx + k + 1) / total
            val o = (off + k) * 2
            out[o] = out[o] * g + tailBuf[k * 2] * (1 - g)
            out[o + 1] = out[o + 1] * g + tailBuf[k * 2 + 1] * (1 - g)
        }
        tailPos += n
        tailLeft -= n
    }

    private fun applyFade(out: FloatArray, n: Int) {
        for (k in 0 until n) {
            val g = if (fadeLeft > 0) fadeLeft.toFloat() / fadeTotal else 0f
            out[k * 2] *= g
            out[k * 2 + 1] *= g
            if (fadeLeft > 0) fadeLeft--
        }
        if (fadeLeft == 0L) ended = true
    }

    private fun publish() {
        state = EngineState(
            section = section,
            pos = pos,
            sectionLength = source.sectionLength(section),
            pass = pass,
            hold = hold,
            repeatsLeft = repeatsLeft,
            nextRequested = nextRequested,
            jumpTarget = jumpTarget,
            finishing = fadeLeft >= 0,
            ended = ended,
        )
    }
}
