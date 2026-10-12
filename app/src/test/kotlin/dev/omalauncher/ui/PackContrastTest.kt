package dev.omalauncher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.omalauncher.theme.pack.Dressing
import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.PackLoader
import dev.omalauncher.theme.pack.PackSurface
import dev.omalauncher.theme.pack.PageKind
import dev.omalauncher.theme.pack.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Whether a shipped look can be read: the contrast ratio (WCAG 2's, 1 to 21) of every pair of colors
 * the adapter draws one on the other. Text read at length needs 4.5 to 1; big marks, glyphs, outlines
 * and the focus line need 3. A color with some alpha is laid over what it is drawn on first, so
 * "foreground@0.65" is measured as it looks, not as it is written.
 */
class PackContrastTest {
    /** The five looks added after Riso, Instrument and 1-bit: each has to pass whole. */
    private val checked = listOf("city-pop", "custard", "tiny-magazine", "bujo", "dial-up")

    /** The three before them, measured the same way and printed, but not held to it here: their manifests are another slice's. */
    private val earlier = listOf("riso", "instrument", "one-bit")

    private fun look(id: String) = PackLook(PackLoader.load(DirectoryPackSource(moduleFile("src/main/assets/packs/$id")), id).pack)

    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** [ink] on [ground], which must be opaque by now. */
    private fun ratio(
        ink: Color,
        ground: Color,
    ): Double {
        assertEquals("the ground has to be opaque to be measured against", 1f, ground.alpha, 0f)
        val a = luminance(ink.compositeOver(ground))
        val b = luminance(ground)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private class Check(
        val what: String,
        val ink: Color,
        val ground: Color,
        val needs: Double,
    )

    private companion object {
        const val TEXT = 4.5
        const val LARGE = 3.0
    }

    /** Every pair [look] draws, with what each has to reach. */
    private fun pairs(look: PackLook): List<Check> {
        val pack = look.pack
        val page = pack.page.color.toColor()
        assertEquals("${pack.id}: these looks draw their own page", PageKind.Paper, pack.page.kind)
        assertEquals("${pack.id} page", 1f, page.alpha, 0f)
        val background = look.background.compositeOver(page)
        val surface = look.surface.compositeOver(background)
        val out = ArrayList<Check>()

        fun text(
            what: String,
            ink: Color,
            ground: Color,
        ) = out.add(Check(what, ink, ground, TEXT))

        fun large(
            what: String,
            ink: Color,
            ground: Color,
        ) = out.add(Check(what, ink, ground, LARGE))

        // Panels, the drawer and the Oma chat: Pack.body in each text color, on the background and on a field or card.
        for ((name, ground) in listOf("the background" to background, "the surface" to surface)) {
            text("foreground on $name", look.foreground, ground)
            text("muted on $name", look.muted, ground)
            text("accent on $name", look.accent, ground)
            text("red on $name", look.red, ground)
            text("green on $name", look.green, ground)
            large("the border around $name", look.border, ground)
        }
        text("foreground over the scrim", look.foreground, look.overlay.compositeOver(page))
        text("foreground on glass", look.foreground, look.glass.compositeOver(page))
        // Glyphs on a filled badge or button: the drawer's own entries, and the mic while it listens.
        large("a glyph on the accent", look.background, look.accent.compositeOver(background))
        large("a glyph on red", look.background, look.red.compositeOver(background))

        // Scraps.
        val scrap = pack.scrap.surface.fill.toColor().compositeOver(page)
        text("scrap text", look.scrapInk, scrap)
        text("scrap captions", look.scrapMuted, scrap)
        val alt = pack.scrap.alt.fill.toColor().compositeOver(page)
        text("text on an emphasised scrap", pack.scrap.alt.ink.toColor(), alt)
        if (pack.type.displayHighlighter) text("a heading on its highlighter", look.scrapInk, look.highlight.compositeOver(scrap))
        val bar = pack.scrap.titleBar
        if (bar.style != Dressing.None) large("the title bar's mark", bar.ink.toColor(), bar.fill.toColor().compositeOver(scrap))
        val sticky = pack.scrap.sticky
        text("a sticky note", sticky.ink.toColor(), sticky.fill.toColor().compositeOver(page))

        // Straight on the page: names under bare icons and the page's name, quiet text, guide lines and the pen.
        val under = if (look.plate.alpha > 0f) look.plate.compositeOver(page) else page
        text("page text", look.onPage, under)
        large("quiet page text", look.pageMuted, page)
        large("the pen", pack.ink.pen.toColor(), page)

        // The dock's buttons, and what stands in for an icon.
        val dock = pack.dock.surface.fill.toColor().compositeOver(page)
        text("the dock's ink", pack.dock.surface.ink.toColor(), dock)
        val badge = look.badge.compositeOver(background)
        text("a placeholder's letter", look.letter, badge)
        large("a silhouette on its badge", look.tint, badge)
        large("a glyph in the tint", look.tint, background)

        // Outlines, and the focus line that replaces one. Where a surface has no outline the focus line is drawn on its fill.
        fun frame(
            name: String,
            surface: PackSurface,
            on: Color,
        ) {
            val fill = surface.fill.toColor().compositeOver(on)
            val focus = surface.focusColor.toColor()
            if (surface.border > 0f) {
                large("$name outline on its fill", surface.borderColor.toColor(), fill)
                large("$name outline on what is around it", surface.borderColor.toColor(), on)
                large("$name focus against its outline", focus, surface.borderColor.toColor().compositeOver(fill))
            } else {
                large("$name focus on its fill", focus, fill)
            }
        }
        frame("scrap", pack.scrap.surface, page)
        frame("emphasised scrap", pack.scrap.alt, page)
        frame("dock", pack.dock.surface, page)
        // A panel is the background outlined in the emphasised scrap's line, a hairline at least.
        large("a panel's outline", pack.scrap.alt.borderColor.toColor(), background)
        if (look.badgeLine.value > 0f) large("a placeholder's outline", look.badgeEdge, badge)

        // The ring is two colors chasing each other: one of them has to show on the page.
        for ((state, ring) in listOf("listening" to pack.ring.listening, "working" to pack.ring.working, "needs you" to pack.ring.needsYou, "done" to pack.ring.done)) {
            val best = listOf(ring.color1, ring.color2).map { it.toColor() }.maxBy { ratio(it, page) }
            large("the ring, $state", best, page)
        }
        return out
    }

    private fun report(id: String): List<String> {
        val pairs = pairs(look(id))
        println("contrast, $id:")
        val failed = ArrayList<String>()
        for (pair in pairs) {
            val ratio = ratio(pair.ink, pair.ground)
            val ok = ratio >= pair.needs
            println("  %-40s %5.2f  (needs %.1f)%s".format(pair.what, ratio, pair.needs, if (ok) "" else "  FAILS"))
            if (!ok) failed += "%s is %.2f to 1, needs %.1f".format(pair.what, ratio, pair.needs)
        }
        return failed
    }

    @Test fun everyNewLookCanBeRead() {
        for (id in checked) assertEquals("$id", emptyList<String>(), report(id))
    }

    /** A focus line the color of what it is drawn on, or of the outline it replaces, shows nothing. */
    @Test fun theFocusLineIsNeverTheOutlinesColorOrTheFills() {
        for (id in checked) {
            val pack = look(id).pack
            for ((name, surface) in listOf("scrap" to pack.scrap.surface, "scrap.alt" to pack.scrap.alt, "dock" to pack.dock.surface)) {
                assertTrue("$id $name focus is its fill", surface.focusColor != surface.fill)
                if (surface.border > 0f) assertTrue("$id $name focus is its outline", surface.focusColor != surface.borderColor)
            }
        }
    }

    /** The measure itself, against known answers. */
    @Test fun measuresContrastAsWcagDoes() {
        assertEquals(21.0, ratio(Color.Black, Color.White), 0.001)
        assertEquals(1.0, ratio(Color.White, Color.White), 0.001)
        // #767676 is the lightest gray that is 4.5 to 1 on white.
        assertEquals(4.54, ratio(Color(0xFF767676), Color.White), 0.01)
        // Half-clear black on white is mid gray, not black.
        assertEquals(ratio(Color(0xFF7F7F7F), Color.White), ratio(Color.Black.copy(alpha = 128 / 255f), Color.White), 0.02)
    }

    /** Not held to it: this only prints where the three earlier looks stand, for whoever owns them. */
    @Test fun printsWhereTheEarlierLooksStand() {
        for (id in earlier) {
            val failed = report(id)
            println("contrast, $id: ${failed.size} below the line" + failed.joinToString("") { "\n    $it" })
        }
    }
}
