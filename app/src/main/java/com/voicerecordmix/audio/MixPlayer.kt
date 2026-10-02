package com.voicerecordmix.audio

import android.os.Process
import com.voicerecordmix.core.Mixer
import com.voicerecordmix.core.toPcm16

/** Plays a take's mix with live slider changes. */
class MixPlayer(private val mixer: Mixer) {
    @Volatile var position = 0L
        private set
    @Volatile var playing = false
        private set
    var onEnded: (() -> Unit)? = null
    private var thread: Thread? = null

    fun play(from: Long) {
        stop()
        position = from.coerceIn(0, mixer.totalFrames)
        playing = true
        thread = Thread({ loop() }, "mix-preview").apply { start() }
    }

    fun stop() {
        playing = false
        thread?.join(1000)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val track = newMusicTrack()
        val block = 2048
        val f = FloatArray(block * 2)
        val pcm = ShortArray(block * 2)
        // The mixer is shared with the exporter only when the player is stopped.
        synchronized(mixer) {
            mixer.reset()
            try {
                track.play()
                while (playing && position < mixer.totalFrames) {
                    mixer.render(position, block, f)
                    for (k in 0 until block * 2) pcm[k] = toPcm16(f[k])
                    track.write(pcm, 0, block * 2)
                    position += block
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
        if (playing) {
            playing = false
            onEnded?.invoke()
        }
    }
}
