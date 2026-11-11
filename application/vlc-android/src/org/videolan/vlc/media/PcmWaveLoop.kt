package org.videolan.vlc.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** A sample-accurate loop for local PCM WAVE and common Android-decoded audio files. */
internal class PcmWaveLoop private constructor(
    private val track: AudioTrack,
    private val pcm: ByteArray,
    private val sampleRate: Int,
    private val frameCount: Int,
    private val loopStart: Int,
    private val loopEnd: Int,
    private val frameSize: Int
) {
    private val lock = Any()
    @Volatile private var running = true
    private var nextFrame = 0
    private val writer = Thread({
        while (running) {
            val written = synchronized(lock) {
                if (!running) return@Thread
                if (nextFrame >= loopEnd) nextFrame = loopStart
                val frames = minOf(2048, loopEnd - nextFrame)
                val bytes = frames * frameSize
                val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    track.write(pcm, nextFrame * frameSize, bytes, AudioTrack.WRITE_NON_BLOCKING)
                else track.write(pcm, nextFrame * frameSize, bytes)
                if (count > 0) nextFrame += count / frameSize
                count
            }
            if (written < 0) break
            if (written == 0) try { Thread.sleep(5) } catch (_: InterruptedException) { break }
        }
    }, "velc-pcm-loop").apply { isDaemon = true; start() }
    private var baseFrame = 0L
    private var baseHead = 0L
    val durationMs get() = frameCount * 1000L / sampleRate
    val loopStartMs get() = loopStart * 1000L / sampleRate

    fun play() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }

    fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
    }

    fun setRate(rate: Float) {
        track.playbackRate = (sampleRate * rate).toInt().coerceAtLeast(1)
    }

    fun seekTo(timeMs: Long) {
        val resume = track.playState == AudioTrack.PLAYSTATE_PLAYING
        val requested = (timeMs * sampleRate / 1000).coerceAtLeast(0)
        val frame = if (requested >= loopEnd) loopStart else requested.toInt()
        synchronized(lock) {
            track.pause()
            track.flush()
            nextFrame = frame
            baseFrame = frame.toLong()
            baseHead = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (resume) track.play()
        }
    }

    fun positionMs(): Long {
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        val played = baseFrame + ((head - baseHead) and 0xffffffffL)
        val frame = if (played < loopEnd) played else loopStart + (played - loopEnd) % (loopEnd - loopStart)
        return frame * 1000L / sampleRate
    }

    @Suppress("DEPRECATION")
    fun setVolume(volume: Int) {
        val level = (volume / 100f).coerceIn(0f, 1f)
        track.setStereoVolume(level, level)
    }

    fun release() {
        running = false
        writer.interrupt()
        synchronized(lock) {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.release()
        }
    }

    companion object {
        private const val MAX_PCM_BYTES = 64 * 1024 * 1024

        @Suppress("DEPRECATION")
        fun load(context: Context, uri: Uri, abStartMs: Long? = null, abEndMs: Long? = null): PcmWaveLoop? {
            if (uri.scheme != "file" && uri.scheme != "content") return null
            val ab = abStartMs != null && abEndMs != null && abStartMs >= 0 && abEndMs > abStartMs
            val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            val wave = try {
                when (extension) {
                    "wav", "wave" -> context.contentResolver.openInputStream(uri)?.use { parse(it, ab) }
                    "ogg", "oga", "mp3", "m4a", "mp4", "aac", "flac" -> decodeAudio(context, uri, ab)
                    else -> if (ab) decodeAudio(context, uri, true) else null
                }
            } catch (error: Exception) {
                Log.w("VELC/SampleLoop", "Cannot decode loop audio", error)
                null
            } ?: return null
            val frames = wave.pcm.size / (wave.channels * 2)
            val start = if (ab) (abStartMs!! * wave.sampleRate / 1000).coerceIn(0, frames.toLong()).toInt() else wave.start
            val end = if (ab) (abEndMs!! * wave.sampleRate / 1000).coerceIn(0, frames.toLong()).toInt() else wave.end
            if (end <= start) return null
            val channelMask = if (wave.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val track = try {
                val minBuffer = AudioTrack.getMinBufferSize(wave.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuffer <= 0) return null
                AudioTrack(AudioManager.STREAM_MUSIC, wave.sampleRate, channelMask,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 4, 65536), AudioTrack.MODE_STREAM)
            } catch (error: Exception) {
                Log.w("VELC/SampleLoop", "Cannot create AudioTrack", error)
                return null
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w("VELC/SampleLoop", "AudioTrack did not initialize for ${wave.pcm.size} bytes")
                track.release()
                return null
            }
            Log.i("VELC/SampleLoop", "Loaded $frames frames; loop $start..$end")
            return PcmWaveLoop(track, wave.pcm, wave.sampleRate,
                frames, start, end, wave.channels * 2)
        }

        private data class Wave(val sampleRate: Int, val channels: Int, val start: Int, val end: Int, val pcm: ByteArray)

        private fun decodeAudio(context: Context, uri: Uri, ab: Boolean): Wave? {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                val trackIndex = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val rateFromFile = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (!ab && EmbeddedLoopPoints.readSampleFrames(context, uri, rateFromFile, Int.MAX_VALUE) == null) return null
                val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: return null)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()
                val pcm = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var idle = 0
                var rate = rateFromFile
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                while (!outputDone && idle < 1000) {
                    var progressed = false
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index) ?: return null
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, extractor.sampleFlags)
                                extractor.advance()
                            }
                            progressed = true
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        index >= 0 -> {
                            if (info.size > 0) {
                                require(pcm.size() + info.size <= MAX_PCM_BYTES) { "Decoded PCM exceeds loop buffer" }
                                val buffer = decoder.getOutputBuffer(index) ?: return null
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                pcm.write(bytes)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(index, false)
                            progressed = true
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val output = decoder.outputFormat
                            rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return null
                            progressed = true
                        }
                    }
                    idle = if (progressed) 0 else idle + 1
                }
                if (!outputDone || rate !in 8000..192000 || channels !in 1..2) return null
                val data = pcm.toByteArray()
                if (data.isEmpty() || data.size % (channels * 2) != 0) return null
                val frames = data.size / (channels * 2)
                val (start, end) = if (ab) 0 to frames else EmbeddedLoopPoints.readSampleFrames(context, uri, rate, frames) ?: return null
                return Wave(rate, channels, start, end, data)
            } finally {
                try { codec?.stop() } catch (_: Exception) { }
                codec?.release()
                extractor.release()
            }
        }

        private fun parse(input: InputStream, ab: Boolean): Wave? {
            val riff = ByteArray(12)
            if (!input.readAll(riff) || riff.label(0) != "RIFF" || riff.label(8) != "WAVE") return null
            var rate = 0
            var channels = 0
            var start = -1
            var end = -1
            var pcm: ByteArray? = null
            repeat(4096) {
                val header = ByteArray(8)
                if (!input.readAll(header)) return null
                val size = header.uint(4)
                when (header.label(0)) {
                    "fmt " -> {
                        if (size < 16) return null
                        val format = ByteArray(16)
                        if (!input.readAll(format)) return null
                        if (format.ushort(0) != 1 || format.ushort(14) != 16) return null
                        channels = format.ushort(2)
                        rate = format.uint(4).toInt()
                        if (channels !in 1..2 || rate !in 8000..192000 || !input.skipAll(size - 16)) return null
                    }
                    "smpl" -> {
                        if (ab) {
                            if (!input.skipAll(size)) return null
                        } else {
                            if (size < 60) return null
                            val sample = ByteArray(60)
                            if (!input.readAll(sample)) return null
                            if (sample.uint(28) < 1 || sample.uint(40) != 0L) return null
                            start = sample.uint(44).toInt()
                            end = (sample.uint(48) + 1).toInt()
                            if (!input.skipAll(size - 60)) return null
                        }
                    }
                    "data" -> {
                        if (size <= 0 || size > MAX_PCM_BYTES.toLong()) return null
                        pcm = ByteArray(size.toInt())
                        if (!input.readAll(pcm!!)) return null
                    }
                    else -> if (!input.skipAll(size)) return null
                }
                if (size and 1L != 0L && !input.skipAll(1)) return null
                val data = pcm
                if (rate > 0 && data != null && (ab || (start >= 0 && end > start && end <= data.size / (channels * 2))))
                    return Wave(rate, channels, if (ab) 0 else start, if (ab) data.size / (channels * 2) else end, data)
            }
            return null
        }

        private fun ByteArray.label(offset: Int) = String(this, offset, 4, Charsets.US_ASCII)
        private fun ByteArray.ushort(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
        private fun ByteArray.uint(offset: Int): Long = (0..3).fold(0L) { value, n -> value or ((this[offset + n].toLong() and 255) shl (n * 8)) }
        private fun InputStream.readAll(bytes: ByteArray): Boolean {
            var offset = 0
            while (offset < bytes.size) {
                val count = read(bytes, offset, bytes.size - offset)
                if (count <= 0) return false
                offset += count
            }
            return true
        }
        private fun InputStream.skipAll(size: Long): Boolean {
            var left = size
            while (left > 0) {
                val count = skip(left)
                if (count > 0) left -= count
                else if (read() >= 0) left--
                else return false
            }
            return true
        }
    }
}
