package dev.omalauncher.theme.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PRD AC-19: no home, drawer, dock or Oma composable reads a font, shape, border or color
 * except through the active pack. That rules out both the accessors in ui/Theme.kt (Palette,
 * Mono, TextSize, HomeGap, LauncherStyle, Corners, frame(), tile(), hexColor()) and Compose's
 * own (FontFamily, RoundedCornerShape, CutCornerShape, border(), Color(…), Color.White and the
 * like). Home, the drawer, the dock and the Oma chat draw from the pack and are listed in
 * [converted]; this fails for a listed file that has a reference, and counts and prints the
 * rest (settings, setup, the store, files, terminals), which are still on the old accessors.
 * The adapter itself (ui/PackAdapter.kt, ui/ScrapFrame.kt, ui/PagePaper.kt) is where a pack's
 * numbers become Compose's colors and shapes, so it is counted but can't be listed.
 */
class PackSourceCheckTest {
    /**
     * Files already converted to draw from the pack, as paths under the source root
     * ("ui/Home.kt"). Add each file here as it is converted, and it can't slip back.
     */
    private val converted =
        setOf(
            // Home: the page, its tiles, folders, the edit bar and the long-press menu.
            "ui/Home.kt",
            "ui/HomeGrid.kt",
            "ui/WidgetTile.kt",
            "ui/ShortcutTile.kt",
            "ui/Folders.kt",
            "ui/HomeMenu.kt",
            // A thread's tile and the dot of its state (Boss, 2026-10-13), drawn from the pack from the start.
            "ui/AgentTile.kt",
            // Home's gestures and the overview of all pages.
            "ui/HomeGestures.kt",
            "ui/Overview.kt",
            // Writing on pages: the ink and the drawing tools, sticky notes and stickers.
            "ui/Ink.kt",
            "ui/Notes.kt",
            "ui/Stickers.kt",
            // The drawer, the dock, the agent button and the rails.
            "ui/AppDrawer.kt",
            "ui/AppSearch.kt",
            "ui/Dock.kt",
            "ui/AgentButton.kt",
            "ui/SideRail.kt",
            // What those share with every other screen.
            "ui/Components.kt",
            "ui/Icons.kt",
            "ui/IconMask.kt",
            // The Oma chat and the listening screen.
            "oma/OmaChatActivity.kt",
            // Boss 2026-10-13: the thread dots and tabs follow design/oma-threads-mockup.html exactly ("The pulse just use the HTML code").
            // Their dots are round in every look, as the mock-up's are; their colours are still the pack's.
            "oma/ThreadDots.kt",
            "oma/MarkdownText.kt",
            "assist/ListeningScreen.kt",
        )

    /** Where the accessors are defined, which is counted apart from the files that use them. */
    private val definitions = "ui/Theme.kt"

    /**
     * Names are matched as whole words wherever they stand, so `with(Palette) { … }` and
     * `val p = Palette` count as much as `Palette.accent`.
     */
    private val patterns =
        linkedMapOf(
            "Palette" to Regex("""\bPalette\b"""),
            "Mono" to Regex("""\bMono\b"""),
            "TextSize" to Regex("""\bTextSize\b"""),
            "HomeGap" to Regex("""\bHomeGap\b"""),
            "LauncherStyle" to Regex("""\bLauncherStyle\b"""),
            "Corners" to Regex("""\bCorners\b"""),
            ".frame(" to Regex("""\.frame\s*\("""),
            ".tile(" to Regex("""\.tile\s*\("""),
            "hexColor(" to Regex("""\bhexColor\s*\("""),
            "FontFamily" to Regex("""\bFontFamily\b"""),
            "CornerShape" to Regex("""\b(?:Rounded|Cut)CornerShape\b"""),
            ".border(" to Regex("""\.border\s*\("""),
            "Color(" to Regex("""\bColor\s*\("""),
            "Color.<constant>" to Regex("""\bColor\s*\.\s*[A-Z]\w*"""),
        )

    /** The references in one file by pattern, leaving out imports and comments. */
    private fun uses(file: File): Map<String, Int> {
        val code =
            file.readLines().map { it.trim() }.filterNot {
                it.startsWith("import ") || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
            }
        return patterns.mapValues { (_, pattern) -> code.sumOf { pattern.findAll(it).count() } }.filterValues { it > 0 }
    }

