package org.videolan.vlc.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WaveSmplLoopPointsTest {
    @Test
    fun readsInclusiveSmplLoopBeforeOrAfterAudioData() {
        for (smplAfterData in listOf(false, true)) {
            val points = WaveSmplLoopPoints.read(ByteArrayInputStream(wave(smplAfterData)), 94_311)
            assertNotNull(points)
            assertEquals(28_224L, points!!.start)
            assertEquals(94_311L, points.stop)
        }
    }

    private fun wave(smplAfterData: Boolean): ByteArray {
        val fmt = ByteArrayOutputStream().apply {
            put16(1) // PCM
            put16(2) // channels
            put32(32_000) // frames per second
            put32(128_000)
            put16(4)
            put16(16)
        }.toByteArray()
        val smpl = ByteArrayOutputStream().apply {
            repeat(7) { put32(0) }
            put32(1) // loop count
            put32(0) // sampler data
            put32(0) // identifier
            put32(0) // forward loop
            put32(903_168) // 28.224 s
            put32(3_017_951) // inclusive end at 94.311 s
            put32(0)
            put32(0)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            put32(0)
            write("WAVE".toByteArray())
            chunk("fmt ", fmt)
            if (smplAfterData) chunk("data", ByteArray(5))
            chunk("smpl", smpl)
            if (!smplAfterData) chunk("data", ByteArray(5))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.chunk(name: String, bytes: ByteArray) {
        write(name.toByteArray())
        put32(bytes.size)
        write(bytes)
        if (bytes.size and 1 != 0) write(0)
    }

    private fun ByteArrayOutputStream.put16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.put32(value: Int) {
        repeat(4) { write(value ushr (8 * it) and 0xff) }
    }
}
