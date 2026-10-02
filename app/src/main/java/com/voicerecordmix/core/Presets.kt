package com.voicerecordmix.core

/**
 * Ready-made backgrounds: pick a style and a key and sing, with nothing to set up.
 *
 * By default the keys play a "Sa–Pa" drone (no major/minor third), which sits under almost any
 * song without clashing. With [Preset.song]'s `chordChanges` the pads follow a simple
 * I–IV–V pattern instead.
 */
enum class Preset(
    val label: String,
    val description: String,
    val rhythm: Rhythm,
    val bpm: Int,
    val drums: Float,
    val pad: Float,
    val bass: Float,
    val tanpura: Float,
    /** Pallavi / Charanam chords in C, used when chord changes are on. */
    val pallavi: String,
    val charanam: String,
) {
    SLOW_WORSHIP(
        "Slow worship", "Soft 4/4 beat with keys. For quiet, heartfelt songs.",
        Rhythm.SLOW, 68, 0.6f, 0.6f, 0.5f, 0.2f,
        "C C F C C G C C", "F F C C G G C C",
    ),
    BHAJAN(
        "Bhajan 6/8", "Gentle dholak in 6/8, the classic devotional feel.",
        Rhythm.BHAJAN, 88, 0.8f, 0.45f, 0.5f, 0.3f,
        "C C F C G F C C", "F F C C G G C C",
    ),
    KEHERWA(
        "Keherwa praise", "Lively tabla Keherwa for joyful praise songs.",
        Rhythm.KEHERWA, 104, 0.85f, 0.4f, 0.6f, 0.25f,
        "C C F C G G C C", "F F C C F G C C",
    ),
    DADRA(
        "Dadra devotional", "Flowing tabla Dadra, slower devotional songs.",
        Rhythm.DADRA, 78, 0.8f, 0.45f, 0.5f, 0.3f,
        "C C F C G G C C", "F F C C G G C C",
    ),
    POP(
        "Praise band 4/4", "Drum kit and keys, modern praise style.",
        Rhythm.POP, 96, 0.75f, 0.5f, 0.6f, 0f,
        "C Am F G C Am F,G C", "F G C Am F G C C",
    ),
    DRONE(
        "Tanpura & keys only", "No drums: a peaceful drone for prayer and free worship.",
        Rhythm.SLOW, 70, 0f, 0.55f, 0f, 0.5f,
        "C C F C", "F F C C",
    );

    /** Builds a ready-to-sing song in [key] (0 = C .. 11 = B). */
    fun song(key: Int, bpm: Int = this.bpm, chordChanges: Boolean = false): Song {
        val drone = "C5 C5"
        val sections = if (chordChanges) listOf(
            Section("Intro", chords = "C C", hold = false),
            Section("Pallavi", chords = pallavi, hold = true),
            Section("Charanam", chords = charanam, hold = true),
            Section("Ending", chords = "C C", hold = false),
        ) else listOf(
            Section("Intro", chords = drone, hold = false),
            Section("Sing", chords = drone, hold = true),
            Section("Ending", chords = drone, hold = false),
        )
        return Song(
            id = "ready-$name",
            title = "$label · ${Chords.noteName(key)}",
            kind = SongKind.GENERATED,
            sections = sections,
            gen = GenSettings(
                key = 0, transpose = if (key > 6) key - 12 else key, bpm = bpm, rhythm = rhythm,
                drumsVol = drums, padVol = pad, bassVol = bass, tanpuraVol = tanpura,
            ),
            createdAt = System.currentTimeMillis(),
        )
    }
}
