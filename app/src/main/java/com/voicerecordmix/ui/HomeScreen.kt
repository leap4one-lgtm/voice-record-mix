package com.voicerecordmix.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicerecordmix.core.SongKind
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTrack(uri)
    }
    var confirmDelete by remember { mutableStateOf<(() -> Unit)?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Text("Worship Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Sing as long as you are led: repeat any line or charanam while you record.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ picker.launch(arrayOf("audio/*")) }, Modifier.weight(1f)) {
                    Icon(Icons.Default.LibraryMusic, null)
                    Text("  Import track")
                }
                FilledTonalButton({ vm.createGenerated() }, Modifier.weight(1f)) {
                    Icon(Icons.Default.GraphicEq, null)
                    Text("  Rhythm & pads")
                }
            }
        }
        item { SectionTitle("Songs") }
        if (vm.songs.isEmpty()) item {
            Text(
                "Import a karaoke or instrumental track, or build a tabla + keyboard accompaniment " +
                    "from chords. Then mark the Pallavi and Charanams once, and you're ready to sing.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(vm.songs, key = { it.id }) { song ->
            Card(Modifier.fillMaxWidth().clickable { vm.navigate(Screen.Perform(song.id)) }) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (song.kind == SongKind.IMPORTED) Icons.Default.MusicNote else Icons.Default.GraphicEq, null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (song.kind == SongKind.IMPORTED) "Track" else "Rhythm & pads") +
                                " · ${song.sections.size} sections",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton({ vm.navigate(Screen.Edit(song.id)) }) { Icon(Icons.Default.Edit, "Edit") }
                    IconButton({ vm.navigate(Screen.Perform(song.id)) }) { Icon(Icons.Default.Mic, "Sing", tint = Gold) }
                    IconButton({ confirmDelete = { vm.deleteSong(song.id) } }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
        item { SectionTitle("Recordings") }
        if (vm.takes.isEmpty()) item {
            Text("Your recordings will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(vm.takes, key = { it.id }) { take ->
            Card(Modifier.fillMaxWidth().clickable { vm.navigate(Screen.Mix(take.id)) }) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(take.songTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(take.createdAt)) +
                                " · " + formatFrames(take.frames),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton({ confirmDelete = { vm.deleteTake(take.id) } }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
        item { Text("", Modifier.padding(bottom = 24.dp)) }
    }

    confirmDelete?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete?") },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton({ action(); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = Gold)
}
