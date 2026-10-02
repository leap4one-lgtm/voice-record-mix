package com.voicerecordmix.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.voicerecordmix.core.Resampler
import com.voicerecordmix.core.SAMPLE_RATE
import com.voicerecordmix.core.toPcm16
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

/** Decodes any audio file Android can play into 48 kHz stereo 16-bit PCM. */
object Decoder {
    class Result(val frames: Long, val peaks: FloatArray)

    fun decode(context: Context, uri: Uri, out: File, onProgress: (Float) -> Unit): Result {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio found in this file")
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var isFloat = false
        var resampler = Resampler(rate, SAMPLE_RATE, 2)
        val writer = PcmWriter(out)
        var frames = 0L
        var lastProgress = 0f
        // Peaks are gathered per 1/20 s while decoding, then reduced for the waveform view.
        val blockPeaks = ArrayList<Float>()
        var blockMax = 0f
        var blockCount = 0
        val peakBlock = SAMPLE_RATE / 20

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var shorts = ShortArray(0)
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (frames == 0L) resampler = Resampler(rate, SAMPLE_RATE, 2)
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val inFrames = if (isFloat) info.size / 4 / channels else info.size / 2 / channels
                        val stereo = FloatArray(inFrames * 2)
                        if (isFloat) {
                            val fb = buf.asFloatBuffer()
                            for (k in 0 until inFrames) {
                                val l = fb.get(k * channels)
                                stereo[k * 2] = l
                                stereo[k * 2 + 1] = if (channels > 1) fb.get(k * channels + 1) else l
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            for (k in 0 until inFrames) {
                                val l = sb.get(k * channels) / 32768f
                                stereo[k * 2] = l
                                stereo[k * 2 + 1] = if (channels > 1) sb.get(k * channels + 1) / 32768f else l
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        val res = resampler.process(stereo, inFrames)
                        if (shorts.size < res.size) shorts = ShortArray(res.size)
                        for (k in res.indices) {
                            shorts[k] = toPcm16(res[k])
                            if (k % 2 == 0) {
                                blockMax = maxOf(blockMax, abs(res[k]), abs(res[k + 1]))
                                if (++blockCount == peakBlock) {
                                    blockPeaks.add(blockMax); blockMax = 0f; blockCount = 0
                                }
                            }
                        }
                        writer.write(shorts, res.size)
                        frames += res.size / 2
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            if (p - lastProgress > 0.01f) { lastProgress = p; onProgress(p) }
                        }
                    }
                }
            }
        } finally {
            writer.close()
            codec.stop(); codec.release()
            extractor.release()
        }
        if (blockCount > 0) blockPeaks.add(blockMax)
        if (frames < SAMPLE_RATE) error("The track is too short")
        return Result(frames, blockPeaks.toFloatArray())
    }
}
