package org.videolan.vlc.media

import java.io.InputStream

/** Reads the first forward loop from a RIFF/WAVE `smpl` chunk. */
internal object WaveSmplLoopPoints {
    private data class Frames(val start: Long, val endInclusive: Long)

    fun read(input: InputStream, duration: Long): ABRepeat? {
        val riff = ByteArray(12)
        if (!input.readFully(riff) || riff.ascii(0) != "RIFF" || riff.ascii(8) != "WAVE") return null
        var sampleRate = 0L
        var frames: Frames? = null
        repeat(4096) {
            val chunk = ByteArray(8)
            if (!input.readFully(chunk)) return null
            val type = chunk.ascii(0)
            val size = chunk.u32(4)
            when (type) {
                "fmt " -> {
                    if (size < 16) return null
                    val format = ByteArray(16)
                    if (!input.readFully(format)) return null
                    sampleRate = format.u32(4)
                    if (!input.skipFully(size - 16)) return null
                }
                "smpl" -> {
                    if (size < 36) return null
                    val header = ByteArray(36)
                    if (!input.readFully(header)) return null
                    val count = minOf(header.u32(28), (size - 36) / 24, 1024L).toInt()
                    repeat(count) {
                        val loop = ByteArray(24)
                        if (!input.readFully(loop)) return null
                        if (frames == null && loop.u32(4) == 0L) {
                            frames = Frames(loop.u32(8), loop.u32(12))
                        }
                    }
                    if (!input.skipFully(size - 36 - count * 24L)) return null
                }
                else -> if (!input.skipFully(size)) return null
            }
            if (size and 1L != 0L && !input.skipFully(1)) return null
            val loop = frames
            if (sampleRate > 0 && loop != null) {
                val start = loop.start * 1000 / sampleRate
                val end = (loop.endInclusive + 1) * 1000 / sampleRate
                val stop = if (duration > 0) minOf(end, duration) else end
                return if (stop > start && (duration <= 0 || end <= duration + 2000)) ABRepeat(start, stop) else null
            }
        }
        return null
    }

    private fun ByteArray.ascii(offset: Int) = String(this, offset, 4, Charsets.US_ASCII)

    private fun ByteArray.u32(offset: Int): Long = (0..3).fold(0L) { value, i ->
        value or ((this[offset + i].toLong() and 0xff) shl (8 * i))
    }

    private fun InputStream.readFully(bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val count = read(bytes, offset, bytes.size - offset)
            if (count < 0) return false
            if (count == 0) {
                val byte = read()
                if (byte < 0) return false
                bytes[offset++] = byte.toByte()
            } else offset += count
        }
        return true
    }

    private fun InputStream.skipFully(length: Long): Boolean {
        var remaining = length
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (read() >= 0) remaining--
            else return false
        }
        return true
    }
}
