package dev.omalauncher.theme.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackManifestTest {
    @Test fun readsSectionsStringsNumbersAndFlags() {
        val parsed =
            PackManifest.parse(
                """
                # a pack
                top = 1
                [pack]
                format = 1          # trailing comment
                name = "Riso # two"  # the # inside the quotes is the name's
                [scrap.tape]
                color = '#ff3d8b'
                opacity = .5
                angle = -3
                wide = 1e2
                on = true
                off = false
                sub.key = "dotted"
                """.trimIndent(),
            )
        assertEquals(emptyList<String>(), parsed.problems)
        assertEquals(
            mapOf<String, Any>(
                "top" to 1L,
                "pack.format" to 1L,
                "pack.name" to "Riso # two",
                "scrap.tape.color" to "#ff3d8b",
                "scrap.tape.opacity" to 0.5,
                "scrap.tape.angle" to -3L,
                "scrap.tape.wide" to 100.0,
                "scrap.tape.on" to true,
                "scrap.tape.off" to false,
                "scrap.tape.sub.key" to "dotted",
            ),
            parsed.values,
        )
    }

    @Test fun readsEscapes() {
        val parsed = PackManifest.parse("""a = "q\" b\\ n\n t\t u\u00d7"""")
        assertEquals("q\" b\\ n\n t\t u×", parsed.values["a"])
    }

    @Test fun skipsWhatItCantReadAndSaysWhere() {
        val parsed =
            PackManifest.parse(
                listOf(
                    "[pack]",
                    "format = 1",
                    "list = [1, 2]",
                    "table = { a = 1 }",
                    "open = \"never closed",
                    "junk = \"a\" b",
                    "bad = \"\\q\"",
                    "date = 2026-10-11",
                    "just some words",
                    "format = 2",
                    "[[array.of.tables]]",
                    "hidden = 1",
                    "[fine]",
                    "kept = 1",
                    "x".repeat(2000) + " = 1",
                ).joinToString("\n"),
            )
        assertEquals(mapOf<String, Any>("pack.format" to 1L, "fine.kept" to 1L), parsed.values)
        assertEquals(listOf(9, 10, 11, 15), parsed.problems.map { it.substringAfter("line ").substringBefore(':').toInt() })
        // A key set to something unreadable is remembered as that, not lost among the lines.
        assertEquals(listOf("pack.list", "pack.table", "pack.open", "pack.junk", "pack.bad", "pack.date"), parsed.unreadable.keys.toList())
        assertEquals(listOf(3, 4, 5, 6, 7, 8), parsed.unreadable.values.map { it.substringAfter("line ").substringBefore(':').toInt() })
    }

    @Test fun neverThrowsOnNoise() {
        val random = java.util.Random(7)
        val alphabet = "[]=\"'#.\\ \n\tabc019-_+eE\u0000\uFEFF×"
        repeat(2000) {
            val text = String(CharArray(random.nextInt(200)) { alphabet[random.nextInt(alphabet.length)] })
            PackManifest.parse(text)
        }
        // A line long enough to exhaust a backtracking pattern, were it given to one.
        val parsed = PackManifest.parse("a" + ".a".repeat(30_000) + " = 1\n[" + "b.".repeat(30_000) + "]\nc = " + "9".repeat(50_000))
        assertTrue(parsed.values.isEmpty())
        assertEquals(3, parsed.problems.size)
    }

    @Test fun writesWhatItReadsBack() {
        val values =
            linkedMapOf<String, Any>(
                "pack.format" to 1L,
                "pack.name" to "A \"quoted\" \\ name\twith\ncontrol \u0001 and ×",
                "shape.radius" to 16.0,
                "shape.gap" to 0.1f.toDouble(),
                "scrap.tape.angle" to -3L,
                "scrap.dashed" to true,
                "scrap.tape.color" to "accent@0.5",
            )
        assertEquals(values.mapValues { (_, v) -> if (v == 16.0) 16L else v }, PackManifest.parse(PackManifest.write(values)).values)
    }
}
