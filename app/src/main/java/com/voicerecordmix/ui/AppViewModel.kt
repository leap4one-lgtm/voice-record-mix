package com.voicerecordmix.ui

import android.app.Application
import android.content.Intent
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
import com.voicerecordmix.core.EngineState
import com.voicerecordmix.core.LoopEngine
import com.voicerecordmix.core.MixSettings
import com.voicerecordmix.core.Mixer
import com.voicerecordmix.core.MusicSource
import com.voicerecordmix.core.SAMPLE_RATE
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
enum class PlayMode { NONE, PREVIEW, PRACTICE, RECORD }

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
        SongKind.GENERATED -> SynthSource(song)
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
        return startRunner(e, dir, if (record) PlayMode.RECORD else PlayMode.PRACTICE)
    }

    private fun startRunner(e: LoopEngine, dir: File?, mode: PlayMode): String? {
        val r = AudioRunner(e, dir)
        r.musicGain = musicVolume
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
            val saved = take.copy(
                frames = result.frames, autoLeadFrames = result.leadFrames, autoSyncMeasured = result.measured,
                mix = MixSettings(voiceVol = voiceVol),
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