    @Test fun composablesDrawOnlyFromThePack() {
        val root = moduleFile("src/main/kotlin/dev/omalauncher")
        assertTrue("the launcher's sources aren't at $root", File(root, "ui").isDirectory)
        // oma/ arrives with a later slice.
        val files = listOf("ui", "oma").map { File(root, it) }.filter { it.isDirectory }.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" } }
        val found = files.associate { it.relativeTo(root).invariantSeparatorsPath to uses(it) }.filterValues { it.isNotEmpty() }.toSortedMap()

        val total = found.values.sumOf { it.values.sum() }
        val own = found[definitions]?.values?.sum() ?: 0
        println("AC-19 source check: $total references in ${found.size} of ${files.size} files under ui/ and oma/ (${total - own} outside $definitions)")
        for (name in patterns.keys) println("  %-20s %d".format(name, found.values.sumOf { it[name] ?: 0 }))
        for ((file, counts) in found) println("  %-28s %4d  %s".format(file, counts.values.sum(), counts.entries.joinToString(", ") { "${it.key} ${it.value}" }))

        for (file in converted) assertTrue("$file is listed as converted but isn't a source file", File(root, file).isFile)
        assertEquals("converted files that still read a font, shape, border or color directly", emptyMap<String, Map<String, Int>>(), found.filterKeys { it in converted })
    }

    /**
     * PRD AC-18: a theme never moves an item. The grid, the notebook and the fold rule don't know there
     * is a pack, so nothing a pack says can reach a page or a cell; and home's gaps are [PageGap]'s
     * fixed 6dp, not the pack's.
     */
    @Test fun theGridNeverAsksThePack() {
        val root = moduleFile("src/main/kotlin/dev/omalauncher")
        val layout = File(root, "widgets/layout").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("the grid's sources aren't under $root", layout.isNotEmpty())
        val asks = Regex("""\b(?:ActivePack|PackLook|ThemePack|Pack\s*\.)|theme\.pack""")
        assertEquals(emptyList<String>(), layout.filter { file -> file.readLines().any { asks.containsMatchIn(it) } }.map { it.name })
        // Where home lays out by a gap, it is the fixed one.
        val gap = Regex("""shape\s*\.\s*gap""")
        val reads =
            listOf("ui", "oma").flatMap { File(root, it).walkTopDown().filter { f -> f.extension == "kt" } }.filter { file ->
                file.readLines().map { it.trim() }.any { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") && gap.containsMatchIn(it) }
            }
        assertEquals(emptyList<String>(), reads.map { it.name })
        assertEquals(6f, ConsolePack.pack.shape.gap, 0f)
    }

    /** Every file the slice set out to convert is on the list, so none can be dropped from it quietly. */
    @Test fun theConvertedListIsWhole() {
        val expected =
            listOf("Home", "HomeGrid", "WidgetTile", "ShortcutTile", "Folders", "HomeMenu", "HomeGestures", "Overview", "AppDrawer", "AppSearch", "Dock", "AgentButton", "SideRail", "Components", "Icons", "IconMask", "Ink", "Notes", "Stickers", "AgentTile")
                .map { "ui/$it.kt" } + "oma/OmaChatActivity.kt" + "oma/ThreadDots.kt" + "oma/MarkdownText.kt" + "assist/ListeningScreen.kt"
        assertEquals(expected.toSet(), converted)
    }

    @Test fun countsReferencesButNotImportsOrComments() {
        val file = File.createTempFile("uses", ".kt")
        try {
            file.writeText(
                """
                import androidx.compose.ui.text.font.FontFamily
                import androidx.compose.foundation.border
                import dev.omalauncher.ui.Palette
                // Palette.accent is mentioned here
                /** and RoundedCornerShape(4.dp) here */
                val a = TextStyle(fontFamily = FontFamily.Monospace, fontSize = TextSize.Body)
                val b = Modifier.border(1.dp, Palette.border, RoundedCornerShape(4.dp)).background(Color(0xFF000000))
                val c = ThemePalette.something + borderless(Color.Red) + Color(0XFF112233) + Color(red = 1f, green = 0f, blue = 0f)
                val d = with(Palette) { accent }
                val p = Palette
                val e = Modifier.clip(CutCornerShape(4.dp)).frame(2.dp, Color.White).tile()
                val f = Text("x", style = Mono, color = hexColor("#fff") ?: Color.Unspecified)
                val g = Modifier.padding(HomeGap).clip(LauncherStyle.shape()).also { Corners.Edge }
                val h = Monospace + myColor.copy() + Colors.x + profile(1) + reframe(2) + "tile"
                """.trimIndent(),
            )
            assertEquals(
                mapOf(
                    "Palette" to 3,
                    "Mono" to 1,
                    "TextSize" to 1,
                    "HomeGap" to 1,
                    "LauncherStyle" to 1,
                    "Corners" to 1,
                    ".frame(" to 1,
                    ".tile(" to 1,
                    "hexColor(" to 1,
                    "FontFamily" to 1,
                    "CornerShape" to 2,
                    ".border(" to 1,
                    "Color(" to 3,
                    "Color.<constant>" to 3,
                ),
                uses(file),
            )
        } finally {
            file.delete()
        }
    }
}
