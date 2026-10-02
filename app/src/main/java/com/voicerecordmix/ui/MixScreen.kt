package com.voicerecordmix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicerecordmix.core.MixSettings
import com.voicerecordmix.core.SAMPLE_RATE
import kotlin.math.roundToInt

@Composable
fun MixScreen(vm: AppViewModel, takeId: String) {
    val take = vm.take(takeId) ?: return
    val ctx = LocalContext.current
    val mixer = remember(takeId) { vm.openMix(take) }
    var s by remember(takeId) { mutableStateOf(take.mix) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val total = mixer.totalFrames.coerceAtLeast(1)

    fun set(n: MixSettings, persist: Boolean = false) {
        s = n
        vm.updateMix(take, n, persist)
    }
    val save = { vm.updateMix(take, s, true) }

    Column(Modifier.fillMaxSize()) {
        BackBar(take.songTitle, { vm.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton({ vm.toggleMixPlay() }, Modifier.padding(end = 8.dp)) {
                    Icon(if (vm.mixPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play")
                }
                Slider(
                    value = dragging ?: (vm.mixPosition.toFloat() / total),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { vm.seekMix((it * total).toLong()) }
                        dragging = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "${formatFrames(((dragging ?: (vm.mixPosition.toFloat() / total)) * total).toLong())} / ${formatFrames(total)}",
                style = MaterialTheme.typography.bodySmall,
            )

            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Balance", style = MaterialTheme.typography.titleSmall, color = Gold)
                    LabeledSlider("Music", s.musicVol, 0f..1.5f, "${(s.musicVol * 100).roundToInt()}%",
                        { set(s.copy(musicVol = it)) }, save)
                    LabeledSlider("Voice", s.voiceVol, 0f..4f, "${(s.voiceVol * 100).roundToInt()}%",
                        { set(s.copy(voiceVol = it)) }, save)
                    LabeledSlider("Reverb", s.reverb, 0f..1f, "${(s.reverb * 100).roundToInt()}%",
                        { set(s.copy(reverb = it)) }, save)
                }
            }

            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Voice sync", style = MaterialTheme.typography.titleSmall, color = Gold)
                    Text(
                        if (take.autoSyncMeasured) "Lined up automatically. If your voice still sounds behind the music, slide right; ahead, slide left."
                        else "This phone didn't report its audio delay, so listen and adjust: voice behind the music → slide right.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LabeledSlider("Offset", s.syncMs, -400f..400f, "%+d ms".format(s.syncMs.roundToInt()),
                        { set(s.copy(syncMs = (it / 5).roundToInt() * 5f)) }, save)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ set(s.copy(syncMs = s.syncMs - 10), true) }) { Text("−10 ms") }
                        OutlinedButton({ set(s.copy(syncMs = 0f), true) }) { Text("Reset") }
                        OutlinedButton({ set(s.copy(syncMs = s.syncMs + 10), true) }) { Text("+10 ms") }
                    }
                }
            }

            Text("Save the mix", style = MaterialTheme.typography.titleSmall, color = Gold)
            Button({ save(); vm.export(take, asWav = false) }, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Save, null)
                Text("  Save M4A (small, for WhatsApp)")
            }
            FilledTonalButton({ save(); vm.export(take, asWav = true) }, Modifier.fillMaxWidth()) {
                Text("Save WAV (full quality, large)")
            }
            vm.exported?.let { file ->
                Button({ ctx.startActivity(vm.shareIntent(file)) }, Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Share, null)
                    Text("  Share ${file.extension.uppercase()}")
                }
                Text(
                    "${file.name} · ${"%.1f".format(file.length() / 1_048_576.0)} MB" +
                        if (vm.savedToMusic) " · also in Music › Worship Recorder" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Length ${formatFrames(take.frames)} · recorded at ${SAMPLE_RATE / 1000} kHz",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
