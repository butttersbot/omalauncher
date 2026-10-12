package dev.omalauncher.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.omalauncher.theme.ThemeColors
import dev.omalauncher.theme.pack.ConsolePack
import dev.omalauncher.theme.pack.DirectoryPackSource
import dev.omalauncher.theme.pack.DockStyle
import dev.omalauncher.theme.pack.PackPick
import dev.omalauncher.theme.pack.PackRegistry
import dev.omalauncher.theme.pack.TypeRole
import dev.omalauncher.theme.pack.consoleManifest
import dev.omalauncher.theme.pack.moduleFile
import dev.omalauncher.theme.pack.packOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adapter from a pack's numbers to what composables draw with, and the holder of the pack in use.
 * The golden values here are what the launcher drew before packs: Console through the adapter has to
 * come out as exactly those, in every Omarchy theme.
 */
class PackAdapterTest {
    @After fun backToConsole() {
        ActivePack.choose(PackPick.Console)
        Palette.apply(emptyMap())
        LauncherStyle.corners = Corners.Edge
        LauncherStyle.monochromeIcons = false
    }

    /** Every Omarchy theme bundled with the app, by name. */
    private fun themes(): Map<String, Map<String, String>> =
        moduleFile("src/main/assets/themes").listFiles().orEmpty().filter { it.extension == "toml" }.sortedBy { it.name }.associate {
            it.nameWithoutExtension to ThemeColors.resolve(ThemeColors.parse(it.readText()))
        }

    /** What the old accessors gave for [palette], against what the adapter gives for [look]. */
    private fun assertTodaysTile(
        palette: ThemePalette,
        look: PackLook,
        what: String,
    ) {
        // Palette's colors.
        assertEquals("$what background", palette.background, look.background)
        assertEquals("$what surface", palette.surface, look.surface)
        assertEquals("$what overlay", palette.overlay, look.overlay)
        assertEquals("$what foreground", palette.foreground, look.foreground)
        assertEquals("$what muted", palette.muted, look.muted)
        assertEquals("$what accent", palette.accent, look.accent)
        assertEquals("$what border", palette.border, look.border)
        assertEquals("$what red", palette.red, look.red)
        assertEquals("$what green", palette.green, look.green)
        assertEquals("$what glass", palette.background.copy(alpha = 0.88f), look.glass)

        // Modifier.tile(): frame(2.dp, border or accent, glass); the dock bar and rails were tiles too.
        for (surface in listOf(look.pack.scrap.surface, look.pack.dock.surface)) {
            assertEquals("$what fill", palette.background.copy(alpha = 0.88f), surface.fill.toColor())
            assertEquals("$what line", 2.dp, surface.border.dp)
            assertEquals("$what outline", palette.border, surface.borderColor.toColor())
            assertEquals("$what focus", palette.accent, surface.focusColor.toColor())
            assertTrue("$what shadows", !surface.shadow.visible && !surface.shadow2.visible)
        }
        assertEquals(2.dp, look.line)
        assertEquals(2.dp, look.mark)
        assertEquals(1.dp, look.hair)
        assertEquals(0f, look.pack.scrap.tiltMin, 0f)
        assertEquals(0f, look.pack.scrap.tiltMax, 0f)
        // Panels: frame(2.dp, accent, background).
        assertEquals(2f, look.pack.scrap.alt.border, 0f)
        assertEquals(palette.accent, look.pack.scrap.alt.borderColor.toColor())
        assertEquals(palette.foreground, look.scrapInk)
        assertEquals(palette.muted, look.scrapMuted)
        // Silhouettes and initials were the accent color, on the surface color.
        assertEquals(palette.accent, look.tint)
        assertEquals(palette.accent, look.letter)
        assertEquals(palette.surface, look.badge)
        assertNull(look.iconFilter)

        // Mono, in each of the pack's four voices, and TextSize.
        for (role in TypeRole.entries) {
            assertEquals("$what $role", palette.mono, look.style(role))
            assertEquals("$what $role", palette.mono.copy(color = palette.muted, fontSize = TextSize.Small), look.style(role, look.sizes.small, look.muted))
            assertEquals("abc", look.cased(role, "abc"))
        }
        assertEquals(TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = palette.foreground), look.style(TypeRole.Body))
        assertEquals(listOf(TextSize.Small, TextSize.Body, TextSize.Item, TextSize.Large, TextSize.Title), look.sizes.run { listOf(small, body, item, large, title) })
        assertEquals(listOf(12.sp, 14.sp, 15.sp, 16.sp, 18.sp), look.sizes.run { listOf(small, body, item, large, title) })

