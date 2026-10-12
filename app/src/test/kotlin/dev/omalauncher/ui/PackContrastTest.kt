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
        // City Pop lays a wallpaper over its page color; the contrast is measured against that opaque color, which shows until the image decodes.
        assertEquals("${pack.id}: these looks draw their own page", if (pack.id == "city-pop") PageKind.Wallpaper else PageKind.Paper, pack.page.kind)
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

        // Outlines. The focus line that replaces one has its own rule, below, for every look.
        fun frame(
            name: String,
            surface: PackSurface,
            on: Color,
        ) {
            if (surface.border <= 0f) return
            val fill = surface.fill.toColor().compositeOver(on)
            large("$name outline on its fill", surface.borderColor.toColor(), fill)
            large("$name outline on what is around it", surface.borderColor.toColor(), on)
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

    /**
     * Which tile is focused has to show, in every look and with framed or bare icons. The focus line
     * stands 3 to 1 off the fill it borders; a bare icon's, which is drawn straight on the page, stands
     * 3 to 1 off the page; and it is told from the outline it replaces by color (3 to 1 again) or by
     * weight (half as wide again, at least). A surface sits on the page, so its line has to show from
     * outside too: its color stands 3 to 1 off the page, or the line reaches outside the edge, where
     * it is in the page's focus color, which does. The page is measured as its paper color: 1-bit's
     * dither and the other patterns are not.
     */
    @Test fun focusShowsInEveryLook() {
        val failed = ArrayList<String>()
        for (id in checked + earlier) {
            val pack = look(id).pack
            val page = pack.page.color.toColor()
            println("focus, $id:")
            val bare = ratio(pack.page.focusColor.toColor(), page)
            println("  %-18s %s  %.0fdp  on the page %.2f".format("bare icon", pack.page.focusColor, focusWidth(pack.scrap.surface), bare))
            if (bare < LARGE) failed += "$id: a bare icon's focus is %.2f to 1 on the page".format(bare)
            for ((name, surface) in listOf("scrap" to pack.scrap.surface, "scrap.alt" to pack.scrap.alt, "dock" to pack.dock.surface)) {
                val fill = surface.fill.toColor().compositeOver(page)
                val focus = surface.focusColor.toColor()
                val width = focusWidth(surface)
                val onFill = ratio(focus, fill)
                val outlined = surface.border > 0f
                val offOutline = if (outlined) ratio(focus, surface.borderColor.toColor().compositeOver(fill)) else null
                val wider = if (outlined) width / surface.border else null
                val onPage = ratio(focus, page)
                val outside = focusOutside(surface)
                println(
                    "  %-18s %s  %.0fdp (%.0f outside)  on its fill %.2f  on the page %.2f  against its outline %s".format(
                        name,
                        surface.focusColor,
                        width,
                        outside,
                        onFill,
                        onPage,
                        if (outlined) "%.2f, %.1f times as wide".format(offOutline, wider) else "(none)",
                    ),
                )
                if (onFill < LARGE) failed += "$id $name: focus is %.2f to 1 on its fill".format(onFill)
                if (outlined && offOutline!! < LARGE && wider!! < 1.5f) failed += "$id $name: focus is %.2f to 1 against its outline and only %.1f times as wide".format(offOutline, wider)
                if (onPage < LARGE && outside <= 0f) failed += "$id $name: focus is %.2f to 1 on the page and no part of it is outside the edge".format(onPage)
                // What is inside never covers more of a tile than the outline or the launcher's mark did.
                assertEquals("$id $name", maxOf(surface.border, PackLook.MIN_MARK), focusInside(surface), 0f)
                assertEquals("$id $name", width, focusInside(surface) + outside, 0f)
                // Never thinner than the launcher's own mark.
                assertTrue("$id $name", width >= PackLook.MIN_MARK)
            }
        }
        assertEquals(emptyList<String>(), failed)
    }

    /** Console's focus is as wide as it always was, and a bare icon's is the scrap's own color. */
    @Test fun consolesFocusIsUnchanged() {
        val console = dev.omalauncher.theme.pack.ConsolePack.pack
        for (surface in listOf(console.scrap.surface, console.scrap.alt, console.dock.surface, console.icons.placeholder.surface)) {
            assertEquals(2f, surface.focusWidth, 0f)
            assertEquals(2f, focusWidth(surface), 0f)
        }
        assertEquals(console.scrap.surface.focusColor, console.page.focusColor)
        assertEquals(console.palette.accent, console.page.focusColor)
        // A pack's width only ever adds weight: an outline wider than it stays as wide.
        assertEquals(5f, focusWidth(console.scrap.surface.copy(border = 5f)), 0f)
        assertEquals(4f, focusWidth(console.scrap.surface.copy(focusWidth = 4f)), 0f)
        // Console's line is all inside the edge, as it was; a heavier one grows outward only.
        for (surface in listOf(console.scrap.surface, console.scrap.alt, console.dock.surface)) assertEquals(0f, focusOutside(surface), 0f)
        assertEquals(2f, focusInside(console.scrap.surface.copy(focusWidth = 4f)), 0f)
        assertEquals(2f, focusOutside(console.scrap.surface.copy(focusWidth = 4f)), 0f)
        assertEquals(0f, focusOutside(console.scrap.surface.copy(border = 5f, focusWidth = 4f)), 0f)
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
