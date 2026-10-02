package com.voicerecordmix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicerecordmix.ui.AppTheme
import com.voicerecordmix.ui.AppViewModel
import com.voicerecordmix.ui.EditScreen
import com.voicerecordmix.ui.HomeScreen
import com.voicerecordmix.ui.MixScreen
import com.voicerecordmix.ui.PerformScreen
import com.voicerecordmix.ui.ReadyScreen
import com.voicerecordmix.ui.Screen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                    // Registered first so screens (e.g. Perform while singing) can override it.
                    BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }
                    when (val s = vm.screen) {
                        Screen.Home -> HomeScreen(vm)
                        Screen.Ready -> ReadyScreen(vm)
                        is Screen.Edit -> EditScreen(vm, s.songId)
                        is Screen.Perform -> PerformScreen(vm, s.songId)
                        is Screen.Mix -> MixScreen(vm, s.takeId)
                    }

                    vm.busy?.let { text ->
                        AlertDialog(
                            onDismissRequest = {},
                            confirmButton = {},
                            title = { Text(text) },
                            text = {
                                Column {
                                    LinearProgressIndicator(
                                        progress = { vm.progress },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    )
                                }
                            },
                        )
                    }
                    vm.message?.let { text ->
                        AlertDialog(
                            onDismissRequest = { vm.message = null },
                            confirmButton = { TextButton({ vm.message = null }) { Text("OK") } },
                            text = { Text(text) },
                        )
                    }
                }
            }
        }
    }
}
