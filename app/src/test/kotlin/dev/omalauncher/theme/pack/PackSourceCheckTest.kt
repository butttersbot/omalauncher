package dev.omalauncher.theme.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PRD AC-19: no home, drawer, dock or Oma composable reads a font, shape, border or color
 * except through the active pack. Nothing draws from the pack yet, so for now this counts the
 * direct uses and prints them; it fails only for a file listed in [converted] that still has one.
 */
class PackSourceCheckTest {
    /**
     * Files already converted to draw from the pack, as paths under the source root
     * ("ui/Home.kt"). Add each file here as it is converted, and it can't slip back.
     */
    private val converted = setOf<String>()

    private val patterns =
        linkedMapOf(
            "FontFamily" to Regex("""\bFontFamily\b"""),
            "RoundedCornerShape" to Regex("""\bRoundedCornerShape\b"""),
            ".border(" to Regex("""\.border\("""),
            "Color(0x" to Regex("""\bColor\(0x"""),
            "Palette." to Regex("""\bPalette\."""),
        )

    /** The uses in one file by pattern, leaving out imports and comments. */
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
        println("AC-19 source check: $total direct uses in ${found.size} of ${files.size} files under ui/ and oma/")
        for (name in patterns.keys) println("  %-20s %d".format(name, found.values.sumOf { it[name] ?: 0 }))
        for ((file, counts) in found) println("  %-28s %4d  %s".format(file, counts.values.sum(), counts.entries.joinToString(", ") { "${it.key} ${it.value}" }))

        for (file in converted) assertTrue("$file is listed as converted but isn't a source file", File(root, file).isFile)
        assertEquals("converted files that still read a font, shape, border or color directly", emptyMap<String, Map<String, Int>>(), found.filterKeys { it in converted })
    }

    @Test fun countsUsesButNotImportsOrComments() {
        val file = File.createTempFile("uses", ".kt")
        try {
            file.writeText(
                """
                import androidx.compose.ui.text.font.FontFamily
                import androidx.compose.foundation.border
                // Palette.accent is mentioned here
                /** and RoundedCornerShape(4.dp) here */
                val a = TextStyle(fontFamily = FontFamily.Monospace)
                val b = Modifier.border(1.dp, Palette.border, RoundedCornerShape(4.dp)).background(Color(0xFF000000))
                val c = ThemePalette.something + borderless(Color.Red)
                """.trimIndent(),
            )
            assertEquals(mapOf("FontFamily" to 1, "RoundedCornerShape" to 1, ".border(" to 1, "Color(0x" to 1, "Palette." to 1), uses(file))
        } finally {
            file.delete()
        }
    }
}
