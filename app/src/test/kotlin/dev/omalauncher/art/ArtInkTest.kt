package dev.omalauncher.art

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtInkTest {
    /** One channel of [color] (0..255 each) through the ink matrix, clamped like a color filter. */
    private fun print(
        r: Float,
        g: Float,
        b: Float,
    ): Triple<Float, Float, Float> {
        val m = ArtInk.MATRIX
        fun row(i: Int) = (m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4]).coerceIn(0f, 255f)
        return Triple(row(0), row(1), row(2))
    }

    @Test fun printsGrayWithMoreContrastAndALittleLight() {
        // Every color comes out gray.
        val (r, g, b) = print(200f, 40f, 90f)
        assertEquals(r, g, 0.001f)
        assertEquals(g, b, 0.001f)
        // The middle stays near the middle (brightened by 6%), white and black clip, a light gray gets lighter.
        assertEquals(127.5f * 1.06f, print(127.5f, 127.5f, 127.5f).first, 0.01f)
        assertEquals(255f, print(255f, 255f, 255f).first, 0.01f)
        assertEquals(0f, print(0f, 0f, 0f).first, 0.01f)
        assertEquals(1.06f * (1.45f * 180f + 127.5f * (1 - 1.45f)), print(180f, 180f, 180f).first, 0.01f)
        // Alpha passes through.
        assertEquals(1f, ArtInk.MATRIX[18], 0f)
        assertEquals(20, ArtInk.MATRIX.size)
    }

    @Test fun coverSizeFillsTheBoxWithoutUpscaling() {
        // A wide GIF into a tall panel: the height fills it, the width overflows to be cropped.
        assertEquals(1000 to 500, ArtInk.coverSize(2000, 1000, 400, 500))
        // A small picture stays its own size.
        assertEquals(100 to 80, ArtInk.coverSize(100, 80, 1000, 1000))
        assertEquals(500 to 500, ArtInk.coverSize(1000, 1000, 500, 300))
        assertEquals(1 to 1, ArtInk.coverSize(0, 0, 10, 10))
        assertEquals(1 to 1, ArtInk.coverSize(4000, 3000, 0, 0))
    }
}
