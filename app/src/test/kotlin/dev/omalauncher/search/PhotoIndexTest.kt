package dev.omalauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Photo search's comparing, on the phone: the kept vectors, the nearest, and which of them show. */
class PhotoIndexTest {
    private fun unit(vararg head: Float): FloatArray {
        val v = FloatArray(PhotoIndex.DIM)
        head.forEachIndexed { i, x -> v[i] = x }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun store(vararg vs: FloatArray) = PhotoIndex.Vectors(LongArray(vs.size) { it.toLong() + 1 }, vs.fold(ByteArray(0)) { acc, v -> acc + PhotoIndex.quantize(v) })

    @Test fun theNearestPhotoComesFirst() {
        val cat = unit(1f, 0.1f, 0f)
        val beach = unit(0f, 1f, 0.2f)
        val city = unit(0.1f, 0f, 1f)
        val all = store(beach, cat, city)
        val found = PhotoIndex.nearest(all, unit(0.9f, 0.2f, 0f), 3)
        assertEquals(listOf(2L, 1L, 3L), found.map { it.first })
        // Closeness survives keeping each number in a byte: within a hundredth of the real one.
        val real = cat.zip(unit(0.9f, 0.2f, 0f)).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()
        assertEquals(real, found.first().second, 0.01f)
    }

    @Test fun onlyClearLeadersShow() {
        val scored = listOf(1L to 0.42f, 2L to 0.40f, 3L to 0.37f, 4L to 0.30f, 5L to 0.02f)
        assertEquals(listOf(1L, 2L, 3L), PhotoIndex.leaders(scored).map { it.first })
        // Nothing near enough: nothing shows, rather than the least bad.
        assertTrue(PhotoIndex.leaders(listOf(1L to 0.03f, 2L to 0.01f)).isEmpty())
    }

    @Test fun aBigLibraryIsComparedQuickly() {
        val n = 20_000
        val rnd = java.util.Random(7)
        val data = ByteArray(n * PhotoIndex.DIM) { (rnd.nextInt(255) - 127).toByte() }
        val all = PhotoIndex.Vectors(LongArray(n) { it.toLong() }, data)
        val q = unit(*FloatArray(PhotoIndex.DIM) { rnd.nextFloat() - 0.5f })
        PhotoIndex.nearest(all, q, 12)
        val t = System.nanoTime()
        PhotoIndex.nearest(all, q, 12)
        val ms = (System.nanoTime() - t) / 1e6
        // On the Mac's test machine; the Fold is in the same range. The bar is generous so it never flakes.
        assertTrue("took $ms ms", ms < 150)
    }
}
