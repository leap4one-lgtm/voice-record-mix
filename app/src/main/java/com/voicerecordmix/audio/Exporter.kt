package com.voicerecordmix.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.voicerecordmix.core.Mixer
import com.voicerecordmix.core.SAMPLE_RATE
import com.voicerecordmix.core.Wav
import com.voicerecordmix.core.toPcm16
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders a [Mixer] to a file. Run off the main thread. */
object Exporter {
    private const val BLOCK = 4096

    fun wav(mixer: Mixer, out: File, onProgress: (Float) -> Unit) = synchronized(mixer) {
        mixer.reset()
        val total = mixer.totalFrames
        val f = FloatArray(BLOCK * 2)
        val bytes = ByteBuffer.allocate(BLOCK * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.outputStream().buffered(1 shl 18).use { os ->
            os.write(Wav.header(total, 2))
            var pos = 0L
            while (pos < total) {
                val n = minOf(BLOCK.toLong(), total - pos).toInt()
                mixer.render(pos, n, f)
                bytes.clear()
                for (k in 0 until n * 2) bytes.putShort(toPcm16(f[k]))
                os.write(bytes.array(), 0, n * 4)
                pos += n
                onProgress(pos.toFloat() / total)
            }
        }
    }

    /** AAC in an .m4a container: about 1.4 MB per minute, plays everywhere (WhatsApp included). */
    fun m4a(mixer: Mixer, out: File, onProgress: (Float) -> Unit) = synchronized(mixer) {
        mixer.reset()
        val total = mixer.totalFrames
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BLOCK * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1
        val info = MediaCodec.BufferInfo()
        val f = FloatArray(BLOCK * 2)
        var pos = 0L
        var inputDone = false
        var done = false
        try {
            while (!done) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!.order(ByteOrder.nativeOrder())
                        buf.clear()
                        val cap = buf.capacity() / 4
                        val n = minOf(minOf(BLOCK, cap).toLong(), total - pos).toInt()
                        val ptsUs = pos * 1_000_000 / SAMPLE_RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            mixer.render(pos, n, f)
                            for (k in 0 until n * 2) buf.putShort(toPcm16(f[k]))
                            codec.queueInputBuffer(idx, 0, n * 4, ptsUs, 0)
                            pos += n
                            onProgress(pos.toFloat() / total)
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIdx = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val data = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && trackIdx >= 0) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIdx, data, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** Copies a finished export into the public Music/Worship Recorder folder (Android 10+). */
    fun saveToMusic(context: Context, file: File, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Worship Recorder")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
