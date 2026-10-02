package com.voicerecordmix.core

import kotlinx.serialization.Serializable

/** All audio inside the app runs at this rate; imported tracks are resampled to it. */
const val SAMPLE_RATE = 48000

@Serializable
enum class SongKind { IMPORTED, GENERATED }

@Serializable
enum class Rhythm(val label: String, val matras: Int, val beatsPerBar: Int) {
    KEHERWA("Keherwa (8)", 8, 4),
    DADRA("Dadra (6)", 6, 3),
    BHAJAN("Bhajan 6/8", 6, 3),
    SLOW("Slow worship 4/4", 8, 4),
    POP("Pop 4/4", 8, 4),
}

/**
 * One part of a song: Pallavi, a Charanam, an interlude, or a single line.
 *
 * Imported songs use [start] (frame offset into the track; the section runs until the next
 * section starts). Generated songs use [chords]: one space-separated token per bar,
 * e.g. "C C F G". A token can split a bar in two with a comma: "F,G".
 */
@Serializable
data class Section(
    val name: String,
    val start: Long = 0,
    val chords: String = "",
    /** If true, this section keeps repeating until Next is pressed. */
    val hold: Boolean = true,
    val lyrics: String = "",
)

@Serializable
data class GenSettings(
    /** Key the chords are written in (0 = C .. 11 = B). Tabla and tanpura are tuned to it. */
    val key: Int = 0,
    /** Semitones to shift everything, so the song sits in your voice. */
    val transpose: Int = 0,
    val bpm: Int = 80,
    val rhythm: Rhythm = Rhythm.KEHERWA,
    val drumsVol: Float = 0.8f,
    val padVol: Float = 0.5f,
    val bassVol: Float = 0.6f,
    val tanpuraVol: Float = 0.35f,
    /** Use the loaded tabla/dholak sample pack when available (else synthesized drums). */
    val useSamples: Boolean = true,
)

@Serializable
data class Song(
    val id: String,
    val title: String,
    val kind: SongKind,
    val sections: List<Section>,
    val gen: GenSettings = GenSettings(),
    /** Imported songs: total decoded length in frames. */
    val durationFrames: Long = 0,
    /** Imported songs: tapped tempo used to snap markers to the beat (0 = not set). */
    val bpm: Double = 0.0,
    val createdAt: Long = 0,
)

@Serializable
data class MixSettings(
    val musicVol: Float = 0.8f,
    val voiceVol: Float = 1.0f,
    val reverb: Float = 0.25f,
    /** Manual sync correction added on top of the automatic one. Positive = voice was late. */
    val syncMs: Float = 0f,
)

@Serializable
data class Take(
    val id: String,
    val songId: String,
    val songTitle: String,
    val createdAt: Long,
    val frames: Long,
    /** Voice frames to skip so the voice lines up with the music (from audio timestamps). */
    val autoLeadFrames: Int,
    val autoSyncMeasured: Boolean,
    val mix: MixSettings = MixSettings(),
)

@Serializable
data class AppSettings(
    /** Measured extra voice delay per output route ("wired", "bluetooth", "speaker"), in ms. */
    val calibrationMs: Map<String, Float> = emptyMap(),
    /** Hear your own voice in the headphones while recording. */
    val monitor: Boolean = false,
    val monitorVol: Float = 0.8f,
    /** Key last chosen for ready-made music (0 = C). */
    val lastKey: Int = 1,
)

/** Common section names offered as one-tap choices. */
val QUICK_SECTION_NAMES = listOf(
    "Intro", "Pallavi", "Interlude", "Charanam 1", "Charanam 2", "Charanam 3",
    "Charanam 4", "Line", "Ending",
)

/** Sections that normally repeat until Next is pressed. */
fun defaultHoldFor(name: String): Boolean {
    val n = name.lowercase()
    return !(n.startsWith("intro") || n.startsWith("interlude") || n.startsWith("ending") ||
        n.startsWith("outro") || n.startsWith("music"))
}
