package com.voicerecordmix.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voicerecordmix.core.Song
import kotlin.math.roundToInt

private fun hasHeadphones(ctx: Context): Boolean {
    val am = ctx.getSystemService(AudioManager::class.java)
    val types = mutableSetOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
    )
    if (Build.VERSION.SDK_INT >= 31) types += AudioDeviceInfo.TYPE_BLE_HEADSET
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
}

@Composable
fun PerformScreen(vm: AppViewModel, songId: String) {
    val song = vm.song(songId) ?: return
    val ctx = LocalContext.current
    val view = LocalView.current
    var headphones by remember { mutableStateOf(hasHeadphones(ctx)) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val am = ctx.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) { headphones = hasHeadphones(ctx) }
            override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) { headphones = hasHeadphones(ctx) }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose {
            view.keepScreenOn = false
            am.unregisterAudioDeviceCallback(cb)
        }
    }
    var startFrom by remember { mutableIntStateOf(0) }
    val running = vm.playMode == PlayMode.PRACTICE || vm.playMode == PlayMode.RECORD

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startPerform(song, startFrom, record = true)?.let { vm.message = it }
        else vm.message = "Microphone permission is needed to record your voice."
    }
    fun record() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startPerform(song, startFrom, record = true)?.let { vm.message = it }
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // While singing, Back stops (and saves) instead of leaving the screen.
    BackHandler(enabled = running) { vm.stopAudio() }

    Column(Modifier.fillMaxSize()) {
        BackBar(song.title, { if (running) vm.stopAudio() else vm.back() })
        if (running) LivePanel(vm, song) else StartPanel(vm, song, headphones, startFrom, { startFrom = it }, ::record)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartPanel(
    vm: AppViewModel, song: Song, headphones: Boolean, startFrom: Int,
    onStartFrom: (Int) -> Unit, onRecord: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!headphones) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Headphones, null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Plug in headphones (wired is best). Otherwise the microphone records the " +
                            "music from the speaker too, and it will sound doubled.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Text("Start from", style = MaterialTheme.typography.titleSmall, color = Gold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            song.sections.forEachIndexed { i, s ->
                FilterChip(i == startFrom, { onStartFrom(i) }, { Text(s.name) })
            }
        }
        LabeledSlider("Music in ears", vm.musicVolume, 0f..1f, "${(vm.musicVolume * 100).roundToInt()}%", vm::changeMusicVolume)
        MonitorAndSyncCard(vm, headphones)
        Button(
            onRecord, Modifier.fillMaxWidth().height(68.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
        ) {
            Icon(Icons.Default.FiberManualRecord, null)
            Text("  Record", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            { vm.startPerform(song, startFrom, record = false)?.let { vm.message = it } },
            Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Practice (no recording)") }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("While you sing", style = MaterialTheme.typography.titleSmall)
                Text("• Repeat: keep looping the current part until you tap Next.", style = MaterialTheme.typography.bodySmall)
                Text("• ×2 / ×4: sing it that many more times, then move on by itself.", style = MaterialTheme.typography.bodySmall)
                Text("• Next: move on when this part finishes. Repeats always land on the part's start, so they stay in time.", style = MaterialTheme.typography.bodySmall)
                Text("• Tap any part's name to go there after the current pass.", style = MaterialTheme.typography.bodySmall)
                Text("• Fade out: end the music gently. Then tap Stop to save.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MonitorAndSyncCard(vm: AppViewModel, headphones: Boolean) {
    val s = vm.settings
    val ctx = LocalContext.current
    var showCalib by remember { mutableStateOf(false) }
    val route = remember(headphones) { vm.currentRoute() }
    val calibrated = s.calibrationMs[route]
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.calibrate() else vm.message = "Microphone permission is needed to calibrate."
    }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hear my voice", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your voice in the headphones while recording. There is a small delay; " +
                            "if it distracts you, turn it off or wear only one earbud.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(s.monitor, { vm.updateSettings(s.copy(monitor = it)) }, enabled = headphones || s.monitor)
            }
            if (s.monitor) LabeledSlider("Voice level", s.monitorVol, 0f..1.5f, "${(s.monitorVol * 100).roundToInt()}%",
                { vm.updateSettings(s.copy(monitorVol = it)) })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Sync calibration", style = MaterialTheme.typography.titleSmall)
                    Text(
                        Route.label(route) + ": " +
                            (calibrated?.let { "%+d ms".format(it.roundToInt()) } ?: "not calibrated (automatic sync only)"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton({ showCalib = true }, enabled = vm.playMode == PlayMode.NONE) {
                    Text(if (vm.playMode == PlayMode.CALIBRATE) "Listening…" else "Calibrate")
                }
            }
        }
    }
    if (showCalib) {
        AlertDialog(
            onDismissRequest = { showCalib = false },
            title = { Text("Calibrate sync") },
            text = {
                Text(
                    "Once per headphones, so your voice lines up perfectly with the music.\n\n" +
                        "1. Go somewhere quiet.\n" +
                        "2. Hold one earbud right against the phone's microphone (usually the bottom edge). " +
                        "Without headphones, just keep the phone on a table.\n" +
                        "3. Tap Start and stay silent for 5 seconds while it clicks."
                )
            },
            confirmButton = {
                TextButton({
                    showCalib = false
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        vm.calibrate()
                    } else permission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Start") }
            },
            dismissButton = { TextButton({ showCalib = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LivePanel(vm: AppViewModel, song: Song) {
    val st = vm.engineState
    val recording = vm.playMode == PlayMode.RECORD
    val cur = song.sections.getOrNull(st.section)
    fun name(i: Int) = song.sections.getOrNull(i)?.name ?: "End"
    val status = when {
        st.ended -> if (recording) "Music finished. Tap Stop to save." else "Music finished."
        st.finishing -> "Fading out…"
        st.jumpTarget >= 0 -> "Next: ${name(st.jumpTarget)}"
        st.nextRequested -> "Next: ${name(st.section + 1)}"
        st.repeatsLeft > 0 -> "${st.repeatsLeft} more time${if (st.repeatsLeft > 1) "s" else ""}, then ${name(st.section + 1)}"
        st.hold -> "Repeating until you tap Next"
        else -> "Then: ${name(st.section + 1)}"
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(RecordRed))
                Text("  REC ", color = RecordRed, fontWeight = FontWeight.Bold)
            } else Text("Practice  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatFrames(vm.elapsedFrames), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (recording) {
                Text("Mic ", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { vm.micLevel.coerceIn(0f, 1f) },
                    modifier = Modifier.size(width = 90.dp, height = 8.dp),
                    color = if (vm.micLevel > 0.95f) RecordRed else Gold,
                )
            }
        }
        Text(
            cur?.name ?: "", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
            color = Gold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Text(
            (if (st.pass > 1) "Pass ${st.pass} · " else "") + status,
            Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(
            progress = { (st.pos.toFloat() / st.sectionLength).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)
        ) {
            Text(
                cur?.lyrics?.ifBlank { null } ?: "",
                Modifier.verticalScroll(rememberScrollState()),
                fontSize = 24.sp, lineHeight = 34.sp,
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(song.sections) { i, s ->
                FilterChip(
                    selected = i == st.jumpTarget || i == st.section,
                    onClick = { vm.queue(i) },
                    label = { Text(s.name) },
                    colors = if (i == st.jumpTarget) FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
                    ) else FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val holdColors = if (st.hold) ButtonDefaults.buttonColors()
            else ButtonDefaults.filledTonalButtonColors()
            Button({ vm.toggleHold() }, Modifier.weight(1.4f).height(64.dp), colors = holdColors) {
                Icon(Icons.Default.Repeat, null)
                Text(" Repeat", fontSize = 18.sp)
            }
            FilledTonalButton({ vm.repeat(2) }, Modifier.weight(1f).height(64.dp)) { Text("×2", fontSize = 20.sp) }
            FilledTonalButton({ vm.repeat(4) }, Modifier.weight(1f).height(64.dp)) { Text("×4", fontSize = 20.sp) }
        }
        Button(
            { vm.next() }, Modifier.fillMaxWidth().height(76.dp),
            colors = if (st.nextRequested) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            else ButtonDefaults.buttonColors(),
        ) {
            Icon(Icons.Default.SkipNext, null, Modifier.size(32.dp))
            Text(if (st.nextRequested) " Next ✓" else " Next", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            OutlinedButton({ vm.finish() }, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Default.VolumeDown, null)
                Text(" Fade out")
            }
            Button(
                { vm.stopAudio() }, Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Icon(Icons.Default.Stop, null)
                Text(if (recording) " Stop & save" else " Stop")
            }
        }
    }
}
