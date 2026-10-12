package dev.omalauncher.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.theme.ThemeColors
import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.PackRegistry
import dev.omalauncher.theme.pack.moduleFile
import dev.omalauncher.theme.pack.scrapTilt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The modifiers ScrapFrame builds. Under Console they have to be the chains the launcher built before
 * packs, element for element; under any pack they may draw anything but never lay out or turn a layer,
 * so a tile is touched on its cell whatever it looks like.
 */
class ScrapFrameTest {
    @After fun backToConsole() {
        ActivePack.choose(PackPick.Console)
        Palette.apply(emptyMap())
        LauncherStyle.corners = Corners.Edge
        LauncherStyle.bareAppIcons = false
    }

    private fun use(id: String) = ActivePack.choose(PackRegistry.choose(id) { DirectoryPackSource(moduleFile("src/main/assets/packs/$id")) })

    private fun Modifier.elements(): List<Modifier.Element> = foldIn(emptyList()) { all, element -> all + element }

    /** Widget keys of both parities: about half of a pack's dressed scraps are taped, by the key's hash. */
    private val widgets = (0 until 8).map { "widget-$it.owl" }

    /**
     * Console's chains are the old `tile()` and `frame()` chains in each of the 22 Omarchy themes and 3
     * corner styles. Compose's modifier elements compare by value, so a border a dp wider, a focus line
     * of another width, an outline where the dock bar had none, or a tape Console doesn't have fails here.
     */
    @Test fun consolesChainsAreTheOldOnesInEveryThemeAndCornerStyle() {
        assertTrue(widgets.any { it.hashCode() and 1 == 0 } && widgets.any { it.hashCode() and 1 == 1 })
        val themes = moduleFile("src/main/assets/themes").listFiles().orEmpty().filter { it.extension == "toml" }
        var checked = 0
        for (file in themes) {
            Palette.apply(ThemeColors.resolve(ThemeColors.parse(file.readText())))
            for (corners in Corners.entries) {
                LauncherStyle.corners = corners
                val what = "${file.name} $corners"
                for (key in widgets) {
                    // A widget tile: dressed, with whatever tape and title bar the pack has. Console has neither, and no tilt.
                    assertEquals("$what $key", Modifier.tile(false), Modifier.scrap(false, key, dressed = true))
                    assertEquals("$what $key", Modifier.tile(true), Modifier.scrap(true, key, dressed = true))
                }
                assertSame(what, Modifier, Modifier.scrapContent())
                assertEquals(what, Modifier.tile(false), Modifier.scrap(false, "app:x"))
                assertEquals(what, Modifier.tile(true), Modifier.scrap(true, "app:y"))
                assertEquals(what, Modifier.tile(false), Modifier.scrap())
                assertEquals(what, Modifier.tile(false), Modifier.tileFrame(false, "k"))
                assertEquals(what, Modifier.tile(true), Modifier.tileFrame(true, "k"))
                assertEquals(what, Modifier.tile(false), Modifier.dockBar())
                assertEquals(what, Modifier.tile(true), Modifier.dockBar(true))
                assertEquals(what, Modifier.frame(2.dp, Color.Transparent, Palette.glass), Modifier.dockBarFill())
                assertSame(what, Modifier, Modifier.dockButton(0))
                assertSame(what, Modifier, Modifier.dockButton(1))
                assertEquals(what, Modifier.frame(2.dp, Palette.accent), Modifier.scrapFocus())
                assertEquals(what, Modifier.frame(2.dp, Palette.accent, Palette.background), Modifier.panel())
                assertEquals(what, Modifier.frame(2.dp, Palette.accent, Palette.background, radius = 20.dp), Modifier.panel(20.dp))
                assertEquals(what, Modifier.frame(1.dp, Palette.border, Palette.surface), Modifier.framed(Pack.hair, Pack.border, Pack.surface))
                assertEquals(what, Modifier.frame(1.dp, Palette.border.copy(alpha = 0.5f)), Modifier.framed(Pack.hair, Pack.pageLine.copy(alpha = 0.5f)))
                assertEquals(what, Modifier.frame(2.dp, Palette.accent, Palette.accent.copy(alpha = 0.12f)), Modifier.framed(Pack.mark, Pack.accent, Pack.accent.copy(alpha = 0.12f)))
                assertEquals(what, Modifier.frame(1.dp, Palette.accent, radius = 8.dp), Modifier.framed(Pack.hair, Pack.accent, radius = 8.dp))
                assertEquals(what, Modifier.frame(2.dp, Palette.border, Palette.surface, radius = 12.dp), Modifier.framed(Pack.mark, Pack.border, Pack.surface, radius = 12.dp))
                LauncherStyle.bareAppIcons = true
                assertSame(what, Modifier, Modifier.tileFrame(false, "k"))
                assertEquals(what, Modifier.frame(2.dp, Palette.accent), Modifier.tileFrame(true, "k"))
                LauncherStyle.bareAppIcons = false
                checked++
            }
        }
        assertEquals(22 * 3, checked)
    }

