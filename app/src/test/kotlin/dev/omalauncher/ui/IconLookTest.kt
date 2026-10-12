package dev.omalauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.IconTreatment
import dev.omalauncher.theme.pack.PackIcons
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.PlaceholderStyle
import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a pack's `[icons]` becomes what is drawn (PRD AC-4): silhouette or own colors, the color matrix,
 * the shadow and the placeholder, each as a plain value. Console has to come out as no treatment at
 * all, which is what the launcher drew before packs.
 */
class IconLookTest {
    private val console = ConsolePack.pack.icons

    private fun icons(
        treatment: IconTreatment,
        saturation: Float = 1f,
        contrast: Float = 1f,
        shadow: Boolean = false,
    ): PackIcons = console.copy(treatment = treatment, saturation = saturation, contrast = contrast, shadow = shadow)

    private fun shipped(id: String) = PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/$id")), id).pack

    /** [matrix] laid on one color: red, green, blue and alpha, each 0 to 255, not clamped. */
    private fun apply(
        matrix: List<Float>,
        color: List<Float>,
    ) = (0 until 4).map { row -> (0 until 4).sumOf { (matrix[row * 5 + it] * color[it]).toDouble() }.toFloat() + matrix[row * 5 + 4] }

    private fun assertMatrix(
        what: String,
        expected: List<Float>,
        actual: List<Float>?,
    ) {
        assertNotNull(what, actual)
        assertEquals(what, 20, actual!!.size)
        for (i in expected.indices) assertEquals("$what [$i]", expected[i], actual[i], 1e-5f)
    }

    /** Console: the monochrome switch decides, an icon in its own colors has no filter and no shadow, and a missing one is the app's initials. */
    @Test fun consoleDrawsIconsAsItAlwaysDid() {
        val look = iconLook(console)
        assertEquals(IconLook(mono = null, matrix = null, shadow = false, placeholder = PlaceholderStyle.Letter), look)
        assertTrue(look.mono(true))
        assertFalse(look.mono(false))
        for (mono in listOf(true, false)) for (framed in listOf(true, false)) assertFalse(look.shadowed(mono, framed))
        for (label in listOf("Play Store", "Camera", "", "x", "2048", "日本")) assertEquals(initials(label), look.placeholderText(label))
        assertEquals("PS", look.placeholderText("Play Store"))

        // And through the adapter, which is what the composables ask.
        val adapted = PackLook(ConsolePack.pack)
        assertEquals(look, adapted.icons)
        assertNull(adapted.iconFilter)
        assertTrue(adapted.monoIcons(true) && !adapted.monoIcons(false))
        // Its badge is a fill with no outline.
        assertEquals(0f, adapted.badgeLine.value, 0f)
    }

    /**
     * Console's badges are the chains the launcher built in place before: sized, clipped and filled,
     * under an app's initials, under a folder's four icons (a third of its size for a radius) and
     * under the drawer's own entries (in the accent). Modifier elements compare by value.
     */
    @Test fun consolesBadgesAreTheChainsDrawnBefore() {
        ActivePack.choose(PackPick.Console)
        for (corners in Corners.entries) {
            LauncherStyle.corners = corners
            try {
                for (size in listOf(18.dp, 40.dp, 52.dp)) {
                    assertEquals("$corners $size", Modifier.size(size).clip(Pack.shape(size / 2)).background(Pack.badge), Modifier.iconBadge(size))
                    assertEquals("$corners $size folder", Modifier.size(size).clip(Pack.shape(size / 3)).background(Pack.badge), Modifier.iconBadge(size, radius = size / 3))
                    assertEquals("$corners $size entry", Modifier.size(size).clip(Pack.shape(size / 2)).background(Pack.accent), Modifier.iconBadge(size, fill = Pack.accent))
                }
            } finally {
                LauncherStyle.corners = Corners.Edge
            }
        }
    }