        // HomeGap.
        assertEquals(HomeGap, PageGap)
        assertEquals(6.dp, look.pack.shape.gap.dp)
        assertEquals(PageGap, look.pack.shape.gap.dp)

        // LauncherStyle.shape(radius), per corner style, at the default radius and at callers' own.
        assertEquals(16.dp, look.radius)
        for (corners in Corners.entries) {
            for (radius in listOf(16.dp, 8.dp, 12.dp, 20.dp, 24.dp, 26.dp)) {
                val before = if (corners == Corners.Edge) RectangleShape else RoundedCornerShape(radius * corners.scale)
                assertEquals("$what $corners $radius", before, look.shape(radius, corners.scale))
            }
        }
        assertEquals(RectangleShape, look.shape(16.dp, Corners.Edge.scale))
        assertEquals(RoundedCornerShape(8.dp), look.shape(16.dp, Corners.Soft.scale))
        assertEquals(RoundedCornerShape(16.dp), look.shape(16.dp, Corners.Round.scale))
        // The agent strip was round whatever the corner style.
        assertEquals(RoundedCornerShape(10.dp), look.round(10.dp))

        // A bare icon's name on the wallpaper, and the page's name over it.
        assertEquals(
            palette.mono.copy(fontSize = TextSize.Small, color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 6f)),
            look.pageLabel(look.sizes.small),
        )
        assertEquals(palette.mono.copy(color = palette.muted), look.folio)
        assertEquals(palette.background.copy(alpha = 0.6f), look.folioPlate)
        assertEquals(Color.Transparent, look.plate)
        assertEquals(palette.isLight, look.panelsLight)
        assertTrue(!look.ownsPage)
        assertEquals(DockStyle.Bar, look.pack.dock.style)
        // The monochrome switch in settings decides.
        assertTrue(look.monoIcons(true) && !look.monoIcons(false))
    }

    @Test fun consoleIsTodaysTileBeforeAnyThemeIsApplied() {
        assertTodaysTile(ThemePalette(emptyMap()), PackLook(ConsolePack.pack), "console")
        assertTodaysTile(Palette.current, ActivePack.look, "active")
        assertSame(Palette.theme, Palette.current)
    }

    /** PRD AC-21: Console with each of the Omarchy themes is what that theme drew before packs. */
    @Test fun consoleIsTodaysTileInEveryOmarchyTheme() {
        val themes = themes()
        assertTrue("found ${themes.size} themes", themes.size >= 22)
        for ((name, colors) in themes) {
            Palette.apply(colors)
            assertEquals(ConsolePack.ID, ActivePack.pack.id)
            assertTodaysTile(ThemePalette(colors), ActivePack.look, name)
            assertSame(name, Palette.theme, Palette.current)
            // The same pack until the theme changes, not one per read.
            assertSame(ActivePack.look, ActivePack.look)
        }
    }

    @Test fun theAccessorsAreTheActivePacks() {
        Palette.apply(themes().getValue("gruvbox"))
        val palette = Palette.current
        assertEquals(palette.accent, Pack.accent)
        assertEquals(palette.mono, Pack.body())
        assertEquals(palette.mono.copy(color = palette.accent, fontSize = TextSize.Title), Pack.display(color = Pack.accent))
        assertEquals(palette.mono.copy(color = palette.red, fontSize = TextSize.Small), Pack.mono(Pack.size.small, Pack.red))
        assertEquals(palette.mono.copy(color = palette.accent, fontSize = 20.sp), Pack.placeholder(20.sp))
        assertEquals(palette.mono.copy(color = palette.red, fontSize = TextSize.Title, fontFamily = NerdSymbols), Pack.symbol(Pack.size.title, Pack.red))
        // The band's strut measures as the old text did: family and size are all that set a line's height.
        assertEquals(palette.mono.fontFamily, Pack.strut(Pack.size.item).fontFamily)
        assertEquals(TextSize.Item, Pack.strut(Pack.size.item).fontSize)
        for (corners in Corners.entries) {
            LauncherStyle.corners = corners
            assertEquals(LauncherStyle.shape(), Pack.shape())
            assertEquals(LauncherStyle.shape(12.dp), Pack.shape(12.dp))
        }
        LauncherStyle.monochromeIcons = true
        assertTrue(Pack.monoIcons)
        assertNotNull(Pack.icon(mono = true))
        assertNull(Pack.icon(mono = false))
    }

    /** PRD UP-10: a pack that can't be loaded leaves Console drawing, in the theme's colors. */
    @Test fun aBrokenPackDrawsConsole() {
        val colors = themes().getValue("nord")
        Palette.apply(colors)
        ActivePack.choose(PackRegistry.choose("broken") { packOf("[pack]\nname = \"No format\"\n") })
        assertEquals("broken", ActivePack.pick.id)
        assertNotNull(ActivePack.pick.error)
        assertEquals(ConsolePack.ID, ActivePack.pack.id)
        assertTodaysTile(ThemePalette(colors), ActivePack.look, "broken")
        assertSame(Palette.theme, Palette.current)
    }

    /** A pack other than Console draws in its own colors and type; the theme stays applied underneath. */
    @Test fun anotherPackKeepsItsOwnLookWhateverTheTheme() {
        val themes = themes()
        Palette.apply(themes.getValue("nord"))
        val nord = Palette.theme
        ActivePack.choose(PackRegistry.choose("dial-up") { DirectoryPackSource(moduleFile("src/test/resources/packs/dial-up")) })
        assertEquals("dial-up", ActivePack.pack.id)
        val paper = Color(0xFFF7F3EE)
        val ink = Color(0xFF4A5590)
        assertEquals(paper, Pack.background)
        assertEquals(ink, Pack.foreground)
        assertTrue(Pack.ownsPage)
        // What still reads Palette (widgets, settings) gets the pack's colors, so it can be read on its scraps.
        assertEquals(paper, Palette.background)
        assertEquals(ink, Palette.foreground)
        assertTrue(Palette.isLight)
        assertSame(nord, Palette.theme)

        // Picking another Omarchy theme keeps the pack.
        Palette.apply(themes.getValue("gruvbox"))
        assertEquals("dial-up", ActivePack.pack.id)
        assertEquals(paper, Pack.background)
        assertEquals(paper, Palette.background)

        // Back to Console: the theme picked meanwhile shows.
        ActivePack.choose(PackPick.Console)
        assertSame(Palette.theme, Palette.current)
        assertTodaysTile(ThemePalette(themes.getValue("gruvbox")), ActivePack.look, "gruvbox again")
    }

    @Test fun aPacksTypeAndShapeReachCompose() {
        val manifest =
            consoleManifest(
                mapOf(
                    "type.display.family" to "serif",
                    "type.display.weight" to 800,
                    "type.display.scale" to 1.5,
                    "type.display.italic" to true,
                    "type.display.uppercase" to true,
                    "type.display.tracking" to 0.1,
                    "type.hand.family" to "cursive",
                    "type.body.family" to "sans-serif",
                    "shape.radius" to 8,
                    "shape.corners" to "round",
                    "scrap.border" to 0,
                    "icons.treatment" to "grayscale",
                ),
            )
        val look = PackLook(PackRegistry.choose("typed") { packOf(manifest) }.load!!.pack)
        assertEquals(
            TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize = 18.sp * 1.5f,
                color = look.foreground,
                fontWeight = FontWeight(800),
                fontStyle = FontStyle.Italic,
                letterSpacing = 0.1f.em,
            ),
            look.style(TypeRole.Display, look.sizes.title),
        )
        assertEquals("TITLE", look.cased(TypeRole.Display, "Title"))
        assertEquals(FontFamily.Cursive, look.style(TypeRole.Hand).fontFamily)
        assertEquals(FontFamily.SansSerif, look.style(TypeRole.Body).fontFamily)
        assertEquals(FontFamily.Monospace, look.style(TypeRole.Mono).fontFamily)
        // The pack's corner style wins over the user's, and callers' radii shrink with the pack's.
        assertEquals(RoundedCornerShape(8.dp), look.shape(16.dp, Corners.Edge.scale))
        assertEquals(RoundedCornerShape(10.dp), look.shape(20.dp, Corners.Edge.scale))
        // No outline on scraps, but focus and light outlines still show.
        assertEquals(0.dp, look.line)
        assertEquals(2.dp, look.mark)
        assertEquals(1.dp, look.hair)
        assertNotNull(look.iconFilter)
        assertTrue(!look.monoIcons(true))
        // A font file the pack ships replaces the role's system family once it is read.
        val shipped = FontFamily.Serif
        val withFont = PackLook(PackRegistry.choose("dial-up") { DirectoryPackSource(moduleFile("src/test/resources/packs/dial-up")) }.load!!.pack, mapOf("fonts/pixelify.ttf" to shipped))
        assertEquals(shipped, withFont.style(TypeRole.Body).fontFamily)
        assertEquals(FontFamily.Monospace, withFont.style(TypeRole.Mono).fontFamily)
    }
}
