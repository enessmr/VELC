package org.videolan.vlc.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Reads common LOOPSTART, LOOPEND and LOOPLENGTH tags from local media headers. */
internal object EmbeddedLoopPoints {
    private const val MAX_HEADER = 2 * 1024 * 1024
    private val tag = Regex("(?i)(?:^|[^A-Z])(?:LOOPSTART|LOOP_START|LOOPEND|LOOP_END|LOOPLENGTH|LOOP_LENGTH)[\\s=:]+[0-9:.]+(?:ms|s)?")
    private val value = Regex("(?i)(LOOPSTART|LOOP_START|LOOPEND|LOOP_END|LOOPLENGTH|LOOP_LENGTH)[\\s=:]+([0-9:.]+)(ms|s)?")

    fun read(context: Context, uri: Uri, duration: Long): ABRepeat? {
        if (uri.scheme != "file" && uri.scheme != "content") return null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                WaveSmplLoopPoints.read(input, duration)?.let { return it }
            }
        } catch (_: Exception) { /* Try text tags below. */ }
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() < MAX_HEADER) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_HEADER - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: return null
        } catch (_: Exception) { return null }
        // Vorbis comments and ID3 TXXX frames store their keys next to their values.
        val header = bytes.toString(Charsets.ISO_8859_1).replace('\u0000', '=') +
                bytes.toString(Charsets.UTF_16LE).replace('\u0000', '=') +
                bytes.drop(1).toByteArray().toString(Charsets.UTF_16LE).replace('\u0000', '=') +
                bytes.toString(Charsets.UTF_16BE).replace('\u0000', '=')
        val tags = mutableMapOf<String, Pair<String, String?>>()
        for (match in tag.findAll(header)) {
            val parsed = value.find(match.value) ?: continue
            tags[parsed.groupValues[1].replace("_", "").uppercase()] = parsed.groupValues[2] to parsed.groupValues[3].lowercase().ifEmpty { null }
        }
        if (tags.isEmpty()) return null
        val rate = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toLongOrNull()
            }
        } catch (_: Exception) { null } else null) ?: sampleRate(bytes)
        fun millis(entry: Pair<String, String?>?): Long? {
            if (entry == null) return null
            val (raw, unit) = entry
            return when {
                unit == "ms" -> raw.toDoubleOrNull()?.toLong()
                unit == "s" || raw.contains(':') || raw.contains('.') -> {
                    val seconds = raw.split(':').fold(0.0) { acc, part -> acc * 60 + (part.toDoubleOrNull() ?: return null) }
                    (seconds * 1000).toLong()
                }
                else -> rate?.takeIf { it > 0 }?.let { raw.toLongOrNull()?.times(1000)?.div(it) }
            }
        }
        val start = millis(tags["LOOPSTART"]) ?: return null
        val end = millis(tags["LOOPEND"]) ?: millis(tags["LOOPLENGTH"])?.let { start + it } ?: duration
        val stop = if (duration > 0) minOf(end, duration) else end
        return if (start >= 0 && stop > start && (duration <= 0 || end <= duration + 2000)) ABRepeat(start, stop) else null
    }

    private fun sampleRate(bytes: ByteArray): Long? {
        if (bytes.size > 30 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "fLaC") {
            // The first metadata block is STREAMINFO. Its sample rate occupies 20 bits.
            val offset = 8 + 10
            return (((bytes[offset].toInt() and 0xff) shl 12) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 4) or
                    ((bytes[offset + 2].toInt() and 0xff) ushr 4)).toLong().takeIf { it > 0 }
        }
        val vorbis = byteArrayOf(1, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
        val index = bytes.indexOfSequence(vorbis)
        if (index >= 0 && index + 16 <= bytes.size) {
            val offset = index + 12
            return (0..3).fold(0L) { result, i -> result or ((bytes[offset + i].toLong() and 0xff) shl (8 * i)) }.takeIf { it > 0 }
        }
        if (bytes.indexOfSequence("OpusHead".toByteArray()) >= 0) return 48000L
        // MPEG audio sample rate from the first valid frame header after ID3.
        val id3End = if (bytes.size >= 10 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3") {
            10 + ((bytes[6].toInt() and 0x7f) shl 21) + ((bytes[7].toInt() and 0x7f) shl 14) +
                    ((bytes[8].toInt() and 0x7f) shl 7) + (bytes[9].toInt() and 0x7f)
        } else 0
        for (i in id3End until minOf(bytes.size - 3, id3End + 65536)) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = bytes[i + 1].toInt() and 0xff
            val b2 = bytes[i + 2].toInt() and 0xff
            if (b0 != 0xff || b1 and 0xe0 != 0xe0) continue
            val version = (b1 ushr 3) and 3
            val layer = (b1 ushr 1) and 3
            val rateIndex = (b2 ushr 2) and 3
            if (version == 1 || layer == 0 || rateIndex == 3) continue
            val base = intArrayOf(44100, 48000, 32000)[rateIndex]
            return (base / when (version) { 3 -> 1; 2 -> 2; else -> 4 }).toLong()
        }
        return null
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        for (i in 0..size - needle.size) if (needle.indices.all { this[i + it] == needle[it] }) return i
        return -1
    }
}
