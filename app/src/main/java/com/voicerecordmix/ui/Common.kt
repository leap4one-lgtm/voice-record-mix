package com.voicerecordmix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicerecordmix.core.SAMPLE_RATE

val Gold = Color(0xFFF2C66D)
val RecordRed = Color(0xFFE5484D)

val AppColors: ColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF2A1A00),
    secondary = Color(0xFFCDB4F0),
    onSecondary = Color(0xFF2A1640),
    secondaryContainer = Color(0xFF4A3366),
    onSecondaryContainer = Color(0xFFF0E6FF),
    background = Color(0xFF1B1520),
    surface = Color(0xFF1B1520),
    surfaceVariant = Color(0xFF2C2433),
    surfaceContainer = Color(0xFF261F2C),
    surfaceContainerHigh = Color(0xFF30283A),
    onSurface = Color(0xFFEDE6F2),
    onSurfaceVariant = Color(0xFFC9BFD3),
    error = RecordRed,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = AppColors, content = content)

fun formatFrames(frames: Long, tenths: Boolean = false): String {
    val totalTenths = frames * 10 / SAMPLE_RATE
    val s = totalTenths / 10
    val base = "%d:%02d".format(s / 60, s % 60)
    return if (tenths) "$base.${totalTenths % 10}" else base
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = { actions() },
    )
}

@Composable
fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String,
    onChange: (Float) -> Unit, onDone: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, onValueChangeFinished = onDone)
        Text(display, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
    }
}
