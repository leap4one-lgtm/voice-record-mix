package com.voicerecordmix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicerecordmix.core.Chords
import com.voicerecordmix.core.Markers
import com.voicerecordmix.core.QUICK_SECTION_NAMES
import com.voicerecordmix.core.Rhythm
import com.voicerecordmix.core.SAMPLE_RATE
import com.voicerecordmix.core.Section
import com.voicerecordmix.core.Song
import com.voicerecordmix.core.SongKind
import com.voicerecordmix.core.defaultHoldFor
import kotlin.math.abs
import kotlin.math.roundToInt

private val SUGGESTED_ORDER = listOf(
    "Intro", "Pallavi", "Interlude", "Charanam 1", "Interlude", "Charanam 2",
    "Interlude", "Charanam 3", "Interlude", "Charanam 4", "Ending",
)

@Composable
fun EditScreen(vm: AppViewModel, songId: String) {
    val song = vm.song(songId) ?: return
    Column(Modifier.fillMaxSize()) {
        BackBar("Edit song", { vm.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                song.title, { vm.updateSong(song.copy(title = it)) },
                Modifier.fillMaxWidth(), label = { Text("Song title") }, singleLine = true,
            )
            if (song.kind == SongKind.IMPORTED) ImportedEditor(vm, song) else GeneratedEditor(vm, song)
            Button({ vm.navigate(Screen.Perform(song.id)) }, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Mic, null)
                Text("  Sing this song")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------- Imported track ----------------

@Composable
private fun ImportedEditor(vm: AppViewModel, song: Song) {
    val peaks = remember(song.id) { vm.peaks(song.id) }
    var cursor by remember { mutableLongStateOf(0L) }
    var wholeTrack by remember { mutableStateOf(true) }
    val taps = remember { mutableStateListOf<Long>() }
    val playing = vm.playMode == PlayMode.PREVIEW
    val head = if (playing) vm.playFrame else cursor
    val starts = song.sections.map { it.start }

    fun setSections(list: List<Section>) = vm.updateSong(song.copy(sections = list.sortedBy { it.start }))

    fun seek(frame: Long) {
        val f = frame.coerceIn(0, song.durationFrames - 1)
        cursor = f
        if (playing) {
            if (wholeTrack) vm.seekPreview(0, f) else { wholeTrack = true; vm.preview(song, 0, f, wholeTrack = true) }
        }
    }

    Text(
        "Play the track and tap \"Mark section\" where each part begins: Pallavi, each Charanam, " +
            "interludes, or even single lines you like to repeat.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Waveform(peaks, song.durationFrames, starts, head, onTap = ::seek)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${formatFrames(head, true)} / ${formatFrames(song.durationFrames)}", Modifier.weight(1f))
        IconButton({ seek(head - 5 * SAMPLE_RATE) }) { Icon(Icons.Default.Replay5, "Back 5 s") }
        FilledTonalButton({
            if (playing) { cursor = vm.playFrame; vm.stopAudio() }
            else { wholeTrack = true; vm.preview(song, 0, cursor, wholeTrack = true) }
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            Text(if (playing) " Pause" else " Play")
        }
        IconButton({ seek(head + 5 * SAMPLE_RATE) }) { Icon(Icons.Default.Forward5, "Forward 5 s") }
    }
    Button(
        onClick = {
            if (starts.any { abs(it - head) < SAMPLE_RATE / 4 }) return@Button
            val idx = starts.count { it < head }
            val name = if (idx >= starts.size) SUGGESTED_ORDER.getOrElse(idx) { "Line" } else "Line"
            setSections(song.sections + Section(name, start = head, hold = defaultHoldFor(name)))
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Icon(Icons.Default.Flag, null)
        Text("  Mark section here", style = MaterialTheme.typography.titleMedium)
    }

    // Tap tempo + beat snapping
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Beat snap (optional)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Tap along to the beat while the track plays. Then snap the markers so every repeat " +
                    "starts exactly on the beat. Your first marked section after the intro is the reference.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    val now = System.currentTimeMillis()
                    if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                    taps.add(now)
                    val bpm = Markers.bpmFromTaps(taps)
                    if (bpm > 0) vm.updateSong(song.copy(bpm = bpm))
                }) { Text("Tap beat") }
                Text(if (song.bpm > 0) "${song.bpm.roundToInt()} BPM" else "—", Modifier.weight(1f))
                TextButton(
                    enabled = song.bpm > 0 && starts.size > 1,
                    onClick = {
                        val anchor = starts[1]
                        setSections(song.sections.map { it.copy(start = Markers.snap(it.start, anchor, song.bpm)) }
                            .distinctBy { it.start })
                    },
                ) { Text("Snap markers") }
            }
        }
    }

    Text("Sections", style = MaterialTheme.typography.titleMedium, color = Gold)
    song.sections.forEachIndexed { i, sec ->
        SectionCard(
            index = i, section = sec,
            onChange = { ns -> setSections(song.sections.toMutableList().also { it[i] = ns }) },
            onDelete = if (i == 0) null else { { setSections(song.sections.filterIndexed { j, _ -> j != i }) } },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Starts " + formatFrames(sec.start, true), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (i > 0) {
                    val step = SAMPLE_RATE / 50 // 20 ms
                    IconButton({
                        val lo = starts[i - 1] + SAMPLE_RATE / 4
                        setSections(song.sections.toMutableList().also { it[i] = sec.copy(start = maxOf(lo, sec.start - step)) })
                    }) { Icon(Icons.Default.Remove, "Earlier") }
                    IconButton({
                        val hi = (starts.getOrNull(i + 1) ?: song.durationFrames) - SAMPLE_RATE / 4
                        setSections(song.sections.toMutableList().also { it[i] = sec.copy(start = minOf(hi, sec.start + step)) })
                    }) { Icon(Icons.Default.Add, "Later") }
                }
                IconButton({ wholeTrack = true; cursor = sec.start; vm.preview(song, 0, sec.start, wholeTrack = true) }) {
                    Icon(Icons.Default.PlayArrow, "Play from here")
                }
                IconButton({
                    // Play the last 3 s of this section looping back to its start, to check the seam.
                    wholeTrack = false
                    val len = (starts.getOrNull(i + 1) ?: song.durationFrames) - sec.start
                    vm.preview(song, i, maxOf(0, len - 3 * SAMPLE_RATE), hold = true)
                }) { Icon(Icons.Default.Repeat, "Test the loop") }
            }
        }
    }
}

