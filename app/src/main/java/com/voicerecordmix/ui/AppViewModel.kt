package com.voicerecordmix.ui

import android.app.Application
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicerecordmix.audio.AudioRunner
import com.voicerecordmix.audio.Decoder
import com.voicerecordmix.audio.Exporter
import com.voicerecordmix.audio.FilePcm
import com.voicerecordmix.audio.MixPlayer
import com.voicerecordmix.audio.RecordingService
import com.voicerecordmix.core.AppSettings
import com.voicerecordmix.core.Calibration
import com.voicerecordmix.core.EngineState
import com.voicerecordmix.core.LoopEngine
import com.voicerecordmix.core.MixSettings
import com.voicerecordmix.core.Mixer
import com.voicerecordmix.core.MusicSource
import com.voicerecordmix.core.SAMPLE_RATE
import com.voicerecordmix.core.SamplePack
import com.voicerecordmix.core.Section
import com.voicerecordmix.core.Song
import com.voicerecordmix.core.SongKind
import com.voicerecordmix.core.SynthSource
import com.voicerecordmix.core.Take
import com.voicerecordmix.core.TrackSource
import com.voicerecordmix.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data class Edit(val songId: String) : Screen
    data class Perform(val songId: String) : Screen
    data class Mix(val takeId: String) : Screen
}

/** What is currently making sound through an [AudioRunner]. */
enum class PlayMode { NONE, PREVIEW, PRACTICE, RECORD, CALIBRATE }

/** Output route names used for per-route sync calibration. */
object Route {
    const val WIRED = "wired"
    const val BLUETOOTH = "bluetooth"
    const val SPEAKER = "speaker"

    fun label(r: String) = when (r) {
        WIRED -> "Wired headphones"
        BLUETOOTH -> "Bluetooth headphones"
        else -> "Phone speaker"
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repo(app)

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var songs by mutableStateOf(repo.songs())
        private set
    var takes by mutableStateOf(repo.takes())
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    var message by mutableStateOf<String?>(null)
    var settings by mutableStateOf(repo.settings())
        private set
    var samplePack by mutableStateOf(repo.loadSamplePack())
        private set

    // ---- Live playback ----
    var playMode by mutableStateOf(PlayMode.NONE)
        private set
    var engineState by mutableStateOf(EngineState())
        private set
    /** Absolute playback frame in the track (imported-song editor preview). */
    var playFrame by mutableStateOf(0L)
        private set
    var micLevel by mutableStateOf(0f)
        private set
    var elapsedFrames by mutableStateOf(0L)
        private set
    var musicVolume by mutableStateOf(1f)
        private set

    private var engine: LoopEngine? = null
    private var runner: AudioRunner? = null
    private var pcm: FilePcm? = null
    private var poller: Job? = null
    private var recordingTake: Take? = null
    private var pcmSongId: String? = null
    /** Section starts used to turn engine positions into track positions for the waveform. */
    private var currentStarts: List<Long> = listOf(0L)
    private var recordingRoute = Route.SPEAKER

    fun song(id: String) = songs.firstOrNull { it.id == id }
    fun take(id: String) = takes.firstOrNull { it.id == id }

    fun navigate(to: Screen) {
        stopAudio()
        closeMix()
        screen = to
    }

    fun back() = navigate(Screen.Home)

    // ---- Songs ----

    fun updateSong(song: Song) {
        repo.saveSong(song)
        songs = songs.map { if (it.id == song.id) song else it }
    }

    fun deleteSong(id: String) {
        if (pcmSongId == id) {
            pcm?.close(); pcm = null; pcmSongId = null
        }
        repo.deleteSong(id)
        songs = repo.songs()
    }

    fun deleteTake(id: String) {
        if ((screen as? Screen.Mix)?.takeId == id) navigate(Screen.Home)
        repo.deleteTake(id)
        takes = repo.takes()
    }

    fun peaks(id: String) = repo.loadPeaks(id)

    fun importTrack(uri: Uri) {
        val ctx = getApplication<Application>()
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.substringBeforeLast('.') ?: "New song"
        val id = UUID.randomUUID().toString()
        busy = "Preparing \"$name\"…"
        progress = 0f
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Decoder.decode(ctx, uri, repo.songAudio(id)) { p -> progress = p }
                }
                repo.savePeaks(id, result.peaks)
                val song = Song(
                    id = id, title = name, kind = SongKind.IMPORTED,
                    sections = listOf(Section("Intro", start = 0, hold = false)),
                    durationFrames = result.frames, createdAt = System.currentTimeMillis(),
                )
                repo.saveSong(song)
                songs = repo.songs()
                screen = Screen.Edit(id)
            } catch (e: Exception) {
                repo.deleteSong(id)
                message = "Could not import this file: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun createGenerated() {
        val song = Song(
            id = UUID.randomUUID().toString(),
            title = "New song",
            kind = SongKind.GENERATED,
            sections = listOf(
                Section("Intro", chords = "C G", hold = false),
                Section("Pallavi", chords = "C C F C"),
                Section("Interlude", chords = "Am F", hold = false),
                Section("Charanam 1", chords = "F G C C"),
                Section("Ending", chords = "C", hold = false),
            ),
            createdAt = System.currentTimeMillis(),
        )
        repo.saveSong(song)
        songs = repo.songs()
        screen = Screen.Edit(song.id)
    }

