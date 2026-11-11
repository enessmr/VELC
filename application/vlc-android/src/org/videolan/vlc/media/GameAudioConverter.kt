package org.videolan.vlc.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import me.ayra.vgmstream.VgmNative
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Decodes NintendoWare streams, waves, and RWAR entries to PCM WAVE. */
internal object GameAudioConverter {
    private const val TAG = "VELC/GameAudio"
    private const val MAX_WAVE_BYTES = 1024L * 1024 * 1024
    private val EXTENSIONS = setOf("brstm", "ast", "brwav", "rwav", "rwar", "bfstm", "bfwav", "fwav", "bcstm", "bcwav")
    private const val RWAR_FRAGMENT = "velc-rwar="

    data class Decoded(val uri: Uri, val loopPoints: ABRepeat?)

    fun supports(uri: Uri): Boolean {
        return uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() in EXTENSIONS
    }

    /** Returns the number of playable RWAVs; null means this is not a valid RWAR. */
    fun archiveTrackCount(context: Context, uri: Uri): Int? {
        if (!uri.lastPathSegment.orEmpty().endsWith(".rwar", true) || uri.fragment?.startsWith(RWAR_FRAGMENT) == true) return null
        val input = File.createTempFile("velc-rwar-", ".rwar", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { source -> input.outputStream().use(source::copyTo) }
                ?: return null
            RandomAccessFile(input, "r").use { rwarEntries(it).size }
        } catch (error: Exception) {
            Log.w(TAG, "Cannot read RWAR $uri", error)
            null
        } finally { input.delete() }
    }