    /**
     * PRD 6.5: the hit rectangle is the cell whatever the tilt. A tile's touch area is its layout, and
     * `scrap()`, which every tile on home is dressed by, adds nothing to a layout in any shipped pack:
     * a tilted, taped, shadowed or title-barred scrap is one drawing element and nothing else. No
     * padding, no offset, no size, and no layer, which would turn where it is touched as well.
     */
    @Test fun aScrapIsOnlyEverDrawnSoItsHitRectangleIsItsCell() {
        val keys = widgets + listOf("app:com.example/.Main", "folder:1")
        var tilted = 0
        for (id in listOf("riso", "instrument", "one-bit")) {
            use(id)
            assertEquals(id, ActivePack.pack.id)
            val scrap = ActivePack.pack.scrap
            val chains =
                keys.flatMap { key ->
                    if (scrapTilt(key, scrap.tiltMin, scrap.tiltMax) != 0f) tilted++
                    listOf(true, false).flatMap { focused -> listOf(true, false).map { dressed -> "$id scrap $key" to Modifier.scrap(focused, key, dressed) } }
                } + (0 until 4).map { "$id dock button $it" to Modifier.dockButton(it) }
            for ((what, chain) in chains) {
                val elements = chain.elements()
                assertEquals("$what: ${elements.map { it::class.simpleName }}", 1, elements.size)
                val name = elements.single()::class.simpleName.orEmpty()
                assertTrue("$what is a $name", "Draw" in name)
                assertFalse("$what lays out", elements.single() is LayoutModifier)
                assertFalse("$what is a $name", listOf("Layer", "Padding", "Offset", "Size", "Layout").any { it in name })
            }
        }
        // Riso really does tilt, so the above was said of turned scraps too.
        assertTrue("$tilted tilted", tilted >= 8)
    }

    /** The source says the same: the tilt is a canvas rotation while drawing, never a layer's. */
    @Test fun tiltNeverTurnsALayer() {
        val source = moduleFile("src/main/kotlin/dev/omalauncher/ui/ScrapFrame.kt").readText()
        assertFalse("graphicsLayer" in source)
        assertFalse("rotationZ" in source)
        assertTrue("rotate(tilt)" in source)
    }

    /**
     * A title bar takes room from what is inside the tile, never from the tile: under 1-bit the inset is
     * `scrapContent()`'s alone, and the widget tile puts it after its tap target, so the bar is still the
     * tile's to tap. Long-press, drag and resize are the grid's, by cell, and see no modifier at all.
     */
    @Test fun aTitleBarInsetsTheContentAndNotTheTapTarget() {
        use("one-bit")
        val bar = ActivePack.pack.scrap.titleBar
        assertEquals(18f, bar.height, 0f)
        assertEquals(Modifier.padding(top = 18.dp), Modifier.scrapContent())
        for (key in widgets) assertFalse(Modifier.scrap(false, key, dressed = true).elements().any { it is LayoutModifier })
        for (id in listOf("riso", "instrument")) {
            use(id)
            assertSame(id, Modifier, Modifier.scrapContent())
        }
        val tile = moduleFile("src/main/kotlin/dev/omalauncher/ui/WidgetTile.kt").readText()
        val frame = tile.indexOf(".scrap(focused")
        val tap = tile.indexOf(".pointerInput(spec)")
        val inset = tile.indexOf(".scrapContent()")
        assertTrue("scrap $frame, tap $tap, inset $inset", frame in 0 until tap && tap < inset)
        assertEquals("the widget tile insets its content once", inset, tile.lastIndexOf(".scrapContent()"))
        // Nothing else on home pads for a title bar: only widget tiles are dressed with one.
        val others = moduleFile("src/main/kotlin/dev/omalauncher/ui").listFiles().orEmpty().filter { it.name !in setOf("WidgetTile.kt", "ScrapFrame.kt") && ".scrapContent()" in it.readText() }
        assertEquals(emptyList<String>(), others.map { it.name })
    }
}
