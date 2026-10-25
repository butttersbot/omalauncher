package dev.omalauncher.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The FLAC the phone sends the Mac instead of WAV: lossless, smaller, and whole enough for whisper-server. */
class FlacTest {
    /** Something like speech: a few tones, an envelope, noise, and quiet stretches. */
    private fun speechish(n: Int): ShortArray {
        val r = Random(7)
        return ShortArray(n) { i ->
            val t = i / 16_000.0
            val env = if ((i / 4000) % 3 == 2) 0.0 else 0.5 + 0.5 * sin(2 * PI * 3 * t)
            val v = env * (6000 * sin(2 * PI * 180 * t) + 2500 * sin(2 * PI * 720 * t) + 900 * sin(2 * PI * 2400 * t)) + r.nextInt(-200, 200)
            v.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test fun theHeaderNamesTheRateAndEverySample() {
        val pcm = speechish(16_000 * 3 + 123)
        val f = Flac.encode(pcm)
        assertEquals("fLaC", String(f, 0, 4))
        assertEquals(0x80, f[4].toInt() and 0xFF) // STREAMINFO, the last metadata block
        val info = ByteBuffer.wrap(f, 18, 8).order(ByteOrder.BIG_ENDIAN).long
        assertEquals(16_000L, info ushr 44)
        assertEquals(pcm.size.toLong(), info and ((1L shl 36) - 1))
        assertEquals(0xFF, f[42].toInt() and 0xFF) // the first frame's sync
        assertEquals(0xF8, f[43].toInt() and 0xFF)
        // WAV carries 2 bytes a sample.
        assertTrue("${f.size} bytes", f.size < pcm.size * 2 * 3 / 4)
    }

    @Test fun silenceAndTinyClipsAreEncodedToo() {
        assertTrue(Flac.encode(ShortArray(16_000)).size < 200)
        assertTrue(Flac.encode(shortArrayOf(1, -1, 3)).size > 42)
        assertEquals(42, Flac.encode(ShortArray(0)).size)
    }

    @Test fun samplesAreTheWavsSixteenBits() {
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 16383), pcm16(floatArrayOf(0f, 1.5f, -1f, 0.5f)))
    }

    /** Decoded by ffmpeg, when the machine running the tests has it: the very samples come back. */
    @Test fun ffmpegDecodesTheExactSamples() {
        val ffmpeg = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue("no ffmpeg here", ffmpeg != null)
        for (n in listOf(16_000 * 5 + 77, 4096, 1000, 3)) {
            val pcm = speechish(n)
            val inFile = File.createTempFile("oma", ".flac").apply { writeBytes(Flac.encode(pcm)) }
            val outFile = File.createTempFile("oma", ".raw")
            val p = ProcessBuilder(ffmpeg, "-v", "error", "-y", "-i", inFile.path, "-f", "s16le", outFile.path).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            assertEquals(log, 0, p.waitFor())
            val back = ByteBuffer.wrap(outFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val got = ShortArray(back.remaining()).also { back.get(it) }
            assertArrayEquals("n=$n", pcm, got)
            inFile.delete()
            outFile.delete()
        }
    }
}