    // ---- Playback ----

    private fun sourceFor(song: Song, wholeTrack: Boolean): MusicSource = when (song.kind) {
        SongKind.GENERATED -> SynthSource(song, samplePack)
        SongKind.IMPORTED -> {
            if (pcmSongId != song.id) {
                pcm?.close()
                pcm = FilePcm(repo.songAudio(song.id), 2)
                pcmSongId = song.id
            }
            TrackSource(pcm!!, currentStarts)
        }
    }

    /**
     * Editor preview. For imported songs, [wholeTrack] plays the file straight through from
     * [frame]; otherwise playback follows the sections (used for "test loop").
     */
    fun preview(song: Song, section: Int, pos: Long, hold: Boolean? = null, wholeTrack: Boolean = false) {
        stopAudio()
        currentStarts = if (wholeTrack || song.kind == SongKind.GENERATED) listOf(0L) else song.sections.map { it.start }
        val src = sourceFor(song, wholeTrack)
        val holds = if (wholeTrack) booleanArrayOf(false) else song.sections.map { it.hold }.toBooleanArray()
        // Previews play straight through; only an explicit [hold] loops a section.
        val e = LoopEngine(src, holds, autoHold = false)
        e.start(section, pos)
        hold?.let { e.setHold(it) }
        startRunner(e, null, PlayMode.PREVIEW)
    }

    /** Hard jump inside the running preview (whole-track mode: section 0). */
    fun seekPreview(section: Int, pos: Long) = engine?.seek(section, pos)

    fun startPerform(song: Song, fromSection: Int, record: Boolean): String? {
        stopAudio()
        currentStarts = song.sections.map { it.start }
        val e = LoopEngine(sourceFor(song, false), song.sections.map { it.hold }.toBooleanArray())
        e.start(fromSection, 0)
        var dir: File? = null
        if (record) {
            val take = Take(
                id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(4),
                songId = song.id, songTitle = song.title, createdAt = System.currentTimeMillis(),
                frames = 0, autoLeadFrames = 0, autoSyncMeasured = false,
            )
            recordingTake = take
            dir = repo.takeDir(take.id)
        }
        recordingRoute = currentRoute()
        return startRunner(e, dir, if (record) PlayMode.RECORD else PlayMode.PRACTICE)
    }

    private fun startRunner(e: LoopEngine, dir: File?, mode: PlayMode): String? {
        val r = AudioRunner(e, dir, monitor = mode == PlayMode.RECORD && settings.monitor)
        r.musicGain = musicVolume
        r.monitorGain = settings.monitorVol
        r.onEnded = { viewModelScope.launch { if (runner === r) stopAudio() } }
        if (mode == PlayMode.RECORD) RecordingService.start(getApplication())
        val err = r.start()
        if (err != null) {
            RecordingService.stop(getApplication())
            dir?.deleteRecursively()
            recordingTake = null
            return err
        }
        engine = e
        runner = r
        playMode = mode
        elapsedFrames = 0
        poller = viewModelScope.launch {
            while (isActive) {
                engineState = e.state
                playFrame = (currentStarts.getOrNull(e.state.section) ?: 0L) + e.state.pos
                micLevel = r.micLevel
                elapsedFrames = r.musicFrames
                delay(50)
            }
        }
        return null
    }

    fun changeMusicVolume(v: Float) {
        musicVolume = v
        runner?.musicGain = v
    }

    fun updateSettings(s: AppSettings) {
        settings = s
        repo.saveSettings(s)
        runner?.monitorGain = s.monitorVol
    }

