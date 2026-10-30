package dev.omalauncher.messages.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** An iMessage voice message (Opus in CAF) rewrapped as Ogg Opus, with a made-up CAF of the same shape as a real one. */
class CafOpusTest {
    /** A CAF like iMessage's: desc (24 kHz Opus, 480 frames a packet), data, then the packet table after it. */
    private fun caf(packets: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeBytes("caff")
        d.writeShort(1)
        d.writeShort(0)
        d.writeBytes("desc")
        d.writeLong(32)
        d.writeDouble(24_000.0)
        d.writeBytes("opus")
        d.writeInt(0)
        d.writeInt(0)
        d.writeInt(480)
        d.writeInt(1)
        d.writeInt(0)
        val body = packets.fold(ByteArray(0)) { a, p -> a + p }
        d.writeBytes("data")
        d.writeLong(4L + body.size)
        d.writeInt(0)
        d.write(body)
        val table = ByteArrayOutputStream()
        packets.forEach { p ->
            val n = p.size
            if (n >= 128) table.write(0x80 or (n shr 7))
            table.write(n and 0x7F)
        }
        d.writeBytes("pakt")
        d.writeLong(24L + table.size())
        d.writeLong(packets.size.toLong())
        d.writeLong(packets.size * 480L)
        d.writeInt(0)
        d.writeInt(0)
        d.write(table.toByteArray())
        return out.toByteArray()
    }

    private fun pages(ogg: ByteArray): List<Pair<Int, Long>> {
        val found = ArrayList<Pair<Int, Long>>()
        var at = 0
        while (at < ogg.size) {
            assertEquals("OggS", String(ogg, at, 4))
            val flags = ogg[at + 5].toInt()
            var granule = 0L
            for (i in 7 downTo 0) granule = (granule shl 8) or (ogg[at + 6 + i].toLong() and 0xFF)
            val segments = ogg[at + 26].toInt() and 0xFF
            val body = (0 until segments).sumOf { ogg[at + 27 + it].toInt() and 0xFF }
            found += flags to granule
            at += 27 + segments + body
        }
        return found
    }

    @Test fun aVoiceMessageBecomesOggOpusWithTheSamePackets() {
        val packets = listOf(ByteArray(40) { 1 }, ByteArray(200) { 2 }, ByteArray(99) { 3 })
        val ogg = CafOpus.toOgg(caf(packets))
        assertNotNull(ogg)
        ogg!!
        assertEquals("OpusHead", String(ogg, 28, 8))
        val p = pages(ogg)
        // OpusHead (beginning of stream), OpusTags, then the packets on one page that ends the stream.
        assertEquals(3, p.size)
        assertEquals(0x02, p[0].first)
        assertEquals(0x04, p[2].first)
        // Three 20 ms packets, counted at 48 kHz.
        assertEquals(3 * 960L, p[2].second)
        // The packets themselves, unchanged, at the end.
        val tail = ogg.copyOfRange(ogg.size - 339, ogg.size)
        assertArrayEquals(packets.fold(ByteArray(0)) { a, b -> a + b }, tail)
    }

    @Test fun manyPacketsSpreadOverPages() {
        val ogg = CafOpus.toOgg(caf(List(400) { ByteArray(60) { 7 } }))!!
        val p = pages(ogg)
        assertTrue(p.size > 3)
        assertEquals(400 * 960L, p.last().second)
        assertEquals(0x04, p.last().first)
    }

    @Test fun notOpusOrNotCafIsNull() {
        assertNull(CafOpus.toOgg("RIFF....WAVE".toByteArray()))
        val aac = caf(listOf(ByteArray(10))).also { it[8 + 12 + 8] = 'a'.code.toByte() }
        assertNull(CafOpus.toOgg(aac))
    }

    @Test fun oggsChecksumIsCrc32WithoutReflection() {
        assertEquals(0x89A1897F.toInt(), CafOpus.crc("123456789".toByteArray()))
    }

    @Test fun aVoiceNoteIsKnownByItsFlagOrItsName() {
        assertEquals("audio/x-caf", MacWire.mimeOf("Audio Message.caf", "application/octet-stream"))
        assertEquals("audio/x-caf", MacWire.mimeOf("x", "application/octet-stream", voiceNote = true))
        assertEquals("application/pdf", MacWire.mimeOf("Lease.pdf", "application/octet-stream"))
        assertEquals("image/png", MacWire.mimeOf("a.png", "image/png"))
        assertEquals("1 Voice message", MacWire.summary(listOf(MacWire.Attachment("a", "Audio Message.caf", "audio/x-caf"))))
        assertTrue(Parts.of("1 Voice message").single() is Part.Attachment)
    }
}