    fun decode(context: Context, uri: Uri): Decoded? {
        if (!supports(uri) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val source = File(uri.path.orEmpty())
        val stamp = if (uri.scheme == "file") "${source.length()}:${source.lastModified()}" else ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("v3:$uri:$stamp".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        val output = File(context.cacheDir, "velc-game-$digest.wav")
        if (output.length() > 44L) return Decoded(Uri.fromFile(output), readLoop(output))

        val extension = ".${uri.lastPathSegment?.substringAfterLast('.', "brstm")?.lowercase()}"
        val input = File.createTempFile("velc-game-input-", extension, context.cacheDir)
        val pending = File.createTempFile("velc-game-output-", ".wav", context.cacheDir)
        var entryFile: File? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                input.outputStream().use { stream.copyTo(it) }
            } ?: return null
            val decodeInput = if (extension == ".rwar") {
                val entry = uri.fragment?.removePrefix(RWAR_FRAGMENT)?.toIntOrNull() ?: 1
                val subfile = File.createTempFile("velc-rwar-entry-", ".rwav", context.cacheDir)
                entryFile = subfile
                RandomAccessFile(input, "r").use { archive ->
                    val entries = rwarEntries(archive)
                    require(entry in 1..entries.size)
                    val (offset, length) = entries[entry - 1]
                    archive.seek(offset)
                    subfile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = length
                        while (remaining > 0) {
                            val read = archive.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            require(read > 0) { "Truncated RWAR entry" }
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
                subfile
            } else input
            val native = VgmNative()
            val metadataHandle = native.open(decodeInput.absolutePath, 1.0, 0L, 0, 0L, false, 2, 0)
            val loop = try { native.getLoopInfo(metadataHandle) } finally { native.close(metadataHandle) }
            val handle = native.open(decodeInput.absolutePath, 1.0, 0L, 2, 0L, false, 2, 0)
            try {
                val rate = native.getSampleRate(handle)
                val channels = native.getChannels(handle)
                require(rate in 8000..192000 && channels in 1..2)
                val start = loop.getOrElse(3) { 0L }
                val end = loop.getOrElse(4) { 0L }
                val hasLoop = loop.getOrElse(0) { 0L } != 0L && start >= 0 && end > start
                var frames = 0L
                RandomAccessFile(pending, "rw").use { wave ->
                    writeHeader(wave, rate, channels, if (hasLoop) start else null, end)
                    val samples = ShortArray(2048 * channels)
                    val bytes = ByteArray(samples.size * 2)
                    while (true) {
                        val count = native.readPcm(handle, samples, 2048)
                        if (count <= 0) break
                        val byteCount = count * channels * 2
                        require(wave.length() + byteCount <= MAX_WAVE_BYTES) { "Decoded WAVE is too large" }
                        for (i in 0 until count * channels) {
                            val value = samples[i].toInt()
                            bytes[2 * i] = value.toByte()
                            bytes[2 * i + 1] = (value ushr 8).toByte()
                        }
                        wave.write(bytes, 0, byteCount)
                        frames += count
                    }
                    require(frames > 0L) { "No audio frames decoded" }
                    wave.seek(4)
                    wave.le32(wave.length() - 8)
                    wave.seek(if (hasLoop) 108 else 40)
                    wave.le32(frames * channels * 2)
                }
                require(pending.renameTo(output)) { "Cannot cache decoded audio" }
                Log.i(TAG, "Decoded $uri: $frames frames at $rate Hz")
                return Decoded(Uri.fromFile(output), readLoop(output))
            } finally {
                native.close(handle)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Cannot decode $uri", error)
            return null
        } finally {
            input.delete()
            entryFile?.delete()
            pending.delete()
        }
    }

    private fun rwarEntries(file: RandomAccessFile): List<Pair<Long, Long>> {
        require(file.length() >= 0x20)
        file.seek(0)
        require(file.readInt() == 0x52574152) { "Missing RWAR header" }
        file.seek(0x10)
        val tableOffset = file.readInt().toLong() and 0xffffffffL
        file.seek(0x18)
        val dataOffset = file.readInt().toLong() and 0xffffffffL
        require(tableOffset + 12 <= file.length() && dataOffset + 8 <= file.length())
        file.seek(tableOffset)
        require(file.readInt() == 0x5441424c) { "Missing TABL section" }
        file.seek(dataOffset)
        require(file.readInt() == 0x44415441) { "Missing DATA section" }
        file.seek(tableOffset + 8)
        val count = file.readInt()
        require(count in 1..4096 && tableOffset + 12L + count * 12L <= file.length())
        return (0 until count).map { index ->
            file.seek(tableOffset + 12L + index * 12L + 4L)
            val offset = dataOffset + (file.readInt().toLong() and 0xffffffffL)
            val size = file.readInt().toLong() and 0xffffffffL
            require(size > 0 && offset >= dataOffset + 8 && offset <= file.length() - size)
            offset to size
        }
    }

    private fun readLoop(file: File): ABRepeat? =
        FileInputStream(file).use { WaveSmplLoopPoints.read(it, 0L) }

    private fun writeHeader(wave: RandomAccessFile, rate: Int, channels: Int, start: Long?, end: Long) {
        wave.writeBytes("RIFF")
        wave.le32(0)
        wave.writeBytes("WAVEfmt ")
        wave.le32(16)
        wave.le16(1)
        wave.le16(channels)
        wave.le32(rate.toLong())
        wave.le32(rate.toLong() * channels * 2)
        wave.le16(channels * 2)
        wave.le16(16)
        if (start != null) {
            wave.writeBytes("smpl")
            wave.le32(60)
            repeat(7) { wave.le32(0) }
            wave.le32(1) // one sample loop
            wave.le32(0)
            wave.le32(0) // cue ID
            wave.le32(0) // forward loop
            wave.le32(start)
            wave.le32(end - 1) // WAVE smpl end is inclusive
            wave.le32(0)
            wave.le32(0)
        }
        wave.writeBytes("data")
        wave.le32(0)
    }

    private fun RandomAccessFile.le16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun RandomAccessFile.le32(value: Long) {
        repeat(4) { write(((value ushr (it * 8)) and 0xff).toInt()) }
    }
}
