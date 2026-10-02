package com.voicerecordmix.data

import android.content.Context
import com.voicerecordmix.core.AppSettings
import com.voicerecordmix.core.SamplePack
import com.voicerecordmix.core.Song
import com.voicerecordmix.core.Stroke
import com.voicerecordmix.core.Take
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plain-file storage:
 *   files/songs/<id>/song.json, audio.pcm (48 kHz stereo 16-bit), peaks.bin
 *   files/takes/<id>/take.json, music.pcm (stereo), voice.pcm (mono)
 */
class Repo(context: Context) {
    private val songsDir = File(context.filesDir, "songs").apply { mkdirs() }
    private val takesDir = File(context.filesDir, "takes").apply { mkdirs() }
    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val calibrationDir = File(context.cacheDir, "calibration")
    private val samplesDir = File(context.filesDir, "samples").apply { mkdirs() }
    private val settingsFile = File(context.filesDir, "settings.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun songDir(id: String) = File(songsDir, id).apply { mkdirs() }
    fun songAudio(id: String) = File(songDir(id), "audio.pcm")
    private fun peaksFile(id: String) = File(songDir(id), "peaks.bin")

    fun takeDir(id: String) = File(takesDir, id).apply { mkdirs() }
    fun takeMusic(id: String) = File(takeDir(id), "music.pcm")
    fun takeVoice(id: String) = File(takeDir(id), "voice.pcm")

    fun songs(): List<Song> = songsDir.listFiles().orEmpty().mapNotNull { dir ->
        runCatching { json.decodeFromString<Song>(File(dir, "song.json").readText()) }.getOrNull()
    }.sortedByDescending { it.createdAt }

    fun saveSong(song: Song) {
        val f = File(songDir(song.id), "song.json")
        val tmp = File(f.parentFile, "song.json.tmp")
        tmp.writeText(json.encodeToString(song))
        tmp.renameTo(f)
    }

    fun deleteSong(id: String) = File(songsDir, id).deleteRecursively()

    fun takes(): List<Take> = takesDir.listFiles().orEmpty().mapNotNull { dir ->
        runCatching { json.decodeFromString<Take>(File(dir, "take.json").readText()) }.getOrNull()
    }.sortedByDescending { it.createdAt }

    fun saveTake(take: Take) {
        val f = File(takeDir(take.id), "take.json")
        val tmp = File(f.parentFile, "take.json.tmp")
        tmp.writeText(json.encodeToString(take))
        tmp.renameTo(f)
    }

    fun deleteTake(id: String) = File(takesDir, id).deleteRecursively()

    fun settings(): AppSettings =
        runCatching { json.decodeFromString<AppSettings>(settingsFile.readText()) }.getOrDefault(AppSettings())

    fun saveSettings(s: AppSettings) = settingsFile.writeText(json.encodeToString(s))

    /** Saves one drum hit (interleaved stereo float) for [stroke], replacing any previous one. */
    fun saveSample(stroke: Stroke, data: FloatArray) {
        val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(data)
        File(samplesDir, "${stroke.name}.f32").writeBytes(bb.array())
    }

    fun loadSamplePack(): SamplePack = SamplePack(
        samplesDir.listFiles().orEmpty().mapNotNull { f ->
            val stroke = Stroke.entries.firstOrNull { "${it.name}.f32" == f.name } ?: return@mapNotNull null
            val fb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            stroke to FloatArray(fb.remaining()).also { fb.get(it) }
        }.toMap()
    )

    fun clearSamples() = samplesDir.listFiles().orEmpty().forEach { it.delete() }

    fun savePeaks(id: String, peaks: FloatArray) {
        DataOutputStream(peaksFile(id).outputStream().buffered()).use { out ->
            out.writeInt(peaks.size)
            for (p in peaks) out.writeFloat(p)
        }
    }

    fun loadPeaks(id: String): FloatArray = runCatching {
        DataInputStream(peaksFile(id).inputStream().buffered()).use { inp ->
            FloatArray(inp.readInt()) { inp.readFloat() }
        }
    }.getOrDefault(FloatArray(0))
}
