package dev.omalauncher.messages.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/** A recording made on the phone (Ogg Opus) turned into an iPhone voice message (Opus in CAF, 24 kHz). */
class CafOpusFromOggTest {
    /** 20 ms CELT packets, as Android's Opus encoder makes them: TOC config 31, one frame. */
    private fun packets(n: Int) = List(n) { i -> byteArrayOf(0xF8.toByte()) + ByteArray(40 + i % 30) { (i + it).toByte() } }

    /** Ogg Opus as the phone records it: OpusHead (pre-skip 312 at 48 kHz), OpusTags, then the packets. */
    private fun ogg(audio: List<ByteArray>, preSkip: Int = 312): ByteArray {
        val head = ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray()); put(1); put(1); putShort(preSkip.toShort()); putInt(24_000); putShort(0); put(0)
        }.array()
        val tags = "OpusTags".toByteArray() + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        var seq = 0
        val out = java.io.ByteArrayOutputStream()
        out.write(CafOpus.page(1, seq++, 0, 0x02, listOf(head)))
        out.write(CafOpus.page(1, seq++, 0, 0, listOf(tags)))
        audio.chunked(50).forEachIndexed { i, page ->
            val end = (i * 50 + page.size) * 960L
            out.write(CafOpus.page(1, seq++, end, if ((i + 1) * 50 >= audio.size) 0x04 else 0, page))
        }
        return out.toByteArray()
    }

    private fun chunk(caf: ByteArray, type: String): ByteBuffer? {
        var at = 8
        while (at + 12 <= caf.size) {
            val t = String(caf, at, 4)
            val size = ByteBuffer.wrap(caf, at + 4, 8).long.toInt()
            if (t == type) return ByteBuffer.wrap(caf, at + 12, size).slice()
            at += 12 + size
        }
        return null
    }

    @Test fun aRecordingBecomesAnIphoneVoiceMessage() {
        val audio = packets(120)
        val caf = CafOpus.fromOgg(ogg(audio))
        assertNotNull(caf)
        assertEquals("caff", String(caf!!, 0, 4))
        val desc = chunk(caf, "desc")!!
        assertEquals(24_000.0, desc.double, 0.0)
        assertEquals("opus", ByteArray(4).also { desc.get(it) }.toString(Charsets.US_ASCII))
        desc.int
        desc.int
        assertEquals(480, desc.int) // 20 ms at 24 kHz
        assertEquals(1, desc.int)
        val pakt = chunk(caf, "pakt")!!
        assertEquals(120L, pakt.long)
        assertEquals(120L * 480 - 156, pakt.long) // the pre-skip, 312 at 48 kHz, is 156 at 24 kHz
        assertEquals(156, pakt.int)
        assertNotNull(chunk(caf, "chan"))
    }

    @Test fun theSamePacketsComeBackOut() {
        val audio = packets(300)
        val back = CafOpus.toOgg(CafOpus.fromOgg(ogg(audio))!!)!!
        // Read back by turning it round once more: the packets in the CAF data are the recording's, in order.
        val again = CafOpus.fromOgg(back)!!
        val data = chunk(again, "data")!!
        data.int
        audio.forEach { p -> assertArrayEquals(p, ByteArray(p.size).also { data.get(it) }) }
        assertEquals(0, data.remaining())
    }

    @Test fun packetsOfMixedLengthsOrNoAudioAreRefused() {
        val mixed = packets(10).toMutableList().also { it[3] = byteArrayOf(0xF0.toByte(), 1, 2) } // 10 ms in the middle
        assertNull(CafOpus.fromOgg(ogg(mixed)))
        assertNull(CafOpus.fromOgg(ogg(emptyList())))
        assertNull(CafOpus.fromOgg(ByteArray(40)))
    }

    @Test fun anOpusPacketsLengthComesFromItsTocByte() {
        assertEquals(960, CafOpus.samples48k(byteArrayOf(0xF8.toByte()))) // CELT 20 ms
        assertEquals(480, CafOpus.samples48k(byteArrayOf(0xF0.toByte()))) // CELT 10 ms
        assertEquals(1920, CafOpus.samples48k(byteArrayOf(0x09))) // SILK 20 ms, two frames
        assertEquals(2880, CafOpus.samples48k(byteArrayOf(0x1B, 0x01))) // SILK 60 ms, code 3, one frame
    }
}
