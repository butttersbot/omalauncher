package dev.omalauncher.voice

/**
 * 16 kHz mono 16-bit audio as a FLAC file: lossless, so the Mac hears exactly what the WAV carried, in some
 * 30-50% fewer bytes (whisper-server decodes FLAC itself; away from home the phone reaches the Mac through
 * Tailscale's relay at about 1 MB/s). Fixed predictors and Rice codes only, which is all speech needs here.
 * Plain Kotlin, tested without Android.
 */
object Flac {
    const val BLOCK = 4096

    /** [pcm] (16-bit samples at [rate] Hz, one channel) as a whole FLAC file. */
    fun encode(
        pcm: ShortArray,
        rate: Int = 16_000,
    ): ByteArray {
        require(rate == 16_000) { "only 16 kHz: frame headers name no other rate" }
        val w = BitWriter(pcm.size + 64)
        w.bytes("fLaC".toByteArray())
        // STREAMINFO, the only metadata block (so marked last). whisper-server needs its sample count.
        w.bits(1, 1).bits(0, 7).bits(34, 24)
        w.bits(BLOCK.toLong(), 16).bits(BLOCK.toLong(), 16)
        w.bits(0, 24).bits(0, 24) // frame sizes: not known
        w.bits(rate.toLong(), 20).bits(0, 3).bits(15, 5).bits(pcm.size.toLong(), 36)
        repeat(16) { w.bits(0, 8) } // no MD5
        var frame = 0L
        var at = 0
        while (at < pcm.size) {
            val n = minOf(BLOCK, pcm.size - at)
            frame(w, pcm, at, n, frame++)
            at += n
        }
        return w.toByteArray()
    }

    private fun frame(
        w: BitWriter,
        pcm: ShortArray,
        from: Int,
        n: Int,
        number: Long,
    ) {
        val start = w.size
        w.bits(0xFFF8, 16) // sync, fixed block size
        val sizeCode = if (n == BLOCK) 12 else 7
        w.bits(sizeCode.toLong(), 4).bits(5, 4) // 16 kHz
        w.bits(0, 4).bits(4, 3).bits(0, 1) // mono, 16 bits a sample
        utf8(w, number)
        if (sizeCode == 7) w.bits((n - 1).toLong(), 16)
        w.bits(crc8(w.buf, start, w.size).toLong(), 8)
        subframe(w, pcm, from, n)
        w.align()
        w.bits(crc16(w.buf, start, w.size).toLong(), 16)
    }

    private fun subframe(
        w: BitWriter,
        pcm: ShortArray,
        from: Int,
        n: Int,
    ) {
        val x = IntArray(n) { pcm[from + it].toInt() }
        if (x.all { it == x[0] }) {
            w.bits(0, 8) // CONSTANT
            w.bits(x[0].toLong() and 0xFFFF, 16)
            return
        }
        if (n <= 4) {
            w.bits(0b00000010, 8) // VERBATIM
            for (s in x) w.bits(s.toLong() and 0xFFFF, 16)
            return
        }
        // The fixed predictor (order 0 to 4) that leaves the least.
        var best = 0
        var bestRes = residual(x, 0)
        for (order in 1..4) {
            val r = residual(x, order)
            if (r.sumOf { kotlin.math.abs(it.toLong()) } < bestRes.sumOf { kotlin.math.abs(it.toLong()) }) {
                best = order
                bestRes = r
            }
        }
        w.bits((0b001000 or best).toLong() shl 1, 8) // FIXED, no wasted bits
        for (i in 0 until best) w.bits(x[i].toLong() and 0xFFFF, 16)
        rice(w, bestRes, n, best)
    }

    /** The residual of the fixed predictor of [order], for samples [order] on. */
    private fun residual(
        x: IntArray,
        order: Int,
    ): IntArray =
        IntArray(x.size - order) {
            val i = it + order
            when (order) {
                0 -> x[i]
                1 -> x[i] - x[i - 1]
                2 -> x[i] - 2 * x[i - 1] + x[i - 2]
                3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
                else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
            }
        }

    private fun zigzag(r: Int) = ((r shl 1) xor (r shr 31)).toLong() and 0xFFFFFFFFL

