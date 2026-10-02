package com.voicerecordmix.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import com.voicerecordmix.core.LoopEngine
import com.voicerecordmix.core.SAMPLE_RATE
import com.voicerecordmix.core.toPcm16
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BLOCK = 960 // 20 ms

fun newMusicTrack(): AudioTrack {
    val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    return AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        )
        .setBufferSizeInBytes(maxOf(minBuf * 2, BLOCK * 4 * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
}

/**
 * Plays a [LoopEngine] to the speaker/headphones and, when [takeDir] is given, records the mic at
 * the same time. Both the music actually played and the voice are written as separate stems, so
 * the final mix can be rebalanced later.
 */
class AudioRunner(private val engine: LoopEngine, private val takeDir: File?) {
    class Result(val frames: Long, val leadFrames: Int, val measured: Boolean, val voicePeak: Float)

    @Volatile var musicGain = 1f
    /** Recent mic peak level, 0..1. */
    @Volatile var micLevel = 0f
        private set
    @Volatile var musicFrames = 0L
        private set
    /** Called (on the audio thread) when the music ends during practice/preview. */
    var onEnded: (() -> Unit)? = null

    @Volatile private var running = false
    private var playThread: Thread? = null
    private var recThread: Thread? = null

    @Volatile private var musicTs: LongArray? = null
    @Volatile private var voiceTs: LongArray? = null
    @Volatile private var trackBufferFrames = 0
    @Volatile private var voicePeak = 0f
    @Volatile private var micError: String? = null

    val recording get() = takeDir != null

    /** Starts audio. Returns an error message if the microphone could not be opened. */
    fun start(): String? {
        running = true
        if (takeDir != null) {
            val ready = CountDownLatch(1)
            recThread = Thread({ recordLoop(File(takeDir, "voice.pcm"), ready) }, "voice-rec").apply { start() }
            ready.await(2, TimeUnit.SECONDS)
            micError?.let { running = false; recThread?.join(); return it }
        }
        playThread = Thread({ playLoop(takeDir?.let { File(it, "music.pcm") }) }, "music-play").apply { start() }
        return null
    }

    fun stop(): Result {
        running = false
        playThread?.join(2000)
        recThread?.join(2000)
        val (lead, measured) = computeLead()
        return Result(musicFrames, lead, measured, voicePeak)
    }

    /**
     * Voice frames to skip so the voice lines up with the music. Uses the AudioTrack/AudioRecord
     * timestamps (same monotonic clock): at time T the listener hears music frame m(T) while the
     * mic captures voice frame v(T); a singer in time with the music makes v(T) belong to m(T).
     */
    private fun computeLead(): Pair<Int, Boolean> {
        val m = musicTs
        val v = voiceTs
        if (m != null && v != null) {
            val t = m[1]
            val vAtT = v[0] + (t - v[1]) * SAMPLE_RATE / 1_000_000_000.0
            val lead = (vAtT - m[0]).roundToInt()
            if (lead in -SAMPLE_RATE / 2..SAMPLE_RATE) return lead to true
        }
        // Fallback: the output buffer is the bulk of the delay before the singer hears the music.
        return trackBufferFrames to false
    }

    private fun playLoop(stemFile: File?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val track = newMusicTrack()
        trackBufferFrames = track.bufferSizeInFrames
        val stem = stemFile?.let { PcmWriter(it) }
        val f = FloatArray(BLOCK * 2)
        val pcm = ShortArray(BLOCK * 2)
        val stemPcm = ShortArray(BLOCK * 2)
        val ts = AudioTimestamp()
        try {
            track.play()
            while (running) {
                engine.render(f, BLOCK)
                val g = musicGain
                for (k in 0 until BLOCK * 2) {
                    pcm[k] = toPcm16(f[k] * g)
                    stemPcm[k] = toPcm16(f[k])
                }
                stem?.write(stemPcm, BLOCK * 2)
                track.write(pcm, 0, BLOCK * 2)
                musicFrames += BLOCK
                if (musicTs == null && musicFrames > SAMPLE_RATE * 2 && track.getTimestamp(ts) && ts.framePosition > 0) {
                    musicTs = longArrayOf(ts.framePosition, ts.nanoTime)
                }
                if (stem == null && engine.state.ended) {
                    onEnded?.invoke()
                    break
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            stem?.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop(file: File, ready: CountDownLatch) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                // VOICE_RECOGNITION: no automatic gain or noise suppression, which would pump with music.
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, BLOCK * 2 * 8))
                .build()
        } catch (e: Exception) {
            micError = "Could not open the microphone: ${e.message}"
            ready.countDown()
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            micError = "Could not open the microphone"
            rec.release()
            ready.countDown()
            return
        }
        val writer = PcmWriter(file)
        val buf = ShortArray(BLOCK)
        val ts = AudioTimestamp()
        var frames = 0L
        try {
            rec.startRecording()
            ready.countDown()
            while (running) {
                val n = rec.read(buf, 0, BLOCK)
                if (n <= 0) continue
                writer.write(buf, n)
                frames += n
                var peak = 0
                for (k in 0 until n) peak = maxOf(peak, abs(buf[k].toInt()))
                val level = peak / 32768f
                micLevel = level
                if (level > voicePeak) voicePeak = level
                if (voiceTs == null && frames > SAMPLE_RATE * 2 &&
                    rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                ) {
                    voiceTs = longArrayOf(ts.framePosition, ts.nanoTime)
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            writer.close()
        }
    }
}