    /** Where audio is going right now: Bluetooth beats wired beats the speaker. */
    fun currentRoute(): String {
        val am = getApplication<Application>().getSystemService(AudioManager::class.java)
        val types = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        val bt = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* TYPE_BLE_HEADSET */, 27 /* TYPE_BLE_SPEAKER */)
        val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
        return when {
            types.any { it in bt } -> Route.BLUETOOTH
            types.any { it in wired } -> Route.WIRED
            else -> Route.SPEAKER
        }
    }

    /**
     * Plays clicks while the user holds an earbud (or the phone speaker) at the microphone,
     * then stores the measured remaining delay for the current output route.
     */
    fun calibrate() {
        if (playMode != PlayMode.NONE) return
        val route = currentRoute()
        val dir = repo.calibrationDir.apply { deleteRecursively(); mkdirs() }
        val e = LoopEngine(Calibration.ClickSource(), booleanArrayOf(false))
        val r = AudioRunner(e, dir)
        r.musicGain = 1f
        r.start()?.let { message = it; return }
        playMode = PlayMode.CALIBRATE
        viewModelScope.launch {
            delay(Calibration.totalFrames * 1000 / SAMPLE_RATE + 300)
            val result = withContext(Dispatchers.IO) { r.stop() }
            val ms = withContext(Dispatchers.IO) {
                FilePcm(File(dir, "voice.pcm"), 1).use { Calibration.measure(it, result.leadFrames) }
            }
            playMode = PlayMode.NONE
            if (ms == null) {
                message = "Couldn't hear the clicks clearly. Try again in a quiet room, holding the " +
                    "earbud right against the phone's microphone (usually at the bottom edge)."
            } else {
                updateSettings(settings.copy(calibrationMs = settings.calibrationMs + (route to ms)))
                message = "${Route.label(route)}: voice sync set to %+d ms. New recordings use it automatically."
                    .format(ms.toInt())
            }
        }
    }

    /**
     * Loads drum hits from audio files. Each file name says which sound it is
     * (e.g. "dha.wav", "Dholak Ge 2.wav", "kick.wav").
     */
    fun importSamples(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        busy = "Loading sounds…"
        progress = 0f
        viewModelScope.launch {
            val loaded = ArrayList<String>()
            val skipped = ArrayList<String>()
            withContext(Dispatchers.IO) {
                uris.forEachIndexed { idx, uri ->
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "?"
                    val stroke = SamplePack.strokeForFileName(name)
                    if (stroke == null) { skipped.add(name); return@forEachIndexed }
                    val tmp = File(ctx.cacheDir, "sample.pcm")
                    try {
                        Decoder.decode(ctx, uri, tmp, minFrames = 100) {}
                        repo.saveSample(stroke, prepareHit(tmp))
                        loaded.add(stroke.name.lowercase())
                    } catch (e: Exception) {
                        skipped.add(name)
                    } finally {
                        tmp.delete()
                    }
                    progress = (idx + 1f) / uris.size
                }
            }
            samplePack = repo.loadSamplePack()
            busy = null
            message = buildString {
                append(if (loaded.isEmpty()) "No sounds loaded." else "Loaded: ${loaded.distinct().joinToString(", ")}.")
                if (skipped.isNotEmpty()) {
                    append("\n\nSkipped (name not recognised or unreadable): ${skipped.joinToString(", ")}.")
                    append("\nName files like: ${SamplePack.EXPECTED}.")
                }
            }
        }
    }

    /** Reads a decoded hit, trims leading silence, caps it at 3 s and normalises its peak. */
    private fun prepareHit(file: File): FloatArray = FilePcm(file, 2).use { p ->
        val frames = p.frames.toInt()
        val raw = ShortArray(frames * 2)
        p.read(0, raw, frames)
        var peak = 1
        for (x in raw) peak = maxOf(peak, kotlin.math.abs(x.toInt()))
        val start = raw.indexOfFirst { kotlin.math.abs(it.toInt()) > peak / 20 }.coerceAtLeast(0) / 2
        val len = minOf(frames - start, SAMPLE_RATE * 3)
        val g = 0.9f / peak
        FloatArray(len * 2) { i ->
            val fade = if (i / 2 > len - 480) (len - i / 2) / 480f else 1f // 10 ms fade at the end
            raw[start * 2 + i] * g * fade
        }
    }

    fun clearSamples() {
        repo.clearSamples()
        samplePack = repo.loadSamplePack()
    }

    fun next() = engine?.next()
    fun repeat(times: Int) = engine?.repeat(times)
    fun toggleHold() = engine?.toggleHold()
    fun queue(section: Int) = engine?.queue(section)
    fun finish() = engine?.finish()

    /** Stops whatever is playing. A recording becomes a saved take and opens the mixer. */
    fun stopAudio() {
        val r = runner ?: return
        poller?.cancel()
        runner = null
        engine = null
        val mode = playMode
        playMode = PlayMode.NONE
        val result = r.stop()
        if (mode == PlayMode.RECORD) RecordingService.stop(getApplication())
        engineState = EngineState()
        micLevel = 0f
        val take = recordingTake
        recordingTake = null
        if (mode == PlayMode.RECORD && take != null) {
            if (result.frames < SAMPLE_RATE) {
                repo.deleteTake(take.id)
                return
            }
            // Start with the voice at a healthy level: aim its loudest moment at about -3 dB.
            val voiceVol = if (result.voicePeak > 0.01f) (0.7f / result.voicePeak).coerceIn(0.5f, 4f) else 1f
            val calibrated = settings.calibrationMs[recordingRoute]
            val saved = take.copy(
                frames = result.frames, autoLeadFrames = result.leadFrames,
                autoSyncMeasured = result.measured || calibrated != null,
                mix = MixSettings(voiceVol = voiceVol, syncMs = calibrated ?: 0f),
            )
            repo.saveTake(saved)
            takes = repo.takes()
            screen = Screen.Mix(saved.id)
        }
    }

    // ---- Mixing ----

    var mixPosition by mutableStateOf(0L)
        private set
    var mixPlaying by mutableStateOf(false)
        private set
    var exported by mutableStateOf<File?>(null)
        private set
    var savedToMusic by mutableStateOf(false)
        private set

    private var mixer: Mixer? = null
    private var mixPlayer: MixPlayer? = null
    private var mixFiles: List<FilePcm> = emptyList()
    private var mixPoller: Job? = null
    private var mixTakeId: String? = null

    fun openMix(take: Take): Mixer {
        if (mixTakeId == take.id) mixer?.let { return it }
        closeMix()
        val music = FilePcm(repo.takeMusic(take.id), 2)
        val voice = FilePcm(repo.takeVoice(take.id), 1)
        mixFiles = listOf(music, voice)
        val m = Mixer(music, voice, take.autoLeadFrames).also { it.settings = take.mix }
        mixer = m
        mixTakeId = take.id
        mixPosition = 0
        exported = null
        return m
    }

    fun updateMix(take: Take, settings: MixSettings, persist: Boolean) {
        mixer?.settings = settings
        val t = take.copy(mix = settings)
        takes = takes.map { if (it.id == t.id) t else it }
        if (persist) repo.saveTake(t)
        exported = null
    }

    fun toggleMixPlay() {
        val m = mixer ?: return
        val p = mixPlayer
        if (p != null && p.playing) {
            p.stop()
            mixPlaying = false
            return
        }
        val player = MixPlayer(m).also { mixPlayer = it }
        player.onEnded = { viewModelScope.launch { mixPlaying = false; mixPosition = 0 } }
        player.play(if (mixPosition >= m.totalFrames - SAMPLE_RATE) 0 else mixPosition)
        mixPlaying = true
        mixPoller?.cancel()
        mixPoller = viewModelScope.launch {
            while (isActive && player.playing) {
                mixPosition = player.position
                delay(100)
            }
        }
    }

    fun seekMix(frame: Long) {
        mixPosition = frame
        if (mixPlayer?.playing == true) {
            mixPlayer?.play(frame)
        }
    }

    private fun closeMix() {
        mixPoller?.cancel()
        mixPlayer?.stop()
        mixPlayer = null
        mixPlaying = false
        mixFiles.forEach { it.close() }
        mixFiles = emptyList()
        mixer = null
        mixTakeId = null
    }

    fun export(take: Take, asWav: Boolean) {
        val m = mixer ?: return
        mixPlayer?.stop()
        mixPlaying = false
        val ctx = getApplication<Application>()
        val safe = take.songTitle.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Song" }
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(take.createdAt))
        val file = File(repo.exportsDir, "$safe $stamp.${if (asWav) "wav" else "m4a"}")
        busy = if (asWav) "Saving WAV…" else "Saving M4A…"
        progress = 0f
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (asWav) Exporter.wav(m, file) { progress = it } else Exporter.m4a(m, file) { progress = it }
                    savedToMusic = Exporter.saveToMusic(ctx, file, if (asWav) "audio/wav" else "audio/mp4") != null
                }
                exported = file
                message = if (savedToMusic) "Saved to Music › Worship Recorder" else "Ready to share"
            } catch (e: Exception) {
                message = "Export failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun shareIntent(file: File): Intent {
        val ctx = getApplication<Application>()
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "wav") "audio/wav" else "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Share recording") }
    }

    override fun onCleared() {
        stopAudio()
        closeMix()
        pcm?.close()
    }
}
