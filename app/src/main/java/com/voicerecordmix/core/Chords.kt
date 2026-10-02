package com.voicerecordmix.core

/** A parsed chord: [root] pitch class (0 = C), [intervals] above the root, optional slash [bass]. */
data class Chord(val root: Int, val intervals: IntArray, val bass: Int = root) {
    override fun equals(other: Any?): Boolean =
        other is Chord && root == other.root && bass == other.bass && intervals.contentEquals(other.intervals)

    override fun hashCode(): Int = (root * 31 + bass) * 31 + intervals.contentHashCode()
}

/** One bar of a generated section: one or more chords splitting the bar equally. null = silence. */
typealias Bar = List<Chord?>

object Chords {
    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val LETTERS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    private val QUALITIES = linkedMapOf(
        "maj7" to intArrayOf(0, 4, 7, 11),
        "m7" to intArrayOf(0, 3, 7, 10),
        "sus4" to intArrayOf(0, 5, 7),
        "sus2" to intArrayOf(0, 2, 7),
        "dim" to intArrayOf(0, 3, 6),
        "aug" to intArrayOf(0, 4, 8),
        "add9" to intArrayOf(0, 4, 7, 14),
        "m" to intArrayOf(0, 3, 7),
        "7" to intArrayOf(0, 4, 7, 10),
        "6" to intArrayOf(0, 4, 7, 9),
        // Sa–Pa "power chord": no third, so it fits major and minor melodies alike.
        "5" to intArrayOf(0, 7, 12),
        "" to intArrayOf(0, 4, 7),
    )

    fun noteName(pc: Int): String = NOTE_NAMES[((pc % 12) + 12) % 12]

    private fun parseNote(s: String): Pair<Int, Int>? {
        if (s.isEmpty()) return null
        val base = LETTERS[s[0].uppercaseChar()] ?: return null
        var pc = base
        var used = 1
        if (s.length > 1 && (s[1] == '#' || s[1] == 'b')) {
            pc += if (s[1] == '#') 1 else -1
            used = 2
        }
        return ((pc + 12) % 12) to used
    }

    /** Parses one chord like "Am", "G7", "D/F#". Returns null for unknown text. */
    fun parse(token: String): Chord? {
        val t = token.trim()
        val slash = t.indexOf('/')
        val main = if (slash >= 0) t.substring(0, slash) else t
        val (root, used) = parseNote(main) ?: return null
        val quality = main.substring(used)
        val intervals = QUALITIES[quality] ?: QUALITIES[quality.lowercase()] ?: return null
        val bass = if (slash >= 0) parseNote(t.substring(slash + 1))?.first ?: return null else root
        return Chord(root, intervals, bass)
    }

    /**
     * Parses a section's chord line into bars. "-" repeats the previous bar's last chord,
     * "N" or "x" is a bar without chords. Unknown tokens count as a repeat so a typo never
     * changes the song length.
     */
    fun parseBars(line: String): List<Bar> {
        val bars = ArrayList<Bar>()
        var last: Chord? = null
        for (tok in line.trim().split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            val parts = tok.split(',').filter { it.isNotEmpty() }
            val bar = parts.map { p ->
                when (p) {
                    "-", "%" -> last
                    "N", "n", "x", "X" -> null
                    else -> parse(p) ?: last
                }.also { last = it }
            }
            bars.add(bar.ifEmpty { listOf(last) })
        }
        return bars.ifEmpty { listOf(listOf(null)) }
    }

    fun transpose(c: Chord, semis: Int): Chord =
        Chord((c.root + semis + 120) % 12, c.intervals, (c.bass + semis + 120) % 12)
}