@Composable
private fun Waveform(peaks: FloatArray, total: Long, starts: List<Long>, head: Long, onTap: (Long) -> Unit) {
    val measurer = rememberTextMeasurer()
    val bandA = MaterialTheme.colorScheme.surfaceVariant
    val bandB = MaterialTheme.colorScheme.surfaceContainerHigh
    val wave = MaterialTheme.colorScheme.secondary
    Canvas(
        Modifier.fillMaxWidth().height(110.dp).pointerInput(total) {
            detectTapGestures { o -> onTap((o.x / size.width * total).toLong()) }
        }
    ) {
        val w = size.width
        val h = size.height
        fun x(f: Long) = f.toFloat() / total * w
        starts.forEachIndexed { i, s ->
            val e = starts.getOrNull(i + 1) ?: total
            drawRect(if (i % 2 == 0) bandA else bandB, Offset(x(s), 0f), Size(x(e) - x(s), h))
        }
        if (peaks.isNotEmpty()) {
            val cols = w.toInt().coerceAtLeast(1)
            for (c in 0 until cols) {
                val a = (c.toLong() * peaks.size / cols).toInt()
                val b = maxOf(a + 1, ((c + 1).toLong() * peaks.size / cols).toInt()).coerceAtMost(peaks.size)
                var p = 0f
                for (k in a until b) p = maxOf(p, peaks[k])
                val hh = p * h * 0.45f
                drawLine(wave, Offset(c.toFloat(), h / 2 - hh), Offset(c.toFloat(), h / 2 + hh))
            }
        }
        starts.forEachIndexed { i, s ->
            drawLine(Gold, Offset(x(s), 0f), Offset(x(s), h), strokeWidth = 3f)
            drawText(measurer, "${i + 1}", Offset(x(s) + 4f, 2f), TextStyle(color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
        drawLine(Color.White, Offset(x(head), 0f), Offset(x(head), h), strokeWidth = 2f)
    }
}

// ---------------- Generated accompaniment ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneratedEditor(vm: AppViewModel, song: Song) {
    val g = song.gen
    val playing = vm.playMode == PlayMode.PREVIEW
    val taps = remember { mutableStateListOf<Long>() }
    fun update(s: Song) = vm.updateSong(s)
    fun setSections(list: List<Section>) = update(song.copy(sections = list))

    // Restart the preview when the music itself changes (not lyrics or names).
    val musicKey = g to song.sections.map { it.chords }
    LaunchedEffect(musicKey) {
        if (vm.playMode == PlayMode.PREVIEW) {
            kotlinx.coroutines.delay(400)
            vm.preview(song, vm.engineState.section.coerceAtMost(song.sections.size - 1), 0)
        }
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sound", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chords are in", Modifier.weight(1f))
                NoteDropdown(g.key) { update(song.copy(gen = g.copy(key = it))) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transpose", Modifier.weight(1f))
                IconButton({ update(song.copy(gen = g.copy(transpose = (g.transpose - 1).coerceAtLeast(-11)))) }) {
                    Icon(Icons.Default.Remove, "Lower")
                }
                Text("%+d → %s".format(g.transpose, Chords.noteName(g.key + g.transpose)), Modifier.width(72.dp))
                IconButton({ update(song.copy(gen = g.copy(transpose = (g.transpose + 1).coerceAtMost(11)))) }) {
                    Icon(Icons.Default.Add, "Higher")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tempo", Modifier.weight(1f))
                IconButton({ update(song.copy(gen = g.copy(bpm = (g.bpm - 2).coerceAtLeast(40)))) }) { Icon(Icons.Default.Remove, "Slower") }
                Text("${g.bpm} BPM", Modifier.width(72.dp))
                IconButton({ update(song.copy(gen = g.copy(bpm = (g.bpm + 2).coerceAtMost(200)))) }) { Icon(Icons.Default.Add, "Faster") }
                OutlinedButton({
                    val now = System.currentTimeMillis()
                    if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                    taps.add(now)
                    val bpm = Markers.bpmFromTaps(taps)
                    if (bpm > 0) update(song.copy(gen = g.copy(bpm = bpm.roundToInt().coerceIn(40, 200))))
                }) { Text("Tap") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Rhythm.entries.forEach { r ->
                    FilterChip(g.rhythm == r, { update(song.copy(gen = g.copy(rhythm = r))) }, { Text(r.label) })
                }
            }
            LabeledSlider("Tabla", g.drumsVol, 0f..1f, "${(g.drumsVol * 100).roundToInt()}%",
                { update(song.copy(gen = g.copy(drumsVol = it))) })
            LabeledSlider("Keys pad", g.padVol, 0f..1f, "${(g.padVol * 100).roundToInt()}%",
                { update(song.copy(gen = g.copy(padVol = it))) })
            LabeledSlider("Bass", g.bassVol, 0f..1f, "${(g.bassVol * 100).roundToInt()}%",
                { update(song.copy(gen = g.copy(bassVol = it))) })
            LabeledSlider("Tanpura", g.tanpuraVol, 0f..1f, "${(g.tanpuraVol * 100).roundToInt()}%",
                { update(song.copy(gen = g.copy(tanpuraVol = it))) })
            FilledTonalButton(
                { if (playing) vm.stopAudio() else vm.preview(song, 0, 0) },
                Modifier.fillMaxWidth(),
            ) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                Text(
                    if (playing) "  Stop  ·  ${song.sections.getOrNull(vm.engineState.section)?.name ?: ""}"
                    else "  Preview"
                )
            }
        }
    }

    Text("Sections", style = MaterialTheme.typography.titleMedium, color = Gold)
    Text(
        "Type one chord per bar, e.g. \"C C F G\". Split a bar with a comma: \"F,G\". " +
            "Use \"-\" to hold the last chord and \"N\" for drums only.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    song.sections.forEachIndexed { i, sec ->
        SectionCard(
            index = i, section = sec,
            onChange = { ns -> setSections(song.sections.toMutableList().also { it[i] = ns }) },
            onDelete = if (song.sections.size <= 1) null else { { setSections(song.sections.filterIndexed { j, _ -> j != i }) } },
        ) {
            OutlinedTextField(
                sec.chords,
                { setSections(song.sections.toMutableList().also { l -> l[i] = sec.copy(chords = it) }) },
                Modifier.fillMaxWidth(), label = { Text("Chords (${Chords.parseBars(sec.chords).size} bars)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton({ if (i > 0) setSections(song.sections.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }) {
                    Icon(Icons.Default.KeyboardArrowUp, "Move up")
                }
                IconButton({ if (i < song.sections.size - 1) setSections(song.sections.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }) {
                    Icon(Icons.Default.KeyboardArrowDown, "Move down")
                }
                IconButton({ vm.preview(song, i, 0, hold = true) }) { Icon(Icons.Default.Repeat, "Loop this section") }
            }
        }
    }
    OutlinedButton({
        val name = "Charanam ${song.sections.count { it.name.startsWith("Charanam") } + 1}"
        setSections(song.sections + Section(name, chords = song.sections.lastOrNull()?.chords ?: "C", hold = true))
    }, Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, null)
        Text("  Add section")
    }
}

@Composable
private fun NoteDropdown(value: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) {
            Text(Chords.noteName(value))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            (0 until 12).forEach { n ->
                DropdownMenuItem({ Text(Chords.noteName(n)) }, { onPick(n); open = false })
            }
        }
    }
}

