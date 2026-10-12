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
 * like). Nothing draws from the pack yet, so for now this counts the references and prints
 * them; it fails only for a file listed in [converted] that still has one.
 */
class PackSourceCheckTest {
    /**
     * Files already converted to draw from the pack, as paths under the source root
     * ("ui/Home.kt"). Add each file here as it is converted, and it can't slip back.
     */
    private val converted = setOf<String>()

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