    /** Rice-coded [res], in the partition order (0 to 6) that takes the fewest bits. */
    private fun rice(
        w: BitWriter,
        res: IntArray,
        n: Int,
        order: Int,
    ) {
        val u = LongArray(res.size) { zigzag(res[it]) }
        var bestPo = 0
        var bestBits = Long.MAX_VALUE
        var bestKs = IntArray(1)
        for (po in 0..6) {
            val parts = 1 shl po
            if (n % parts != 0 || n / parts <= order) break
            val ks = IntArray(parts)
            var bits = 0L
            for (p in 0 until parts) {
                val (a, b) = range(p, n, parts, order)
                var bestK = 0
                var bestP = Long.MAX_VALUE
                for (k in 0..14) {
                    var s = 0L
                    for (i in a until b) s += (u[i] shr k) + 1 + k
                    if (s < bestP) {
                        bestP = s
                        bestK = k
                    }
                }
                ks[p] = bestK
                bits += bestP + 4
            }
            if (bits < bestBits) {
                bestBits = bits
                bestPo = po
                bestKs = ks
            }
        }
        w.bits(0, 2).bits(bestPo.toLong(), 4)
        val parts = 1 shl bestPo
        for (p in 0 until parts) {
            val k = bestKs[p]
            w.bits(k.toLong(), 4)
            val (a, b) = range(p, n, parts, order)
            for (i in a until b) {
                var q = u[i] shr k
                while (q >= 32) {
                    w.bits(0, 32)
                    q -= 32
                }
                w.bits(1, (q + 1).toInt())
                if (k > 0) w.bits(u[i] and ((1L shl k) - 1), k)
            }
        }
    }

    /** Partition [p]'s residuals, as indexes into the residual (which starts [order] samples in). */
    private fun range(
        p: Int,
        n: Int,
        parts: Int,
        order: Int,
    ): Pair<Int, Int> {
        val len = n / parts
        val a = if (p == 0) 0 else p * len - order
        return a to (p + 1) * len - order
    }

    private fun utf8(
        w: BitWriter,
        v: Long,
    ) {
        when {
            v < 0x80 -> w.bits(v, 8)
            v < 0x800 -> w.bits(0xC0 or (v shr 6).toInt(), 8).bits(0x80 or (v and 0x3F).toInt(), 8)
            v < 0x10000 -> w.bits(0xE0 or (v shr 12).toInt(), 8).bits(0x80 or ((v shr 6) and 0x3F).toInt(), 8).bits(0x80 or (v and 0x3F).toInt(), 8)
            else -> {
                w.bits(0xF0 or (v shr 18).toInt(), 8).bits(0x80 or ((v shr 12) and 0x3F).toInt(), 8)
                w.bits(0x80 or ((v shr 6) and 0x3F).toInt(), 8).bits(0x80 or (v and 0x3F).toInt(), 8)
            }
        }
    }

    fun crc8(
        b: ByteArray,
        from: Int,
        to: Int,
    ): Int {
        var c = 0
        for (i in from until to) {
            c = c xor (b[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    fun crc16(
        b: ByteArray,
        from: Int,
        to: Int,
    ): Int {
        var c = 0
        for (i in from until to) {
            c = c xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    /** Bits, most significant first; [size] counts whole bytes written. */
    private class BitWriter(
        capacity: Int,
    ) {
        var buf = ByteArray(maxOf(capacity, 64))
        var size = 0
        private var acc = 0L
        private var have = 0

        fun bits(
            v: Long,
            n: Int,
        ): BitWriter {
            if (n > 32) {
                bits(v ushr 32, n - 32)
                return bits(v and 0xFFFFFFFFL, 32)
            }
            acc = (acc shl n) or (v and ((1L shl n) - 1))
            have += n
            while (have >= 8) {
                have -= 8
                put(((acc ushr have) and 0xFF).toInt())
            }
            acc = acc and ((1L shl have) - 1)
            return this
        }

        fun bits(
            v: Int,
            n: Int,
        ) = bits(v.toLong(), n)

        fun bytes(b: ByteArray) {
            for (x in b) bits(x.toLong() and 0xFF, 8)
        }

        fun align() {
            if (have > 0) bits(0, 8 - have)
        }

        private fun put(b: Int) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = b.toByte()
        }

        fun toByteArray() = buf.copyOf(size)
    }
}

/** [samples] (-1 to 1) as 16-bit, as both the WAV and the FLAC carry them. */
fun pcm16(samples: FloatArray) = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
