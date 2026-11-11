package org.videolan.vlc.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.InputStream

/** A sample-accurate loop for local, 16-bit PCM WAVE files with a `smpl` chunk. */
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
        val frame = (timeMs * sampleRate / 1000).coerceIn(0, (loopEnd - 1).toLong()).toInt()
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
        fun load(context: Context, uri: Uri): PcmWaveLoop? {
            if (uri.scheme != "file" && uri.scheme != "content") return null
            val wave = try { context.contentResolver.openInputStream(uri)?.use(::parse) } catch (error: Exception) {
                Log.w("VELC/SampleLoop", "Cannot read PCM WAVE", error)
                null
            } ?: run {
                Log.w("VELC/SampleLoop", "Unsupported or unreadable PCM WAVE: $uri")
                return null
            }
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
            Log.i("VELC/SampleLoop", "Loaded ${wave.pcm.size / (wave.channels * 2)} frames; loop ${wave.start}..${wave.end}")
            return PcmWaveLoop(track, wave.pcm, wave.sampleRate,
                wave.pcm.size / (wave.channels * 2), wave.start, wave.end, wave.channels * 2)
        }

        private data class Wave(val sampleRate: Int, val channels: Int, val start: Int, val end: Int, val pcm: ByteArray)

        private fun parse(input: InputStream): Wave? {
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
                        if (size < 60) return null
                        val sample = ByteArray(60)
                        if (!input.readAll(sample)) return null
                        if (sample.uint(28) < 1 || sample.uint(40) != 0L) return null
                        start = sample.uint(44).toInt()
                        end = (sample.uint(48) + 1).toInt()
                        if (!input.skipAll(size - 60)) return null
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
                if (rate > 0 && start >= 0 && end > start && data != null && end <= data.size / (channels * 2))
                    return Wave(rate, channels, start, end, data)
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
