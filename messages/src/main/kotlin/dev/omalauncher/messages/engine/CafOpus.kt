package dev.omalauncher.messages.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An iMessage voice message ("Audio Message.caf": Opus in Apple's CAF container) rewrapped as an Ogg Opus file, which
 * Android plays: the same packets, in Ogg's pages, nothing decoded or re-encoded (Boss, 2026-10-23: a voice message
 * from a friend couldn't be seen or played). Null for a CAF that isn't Opus or that it can't read. Pure, for the JVM
 * tests.
 */
object CafOpus {
    fun toOgg(caf: ByteArray): ByteArray? =
        runCatching {
            val b = ByteBuffer.wrap(caf).order(ByteOrder.BIG_ENDIAN)
            if (caf.size < 8 || String(caf, 0, 4, Charsets.US_ASCII) != "caff") return null
            b.position(8)
            var rate = 0.0
            var framesPerPacket = 0
            var channels = 1
            var sizes: List<Int>? = null
            var priming = 0
            var data: Pair<Int, Int>? = null
            while (b.remaining() >= 12) {
                val type = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)
                val size = b.long
                val start = b.position()
                val end = if (size < 0) caf.size else (start + size).toInt().coerceAtMost(caf.size)
                when (type) {
                    "desc" -> {
                        rate = b.double
                        val format = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)
                        if (format != "opus") return null
                        b.int // flags
                        b.int // bytes per packet: 0, the sizes are in the packet table
                        framesPerPacket = b.int
                        channels = b.int.coerceIn(1, 2)
                    }
                    "pakt" -> {
                        val packets = b.long.toInt()
                        b.long // valid frames
                        priming = b.int
                        b.int // remainder frames
                        val list = ArrayList<Int>(packets)
                        repeat(packets) {
                            var v = 0
                            while (true) {
                                val byte = b.get().toInt() and 0xFF
                                v = (v shl 7) or (byte and 0x7F)
                                if (byte and 0x80 == 0) break
                            }
                            list += v
                        }
                        sizes = list
                    }
                    // The edit count first, then the packets.
                    "data" -> data = (start + 4) to end
                }
                b.position(end)
            }
            val packets = sizes ?: return null
            val (from, to) = data ?: return null
            if (rate <= 0 || framesPerPacket <= 0) return null
            // Opus is always counted at 48 kHz in Ogg.
            val step = (framesPerPacket * 48_000.0 / rate).toLong()
            val preSkip = (priming * 48_000.0 / rate).toInt().coerceIn(0, 65_535)
            val out = ByteArrayOutputStream()
            val serial = 0x50AC0FF0
            var page = 0
            fun write(
                body: List<ByteArray>,
                granule: Long,
                flags: Int,
            ) {
                out.write(page(serial, page++, granule, flags, body))
            }
            write(listOf(head(channels, preSkip, rate.toInt())), 0, 0x02)
            write(listOf(tags()), 0, 0)
            var at = from
            var granule = 0L
            val pending = ArrayList<ByteArray>()
            var lacing = 0
            packets.forEachIndexed { i, n ->
                if (at + n > to) return null
                val p = caf.copyOfRange(at, at + n)
                at += n
                val segments = n / 255 + 1
                if (lacing + segments > 255 && pending.isNotEmpty()) {
                    write(pending.toList(), granule, 0)
                    pending.clear()
                    lacing = 0
                }
                pending += p
                lacing += segments
                granule += step
                if (i == packets.lastIndex) write(pending.toList(), granule, 0x04)
            }
            if (packets.isEmpty()) return null
            out.toByteArray()
        }.getOrNull()

    private fun head(
        channels: Int,
        preSkip: Int,
        rate: Int,
    ): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))
            put(1)
            put(channels.toByte())
            putShort(preSkip.toShort())
            putInt(rate)
            putShort(0)
            put(0)
        }.array()

    private fun tags(): ByteArray {
        val vendor = "Soma Messages".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.US_ASCII))
            putInt(vendor.size)
            put(vendor)
            putInt(0)
        }.array()
    }

    /** One Ogg page holding [packets], each ending on it. */
    fun page(
        serial: Int,
        sequence: Int,
        granule: Long,
        flags: Int,
        packets: List<ByteArray>,
    ): ByteArray {
        val lacing = ArrayList<Int>()
        for (p in packets) {
            var left = p.size
            while (left >= 255) {
                lacing += 255
                left -= 255
            }
            lacing += left
        }
        val bodySize = packets.sumOf { it.size }
        val page = ByteBuffer.allocate(27 + lacing.size + bodySize).order(ByteOrder.LITTLE_ENDIAN)
        page.put("OggS".toByteArray(Charsets.US_ASCII))
        page.put(0)
        page.put(flags.toByte())
        page.putLong(granule)
        page.putInt(serial)
        page.putInt(sequence)
        page.putInt(0) // the checksum, below
        page.put(lacing.size.toByte())
        lacing.forEach { page.put(it.toByte()) }
        packets.forEach { page.put(it) }
        val bytes = page.array()
        val crc = crc(bytes)
        bytes[22] = crc.toByte()
        bytes[23] = (crc ushr 8).toByte()
        bytes[24] = (crc ushr 16).toByte()
        bytes[25] = (crc ushr 24).toByte()
        return bytes
    }

    private val table =
        IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }

    /** Ogg's checksum: CRC-32, polynomial 0x04C11DB7, not reflected, starting at 0. */
    fun crc(bytes: ByteArray): Int {
        var c = 0
        for (x in bytes) c = (c shl 8) xor table[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return c
    }
}