    /** Every place that draws an app's icon or its stand-in goes through the pack: folders and the drawer's entries too. */
    @Test fun iconsAreDrawnThroughThePackEverywhere() {
        val ui = moduleFile("src/main/kotlin/dev/omalauncher/ui")
        for (name in listOf("ShortcutTile.kt", "AppDrawer.kt", "Folders.kt")) {
            val code = java.io.File(ui, name).readLines().map { it.trim() }.filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.startsWith("internal fun initials") }
            assertFalse("$name spells a placeholder itself", code.any { "initials(" in it })
            assertFalse("$name filters an icon itself", code.any { "Pack.icon(" in it })
            assertFalse("$name builds a badge itself", code.any { ".background(Pack.badge)" in it })
            assertTrue("$name draws icons with AppIcon", code.any { "AppIcon(" in it })
            assertTrue("$name sits them on iconBadge", code.any { ".iconBadge(" in it })
        }
    }

    /** `user` leaves silhouettes to the switch; the other three decide for themselves, whatever the switch says. */
    @Test fun eachTreatmentSaysWhetherIconsAreSilhouettes() {
        assertNull(iconLook(icons(IconTreatment.User)).mono)
        assertEquals(false, iconLook(icons(IconTreatment.Original)).mono)
        assertEquals(true, iconLook(icons(IconTreatment.Monochrome)).mono)
        assertEquals(false, iconLook(icons(IconTreatment.Grayscale)).mono)
        for (user in listOf(true, false)) {
            assertEquals(user, iconLook(icons(IconTreatment.User)).mono(user))
            assertFalse(iconLook(icons(IconTreatment.Original)).mono(user))
            assertTrue(iconLook(icons(IconTreatment.Monochrome)).mono(user))
            assertFalse(iconLook(icons(IconTreatment.Grayscale)).mono(user))
        }
        // Every treatment the format allows is one of these four.
        assertEquals(4, IconTreatment.entries.size)
    }

    @Test fun theMatrixForEachTreatment() {
        // As they are: no filter at all, for the three treatments that keep an icon's colors or replace them outright.
        for (treatment in listOf(IconTreatment.User, IconTreatment.Original, IconTreatment.Monochrome)) assertNull("$treatment", iconLook(icons(treatment)).matrix)

        // Gray: every channel becomes the same weighted sum, and alpha is left alone.
        val gray = listOf(0.213f, 0.715f, 0.072f, 0f, 0f)
        val alpha = listOf(0f, 0f, 0f, 1f, 0f)
        assertMatrix("grayscale", gray + gray + gray + alpha, iconLook(icons(IconTreatment.Grayscale)).matrix)
        // Gray is gray whatever saturation the pack also gives.
        assertEquals(iconLook(icons(IconTreatment.Grayscale)).matrix, iconLook(icons(IconTreatment.Grayscale, saturation = 1.6f)).matrix)

        // Desaturated a quarter (Instrument): three quarters of the color and a quarter of the gray.
        assertMatrix(
            "saturation 0.75",
            listOf(0.80325f, 0.17875f, 0.018f, 0f, 0f) + listOf(0.05325f, 0.92875f, 0.018f, 0f, 0f) + listOf(0.05325f, 0.17875f, 0.768f, 0f, 0f) + alpha,
            iconLook(icons(IconTreatment.Original, saturation = 0.75f)).matrix,
        )

        // Contrast alone: each channel stretched about mid gray.
        assertMatrix(
            "contrast 1.5",
            listOf(1.5f, 0f, 0f, 0f, -63.75f) + listOf(0f, 1.5f, 0f, 0f, -63.75f) + listOf(0f, 0f, 1.5f, 0f, -63.75f) + alpha,
            iconLook(icons(IconTreatment.Original, contrast = 1.5f)).matrix,
        )

        // Gray and crushed (1-bit).
        val crushed = listOf(0.3195f, 1.0725f, 0.108f, 0f, -63.75f)
        assertMatrix("grayscale, contrast 1.5", crushed + crushed + crushed + alpha, iconLook(icons(IconTreatment.Grayscale, contrast = 1.5f)).matrix)

        // A silhouette is tinted, not filtered, but the matrix is still the pack's for the places that draw the full icon.
        assertNotNull(iconLook(icons(IconTreatment.Monochrome, saturation = 0.5f)).matrix)
    }

    /** What the matrix does to a color: gray stays gray, mid gray stays put, and alpha is never touched. */
    @Test fun theMatrixKeepsGrayGrayAndAlphaAlone() {
        for (saturation in listOf(0f, 0.5f, 0.75f, 1f, 1.5f, 2f)) {
            for (contrast in listOf(0.5f, 1f, 1.5f, 2f)) {
                val matrix = iconMatrix(saturation, contrast) ?: continue
                val what = "saturation $saturation, contrast $contrast"
                val mid = apply(matrix, listOf(127.5f, 127.5f, 127.5f, 255f))
                for (channel in 0 until 3) assertEquals(what, 127.5f, mid[channel], 0.01f)
                assertEquals(what, 255f, mid[3], 0f)
                val light = apply(matrix, listOf(200f, 200f, 200f, 128f))
                assertEquals(what, light[0], light[1], 0.01f)
                assertEquals(what, light[1], light[2], 0.01f)
                assertEquals(what, 127.5f + (200f - 127.5f) * contrast, light[0], 0.02f)
                assertEquals(what, 128f, light[3], 0f)
            }
        }
        // With no saturation a pure red is as bright as its share of the gray.
        val red = apply(iconMatrix(0f, 1f)!!, listOf(255f, 0f, 0f, 255f))
        for (channel in 0 until 3) assertEquals(54.315f, red[channel], 0.001f)
        assertEquals(255f, red[3], 0f)
    }

    /** The matrix is the one the adapter built with Compose before it was a plain value: saturation, then contrast about mid gray. */
    @Test fun theMatrixIsTheOneDrawnBefore() {
        for (saturation in listOf(0f, 0.25f, 0.75f, 1f, 1.3f, 2f)) {
            for (contrast in listOf(0.5f, 0.8f, 1f, 1.5f, 2f)) {
                val ours = iconMatrix(saturation, contrast)
                if (saturation == 1f && contrast == 1f) {
                    assertNull(ours)
                    continue
                }
                val before = ColorMatrix().apply { setToSaturation(saturation) }
                if (contrast != 1f) {
                    val shift = 127.5f * (1f - contrast)
                    before.timesAssign(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, shift, 0f, contrast, 0f, 0f, shift, 0f, 0f, contrast, 0f, shift, 0f, 0f, 0f, 1f, 0f)))
                }
                for (i in 0 until 20) assertEquals("saturation $saturation, contrast $contrast [$i]", before.values[i], ours!![i], 1e-4f)
            }
        }
    }

    /** The shadow goes under an icon in its own colors that stands alone, in a pack that asks for it. */
    @Test fun theShadowIsOnlyUnderAFullColorIconWithNoFrame() {
        val with = iconLook(icons(IconTreatment.Original, shadow = true))
        assertTrue(with.shadowed(mono = false, framed = false))
        assertFalse(with.shadowed(mono = true, framed = false))
        assertFalse(with.shadowed(mono = false, framed = true))
        assertFalse(with.shadowed(mono = true, framed = true))
        val without = iconLook(icons(IconTreatment.Original))
        assertFalse(without.shadowed(mono = false, framed = false))
        assertEquals(2f, IconLook.SHADOW_DROP, 0f)
        assertEquals(0.25f, IconLook.SHADOW_ALPHA, 0f)
    }

    @Test fun thePlaceholderIsALetterAMarkOrNothing() {
        fun look(style: PlaceholderStyle) = iconLook(console.copy(placeholder = console.placeholder.copy(style = style)))
        assertEquals("PS", look(PlaceholderStyle.Letter).placeholderText("Play Store"))
        assertEquals("Ca", look(PlaceholderStyle.Letter).placeholderText("Camera"))
        // One mark for every app.
        assertEquals(look(PlaceholderStyle.Glyph).placeholderText("Play Store"), look(PlaceholderStyle.Glyph).placeholderText("Camera"))
        assertEquals(1, look(PlaceholderStyle.Glyph).placeholderText("Camera").length)
        assertEquals("", look(PlaceholderStyle.Blank).placeholderText("Camera"))
        assertEquals(3, PlaceholderStyle.entries.size)
    }

    /** The treatment each shipped look has, as the prototype has it: it draws a drop shadow under a real icon in every look. */
    @Test fun theShippedPacksTreatIconsAsThePrototypeDoes() {
        assertEquals(IconLook(false, null, true, PlaceholderStyle.Letter), iconLook(shipped("riso").icons))
        assertEquals(iconLook(icons(IconTreatment.Original, saturation = 0.75f, shadow = true)), iconLook(shipped("instrument").icons))
        assertEquals(iconLook(icons(IconTreatment.Grayscale, contrast = 1.5f, shadow = true)), iconLook(shipped("one-bit").icons))
        for (id in listOf("city-pop", "custard", "tiny-magazine", "bujo", "dial-up")) {
            val pack = shipped(id)
            assertEquals(id, IconLook(mono = false, matrix = null, shadow = true, placeholder = PlaceholderStyle.Letter), iconLook(pack.icons))
            val look = PackLook(pack)
            assertEquals(id, iconLook(pack.icons), look.icons)
            assertNull(id, look.iconFilter)
            // Neither state of the monochrome switch turns them into silhouettes.
            assertFalse(id, look.monoIcons(true) || look.monoIcons(false))
        }
        // The packs that outline their placeholder have the line reach the badge.
        assertEquals(1.5f, PackLook(shipped("bujo")).badgeLine.value, 0f)
        assertEquals(2f, PackLook(shipped("dial-up")).badgeLine.value, 0f)
        assertEquals(2f, PackLook(shipped("one-bit")).badgeLine.value, 0f)
        assertNotNull(PackLook(shipped("one-bit")).iconFilter)
        assertNotNull(PackLook(shipped("instrument")).iconFilter)
    }
}
