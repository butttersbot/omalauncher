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

    /**
     * The other way: a recording made here (Android's Ogg Opus) as an iMessage voice message, Opus in CAF at 24 kHz as
     * an iPhone records them, so it shows on an iPhone as a voice message that plays where it is (Boss, 2026-10-24).
     * The same packets, nothing re-encoded. Null for an Ogg that isn't Opus, has nothing in it, or mixes packet lengths
     * (CAF's header holds one length for all of them).
     */
    fun fromOgg(ogg: ByteArray): ByteArray? =
        runCatching {
            val packets = oggPackets(ogg) ?: return null
            if (packets.size < 3) return null
            val head = packets[0]
            if (head.size < 19 || String(head, 0, 8, Charsets.US_ASCII) != "OpusHead") return null
            val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val channels = (head[9].toInt() and 0xFF).coerceIn(1, 2)
            val preSkip = h.getShort(10).toInt() and 0xFFFF
            val audio = packets.drop(2).filter { it.isNotEmpty() }
            if (audio.isEmpty()) return null
            val lengths = audio.map { samples48k(it) }
            val length = lengths.first()
            // The last packet may be cut short by the encoder; every one before it must be the same.
            if (length <= 0 || lengths.dropLast(1).any { it != length }) return null
            val perPacket = length * RATE / 48_000
            val priming = preSkip * RATE / 48_000
            val total = lastGranule(ogg).let { if (it > 0) (it * RATE / 48_000).toLong() else audio.size.toLong() * perPacket }
            val valid = (total - priming).coerceIn(0, audio.size.toLong() * perPacket - priming)
            val remainder = (audio.size.toLong() * perPacket - priming - valid).toInt()
            val out = ByteArrayOutputStream()
            fun chunk(
                type: String,
                body: ByteArray,
            ) {
                out.write(type.toByteArray(Charsets.US_ASCII))
                out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(body.size.toLong()).array())
                out.write(body)
            }
            // "caff", version 1, no flags.
            out.write(byteArrayOf(0x63, 0x61, 0x66, 0x66, 0, 1, 0, 0))
            chunk(
                "desc",
                ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
                    putDouble(RATE.toDouble())
                    put("opus".toByteArray(Charsets.US_ASCII))
                    putInt(0) // flags
                    putInt(0) // bytes per packet: the sizes are in the packet table
                    putInt(perPacket)
                    putInt(channels)
                    putInt(0) // bits per channel
                }.array(),
            )
            // Mono, or stereo: kAudioChannelLayoutTag_Mono (100 << 16 | 1) or _Stereo (101 << 16 | 2).
            chunk("chan", ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(if (channels == 1) (100 shl 16) or 1 else (101 shl 16) or 2).putInt(0).putInt(0).array())
            val table = ByteArrayOutputStream()
            table.write(ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).putLong(audio.size.toLong()).putLong(valid).putInt(priming).putInt(remainder).array())
            audio.forEach { table.write(vlq(it.size)) }
            chunk("pakt", table.toByteArray())
            val data = ByteArrayOutputStream()
            data.write(byteArrayOf(0, 0, 0, 0)) // edit count
            audio.forEach { data.write(it) }
            chunk("data", data.toByteArray())
            out.toByteArray()
        }.getOrNull()

    /** The time base iPhone voice messages are kept at. */
    const val RATE = 24_000

    /** How long Opus packet [p] lasts, in 48 kHz samples, from its TOC byte (RFC 6716 §3.1); 0 when it can't say. */
    fun samples48k(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xFF
        val config = toc shr 3
        // One frame's length in 48 kHz samples: SILK 10/20/40/60 ms, hybrid 10/20 ms, CELT 2.5/5/10/20 ms.
        val frame =
            when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
                config < 16 -> intArrayOf(480, 960)[config % 2]
                else -> intArrayOf(120, 240, 480, 960)[config % 4]
            }
        val frames =
            when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> if (p.size < 2) 0 else p[1].toInt() and 0x3F
            }
        return frame * frames
    }

    /** CAF's packet size: seven bits a byte, highest first, every byte but the last with its top bit set. */
    private fun vlq(n: Int): ByteArray {
        val bytes = ArrayList<Byte>()
        var v = n
        bytes += (v and 0x7F).toByte()
        v = v ushr 7
        while (v > 0) {
            bytes.add(0, ((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        return bytes.toByteArray()
    }

    /** The packets of the first stream in [ogg], put back together across pages; null when it isn't Ogg. */
    private fun oggPackets(ogg: ByteArray): List<ByteArray>? {
        val out = ArrayList<ByteArray>()
        val part = ByteArrayOutputStream()
        var at = 0
        while (at + 27 <= ogg.size) {
            if (String(ogg, at, 4, Charsets.US_ASCII) != "OggS") return if (out.isEmpty()) null else out
            val count = ogg[at + 26].toInt() and 0xFF
            var body = at + 27 + count
            for (i in 0 until count) {
                val n = ogg[at + 27 + i].toInt() and 0xFF
                if (body + n > ogg.size) return out
                part.write(ogg, body, n)
                body += n
                if (n < 255) {
                    out += part.toByteArray()
                    part.reset()
                }
            }
            at = body
        }
        return out
    }

    /** The last page's granule position: where the audio ends, in 48 kHz samples. */
    private fun lastGranule(ogg: ByteArray): Long {
        var at = 0
        var last = 0L
        while (at + 27 <= ogg.size && String(ogg, at, 4, Charsets.US_ASCII) == "OggS") {
            val g = ByteBuffer.wrap(ogg, at + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (g >= 0) last = g
            val count = ogg[at + 26].toInt() and 0xFF
            var size = 0
            for (i in 0 until count) size += ogg[at + 27 + i].toInt() and 0xFF
            at += 27 + count + size
        }
        return last
    }

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
