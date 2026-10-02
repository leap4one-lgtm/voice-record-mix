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

/** [lowLatency] asks for the fast output path with a small buffer (used for voice monitoring). */
fun newMusicTrack(lowLatency: Boolean = false): AudioTrack {
    val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    val bytes = if (lowLatency) maxOf(minBuf, 480 * 4 * 3) else maxOf(minBuf * 2, BLOCK * 4 * 4)
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
        .setBufferSizeInBytes(bytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(
            if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE
        )
        .build()
}

/**
 * Plays a [LoopEngine] to the speaker/headphones and, when [takeDir] is given, records the mic at
 * the same time. Both the music actually played and the voice are written as separate stems, so
 * the final mix can be rebalanced later.
 */
class AudioRunner(
    private val engine: LoopEngine,
    private val takeDir: File?,
    /** Hear your own voice in the headphones (only while recording). */
    private val monitor: Boolean = false,
) {
    class Result(val frames: Long, val leadFrames: Int, val measured: Boolean, val voicePeak: Float)

    @Volatile var musicGain = 1f
    @Volatile var monitorGain = 1f

    // Mic -> headphones ring buffer (single producer: record thread, single consumer: play thread).
    private val ring = ShortArray(SAMPLE_RATE / 2)
    @Volatile private var ringWrite = 0L
    private var ringRead = 0L
    private val monitoring get() = monitor && takeDir != null
    /** Frames per audio block: small when monitoring so the voice comes back quickly. */
    private val block get() = if (monitoring) BLOCK / 4 else BLOCK
    /** Approximate delay of the voice in the headphones, in ms (0 when not monitoring). */
    @Volatile var monitorDelayMs = 0
        private set
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
        val track = newMusicTrack(lowLatency = monitoring)
        trackBufferFrames = track.bufferSizeInFrames
        val stem = stemFile?.let { PcmWriter(it) }
        val block = block
        if (monitoring) monitorDelayMs = (trackBufferFrames + block * 3) * 1000 / SAMPLE_RATE
        val f = FloatArray(block * 2)
        val pcm = ShortArray(block * 2)
        val stemPcm = ShortArray(block * 2)
        val ts = AudioTimestamp()
        try {
            track.play()
            while (running) {
                engine.render(f, block)
                for (k in 0 until block * 2) stemPcm[k] = toPcm16(f[k])
                stem?.write(stemPcm, block * 2)
                val g = musicGain
                for (k in 0 until block * 2) f[k] *= g
                if (monitoring) mixMonitor(f, block)
                for (k in 0 until block * 2) pcm[k] = toPcm16(f[k])
                track.write(pcm, 0, block * 2)
                musicFrames += block
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

    /** Adds the newest mic audio to the headphone output, skipping ahead if it falls behind. */
    private fun mixMonitor(out: FloatArray, n: Int) {
        val w = ringWrite
        if (w - ringRead > n * 3) ringRead = w - n * 2 // keep the delay short
        val g = monitorGain / 32768f
        for (k in 0 until n) {
            if (ringRead >= w) break
            val v = ring[(ringRead % ring.size).toInt()] * g
            out[k * 2] += v
            out[k * 2 + 1] += v
            ringRead++
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
        val block = block
        val buf = ShortArray(block)
        val ts = AudioTimestamp()
        var frames = 0L
        try {
            rec.startRecording()
            ready.countDown()
            while (running) {
                val n = rec.read(buf, 0, block)
                if (n <= 0) continue
                writer.write(buf, n)
                if (monitoring) {
                    var w = ringWrite
                    for (k in 0 until n) { ring[(w % ring.size).toInt()] = buf[k]; w++ }
                    ringWrite = w
                }
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
