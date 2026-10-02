package com.voicerecordmix.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.voicerecordmix.core.GenSettings
import com.voicerecordmix.core.MixSettings
import com.voicerecordmix.core.Rhythm
import com.voicerecordmix.core.SAMPLE_RATE
import com.voicerecordmix.core.Section
import com.voicerecordmix.core.Song
import com.voicerecordmix.core.SongKind
import com.voicerecordmix.core.Take
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * Renders each screen with sample data and saves PNGs to docs/screenshots. Also catches crashes
 * in composition (bad state, missing resources) without needing a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: AppViewModel
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private val imported = Song(
        id = "imported", title = "Yesayya Nee Prema", kind = SongKind.IMPORTED,
        sections = listOf(
            Section("Intro", 0, hold = false),
            Section("Pallavi", 9L * SAMPLE_RATE, lyrics = "యేసయ్యా నీ ప్రేమ\nఎంతో గొప్పది"),
            Section("Interlude", 41L * SAMPLE_RATE, hold = false),
            Section("Charanam 1", 55L * SAMPLE_RATE),
            Section("Line", 70L * SAMPLE_RATE),
            Section("Ending", 150L * SAMPLE_RATE, hold = false),
        ),
        durationFrames = 170L * SAMPLE_RATE, bpm = 92.0, createdAt = 2,
    )

    private val generated = Song(
        id = "generated", title = "Stuthi Paadana", kind = SongKind.GENERATED,
        sections = listOf(
            Section("Intro", chords = "C G", hold = false),
            Section("Pallavi", chords = "C C F C", lyrics = "స్తుతి పాడనా"),
            Section("Charanam 1", chords = "F G C Am"),
        ),
        gen = GenSettings(key = 0, transpose = 2, bpm = 84, rhythm = Rhythm.DADRA), createdAt = 1,
    )

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val seed = com.voicerecordmix.data.Repo(app)
        seed.saveSong(imported)
        seed.saveSong(generated)
        // Fake waveform peaks (20 per second).
        seed.savePeaks(imported.id, FloatArray(170 * 20) { i ->
            val t = i / 20.0
            (0.25 + 0.5 * abs(sin(t * 0.7)) * (if (t < 9) 0.4 else 1.0)).toFloat() * (0.8f + 0.2f * ((i * 7919) % 10) / 10f)
        })
        val take = Take("take1", imported.id, imported.title, 3, 11L * 60 * SAMPLE_RATE, 2400, true, MixSettings(voiceVol = 1.6f))
        seed.saveTake(take)
        seed.takeMusic(take.id).writeBytes(ByteArray(SAMPLE_RATE * 4 * 2))
        seed.takeVoice(take.id).writeBytes(ByteArray(SAMPLE_RATE * 2 * 2))
        vm = AppViewModel(app)
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(screen: Screen) {
        vm.navigate(screen)
        compose.setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val s = vm.screen) {
                        Screen.Home -> HomeScreen(vm)
                        Screen.Ready -> ReadyScreen(vm)
                        is Screen.Edit -> EditScreen(vm, s.songId)
                        is Screen.Perform -> PerformScreen(vm, s.songId)
                        is Screen.Mix -> MixScreen(vm, s.takeId)
                    }
                }
            }
        }
    }

    @Test fun home() { show(Screen.Home); shoot("1_home") }
    @Test fun ready() { show(Screen.Ready); shoot("0_ready_music") }
    @Test fun editImported() { show(Screen.Edit(imported.id)); shoot("2_edit_track") }
    @Test fun editGenerated() { show(Screen.Edit(generated.id)); shoot("3_edit_rhythm") }
    @Test fun performStart() { show(Screen.Perform(imported.id)); shoot("4_sing_start") }

    @Test
    fun performLive() {
        show(Screen.Perform(generated.id))
        compose.runOnIdle { vm.startPerform(generated, 1, record = false) }
        Thread.sleep(400)
        compose.runOnIdle { vm.repeat(2) }
        Thread.sleep(300)
        compose.mainClock.advanceTimeBy(200)
        shoot("5_sing_live")
        compose.runOnIdle { vm.stopAudio() }
    }

    @Test fun mix() { show(Screen.Mix("take1")); shoot("6_mix") }
}
