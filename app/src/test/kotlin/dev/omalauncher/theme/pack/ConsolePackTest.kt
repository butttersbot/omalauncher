package dev.omalauncher.theme.pack

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.theme.ThemeColors
import dev.omalauncher.ui.Corners
import dev.omalauncher.ui.HomeGap
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ConsolePackTest {
    private val keys = PackSchema.tokens.map { it.key }

    @Test fun everyTokenHasAConsoleValue() {
        assertEquals("a token is declared twice", keys.size, keys.toSet().size)
        assertEquals(keys.toSet(), ConsolePack.tokens.values.keys)
        for (key in keys) assertTrue("$key is in a section named like a key", keys.none { it.startsWith("$key.") })
    }

    /** A token the schema declares but no part of [ThemePack] is built from would be dead weight in every manifest. */
    @Test fun thePackIsBuiltFromEveryToken() {
        val builder = PackBuilder(ConsolePack.tokens, null, null)
        builder.build()
        assertEquals(emptySet<String>(), keys.toSet() - builder.read)
    }

    /** Walks the resolved pack, so a field added to the schema later with no value fails here. */
    @Test fun everyFieldOfThePackIsSet() {
        var leaves = 0

        fun walk(
            value: Any?,
            path: String,
        ) {
            assertNotNull("$path is null", value)
            val type = value!!.javaClass
            if (type.isEnum || type.`package`?.name != ThemePack::class.java.`package`!!.name) {
                leaves++
                return
            }
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                walk(field.get(value), "$path.${field.name}")
            }
        }
        walk(ConsolePack.pack, "pack")
        // One field for each token, and three no token sets: id, name and the named colors.
        assertEquals(keys.size + 3, leaves)
    }

    /** The manifest in the app's assets is Console as a pack author reads it; it must say what the code says. */
    @Test fun theAssetStatesTheSameValues() {
        val dir = moduleFile("src/main/assets/packs/console")
        val load = PackLoader.load(DirectoryPackSource(dir), ConsolePack.ID)
        assertNull(load.error)
        assertEquals(emptyList<PackIssue>(), load.issues)
        assertEquals(ConsolePack.tokens, load.tokens)
        assertEquals(ConsolePack.pack, load.pack)
    }

    @Test fun reproducesTodaysLook() {
        val pack = ConsolePack.pack
        val type = pack.type
        for (font in listOf(type.display, type.body, type.hand, type.mono)) {
            assertEquals(PackFont(FontGeneric.Monospace, "", 400, 1f, italic = false, uppercase = false, tracking = 0f), font)
        }
        assertEquals(PackTextSizes(TextSize.Small.value, TextSize.Body.value, TextSize.Item.value, TextSize.Large.value, TextSize.Title.value), type.sizes)
        assertEquals(listOf(12f, 14f, 15f, 16f, 18f), type.sizes.run { listOf(small, body, item, large, title) })

        assertEquals(HomeGap.value, pack.shape.gap, 0f)
        assertEquals(16f, pack.shape.radius, 0f)
        for (corners in Corners.entries) assertEquals(corners.scale, pack.shape.cornerScale(corners.scale), 0f)

        // Modifier.tile(): frame(2.dp, border or accent, glass).
        val palette = ThemePalette(emptyMap())
        val glass = palette.background.copy(alpha = 0.88f).toArgb()
        for (surface in listOf(pack.scrap.surface, pack.dock.surface)) {
            assertEquals(2f, surface.border, 0f)
            assertEquals(glass, surface.fill.argb)
            assertEquals(palette.border.toArgb(), surface.borderColor.argb)
            assertEquals(palette.accent.toArgb(), surface.focusColor.argb)
            assertEquals(palette.foreground.toArgb(), surface.ink.argb)
            assertFalse(surface.shadow.visible || surface.shadow2.visible)
        }
        assertEquals(0f, pack.scrap.tiltMin, 0f)
        assertEquals(0f, pack.scrap.tiltMax, 0f)
        assertEquals(Dressing.None, pack.scrap.tape.style)
        assertEquals(Dressing.None, pack.scrap.titleBar.style)
        assertEquals(PageKind.System, pack.page.kind)
        assertEquals(IconTreatment.User, pack.icons.treatment)
        assertEquals(DockStyle.Bar, pack.dock.style)
    }

    private fun assertMatches(
        palette: ThemePalette,
        pack: ThemePack,
        what: String,
    ) {
        val p = pack.palette
        val pairs =
            listOf(
                "background" to (palette.background to p.background),
                "surface" to (palette.surface to p.surface),
                "overlay" to (palette.overlay to p.overlay),
                "foreground" to (palette.foreground to p.foreground),
                "muted" to (palette.muted to p.muted),
                "accent" to (palette.accent to p.accent),
                "border" to (palette.border to p.border),
                "red" to (palette.red to p.red),
                "green" to (palette.green to p.green),
                "glass" to (palette.background.copy(alpha = 0.88f) to p.glass),
                // What the tiles are drawn with follows the theme too.
                "scrap fill" to (palette.background.copy(alpha = 0.88f) to pack.scrap.surface.fill),
                "scrap border" to (palette.border to pack.scrap.surface.borderColor),
                "scrap focus" to (palette.accent to pack.scrap.surface.focusColor),
                "scrap ink" to (palette.foreground to pack.scrap.surface.ink),
                "on page" to (palette.foreground to p.onPage),
            )
        for ((name, pair) in pairs) assertEquals("$what: $name", "#%08x".format(pair.first.toArgb()), pair.second.toString())
        assertEquals("$what: mode", palette.isLight, p.isLight)
        assertEquals("$what: named", palette.named.mapValues { (_, c: Color) -> c.toArgb() }, p.named.mapValues { it.value.argb })
    }

    /** With no theme applied, Console's own palette is ThemePalette's fallbacks. */
    @Test fun itsOwnColorsAreThemePalettesFallbacks() {
        assertMatches(ThemePalette(emptyMap()), ConsolePack.pack, "console")
        assertMatches(ThemePalette(emptyMap()), ConsolePack.withColors(emptyMap()), "no colors")
        assertEquals(ConsolePack.pack, ConsolePack.withColors(emptyMap()))
    }

    /** AC-21: each of the Omarchy themes shipped in the assets, drawn with Console, has the colors ThemePalette gives it. */
    @Test fun takesAnOmarchyThemesColors() {
        val themes = moduleFile("src/main/assets/themes").listFiles { f -> f.name.endsWith(".toml") }!!
        assertEquals(22, themes.size)
        for (file in themes) {
            val colors = ThemeColors.load(file)
            val pack = ConsolePack.withColors(colors)
            assertMatches(ThemePalette(colors), pack, file.name)
            // Only colors change: everything else is still Console.
            assertEquals(ConsolePack.pack.type, pack.type)
            assertEquals(ConsolePack.pack.shape, pack.shape)
        }
        assertTrue(ConsolePack.withColors(ThemeColors.load(themes.first { it.name == "catppuccin-latte.toml" })).palette.isLight)
    }

    @Test fun fallsBackColorByColor() {
        val colors = mapOf("accent" to "#ff0000", "foreground" to "not a color", "red" to "#12", "mode" to "light", "background" to "#80102030")
        val pack = ConsolePack.withColors(colors)
        assertMatches(ThemePalette(colors), pack, "partial")
        assertEquals("#ffff0000", pack.palette.accent.toString())
        assertEquals(ConsolePack.pack.palette.foreground, pack.palette.foreground)
        assertEquals(ConsolePack.pack.palette.red, pack.palette.red)
        // An alpha of the theme's own is replaced where Console sets one, as Color.copy does.
        assertEquals("#e0102030", pack.palette.glass.toString())
    }

    @Test fun readsColorsStrictly() {
        assertEquals(0xFF7AA2F7.toInt(), PackColor.parse("#7aa2f7")!!.argb)
        assertEquals(0x807AA2F7.toInt(), PackColor.parse("807AA2F7")!!.argb)
        for (bad in listOf("", "#", "#fff", "#12345", "#1234567", "#123456789", "-7aa2f7", "+7aa2f", "#7aa2fg", "0x7aa2f7", "red")) {
            assertNull(bad, PackColor.parse(bad))
        }
    }
}