// ---------------- Shared ----------------

@Composable
private fun SectionCard(
    index: Int,
    section: Section,
    onChange: (Section) -> Unit,
    onDelete: (() -> Unit)?,
    extra: @Composable () -> Unit,
) {
    var showLyrics by remember { mutableStateOf(section.lyrics.isNotEmpty()) }
    var namesOpen by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", Modifier.width(24.dp), color = Gold, fontWeight = FontWeight.Bold)
                OutlinedTextField(section.name, { onChange(section.copy(name = it)) }, Modifier.weight(1f), singleLine = true)
                Box {
                    IconButton({ namesOpen = true }) { Icon(Icons.Default.ArrowDropDown, "Choose a name") }
                    DropdownMenu(namesOpen, { namesOpen = false }) {
                        QUICK_SECTION_NAMES.forEach { n ->
                            DropdownMenuItem({ Text(n) }, {
                                onChange(section.copy(name = n, hold = defaultHoldFor(n)))
                                namesOpen = false
                            })
                        }
                    }
                }
                if (onDelete != null) IconButton(onDelete) { Icon(Icons.Default.Delete, "Delete section") }
            }
            extra()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(section.hold, { onChange(section.copy(hold = it)) })
                Text("  Repeat until Next", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton({ showLyrics = !showLyrics }) { Text(if (showLyrics) "Hide lyrics" else "Lyrics") }
            }
            if (showLyrics) {
                OutlinedTextField(
                    section.lyrics, { onChange(section.copy(lyrics = it)) },
                    Modifier.fillMaxWidth().padding(end = 8.dp), minLines = 2,
                    label = { Text("Lyrics (Telugu or English)") },
                )
            }
        }
    }
}
