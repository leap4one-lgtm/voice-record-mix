package com.voicerecordmix.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicerecordmix.core.Chords
import com.voicerecordmix.core.Preset

/** Pick a style, a key and a speed, and sing. No setup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadyScreen(vm: AppViewModel) {
    var preset by remember { mutableStateOf(Preset.SLOW_WORSHIP) }
    var key by remember { mutableIntStateOf(vm.settings.lastKey) }
    var bpm by remember { mutableIntStateOf(preset.bpm) }
    var changes by remember { mutableStateOf(false) }
    val listening = vm.playMode == PlayMode.PREVIEW

    fun listen() {
        val song = vm.readySong(preset, key, bpm, changes)
        vm.preview(song, 1, 0, hold = true)
    }
    // While listening, any change is heard right away.
    LaunchedEffect(preset, key, bpm, changes) {
        if (vm.playMode == PlayMode.PREVIEW) listen()
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("Sing with ready music", { vm.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("1. Choose a style", style = MaterialTheme.typography.titleMedium, color = Gold)
            Preset.entries.forEach { p ->
                val selected = p == preset
                Card(
                    Modifier.fillMaxWidth().clickable { preset = p; bpm = p.bpm },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    border = if (selected) BorderStroke(2.dp, Gold) else null,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(p.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(p.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text("2. Your key (Sa)", style = MaterialTheme.typography.titleMedium, color = Gold)
            Text(
                "Tap Listen and hum along; pick the key where your voice feels easy. " +
                    "Not sure? Men often sing in C# or D, women in G or A.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until 12).forEach { k ->
                    FilterChip(k == key, { key = k }, { Text(Chords.noteName(k), fontSize = 16.sp) })
                }
            }

            Text("3. Speed", style = MaterialTheme.typography.titleMedium, color = Gold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ bpm = (bpm - 4).coerceAtLeast(40) }) { Icon(Icons.Default.Remove, "Slower") }
                Text("$bpm BPM", Modifier.width(90.dp), style = MaterialTheme.typography.titleMedium)
                IconButton({ bpm = (bpm + 4).coerceAtMost(200) }) { Icon(Icons.Default.Add, "Faster") }
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        bpm < preset.bpm - 6 -> "slower"
                        bpm > preset.bpm + 6 -> "faster"
                        else -> "normal"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chord changes", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (changes) "Simple Pallavi / Charanam pattern (I–IV–V). Use it when it suits your song."
                        else "Off: one steady chord that fits any song.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(changes, { changes = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    { if (listening) vm.stopAudio() else listen() },
                    Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(if (listening) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Text(if (listening) "  Stop" else "  Listen")
                }
                Button(
                    {
                        val song = vm.readySong(preset, key, bpm, changes)
                        vm.navigate(Screen.Perform(song.id))
                    },
                    Modifier.weight(1.4f).height(56.dp),
                ) {
                    Icon(Icons.Default.Mic, null)
                    Text("  Start singing", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
