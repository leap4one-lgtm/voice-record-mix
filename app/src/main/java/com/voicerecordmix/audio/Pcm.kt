package com.voicerecordmix.audio

import com.voicerecordmix.core.PcmReader
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel

/** Memory-mapped little-endian 16-bit PCM file. */
class FilePcm(file: File, override val channels: Int) : PcmReader, Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val size = raf.length()
    private val map: ShortBuffer? = if (size > 0) {
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    } else null

    override val frames: Long = size / (2L * channels)

    override fun read(frame: Long, dst: ShortArray, n: Int) {
        java.util.Arrays.fill(dst, 0, n * channels, 0)
        val m = map ?: return
        val from = maxOf(frame, 0L)
        val to = minOf(frame + n, frames)
        if (to <= from) return
        val b = m.duplicate()
        b.position((from * channels).toInt())
        b.get(dst, ((from - frame) * channels).toInt(), ((to - from) * channels).toInt())
    }

    override fun close() = raf.close()
}

/** Appends little-endian 16-bit samples to a file. */
class PcmWriter(file: File) : Closeable {
    private val out = BufferedOutputStream(file.outputStream(), 1 shl 18)
    private var bytes = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    var samplesWritten = 0L
        private set

    fun write(data: ShortArray, count: Int) {
        if (bytes.capacity() < count * 2) bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.clear()
        bytes.asShortBuffer().put(data, 0, count)
        out.write(bytes.array(), 0, count * 2)
        samplesWritten += count
    }

    override fun close() = out.close()
}
