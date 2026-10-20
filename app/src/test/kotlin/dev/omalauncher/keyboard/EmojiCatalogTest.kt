package dev.omalauncher.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiCatalogTest {
    private val catalog by lazy {
        val f = listOf("src/main/assets/emoji/emoji.txt", "app/src/main/assets/emoji/emoji.txt").map(::File).first { it.isFile }
        EmojiCatalog.parse(f.readText())
    }

    @Test fun theSectionsAreApplesInApplesOrder() {
        assertEquals(
            listOf("Smileys & People", "Animals & Nature", "Food & Drink", "Activity", "Travel & Places", "Objects", "Symbols", "Flags"),
            catalog.sections.map { it.title },
        )
        assertEquals("😀", catalog.sections[0].items[0].emoji)
        assertTrue(catalog.sections.sumOf { it.items.size } > 1800)
    }

    @Test fun skinTonesBelongToTheirEmoji() {
        val thumbs = catalog.item("👍")!!
        assertEquals(listOf("👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"), thumbs.tones)
        assertEquals(thumbs, catalog.item("👍🏽"))
        // No tone has a line of its own.
        assertTrue(catalog.sections.flatMap { it.items }.none { "🏽" in it.emoji })
        assertTrue(catalog.item("😀")!!.tones.isEmpty())
        // The keycap # is an emoji, not a section.
        assertEquals("Symbols", catalog.sections.first { s -> s.items.any { it.emoji == "#️⃣" } }.title)
    }

    @Test fun aSearchFindsKeywordsAndPutsWholeWordsFirst() {
        assertTrue(catalog.search("lol").take(6).any { it.emoji == "😂" })
        assertEquals("❤️", catalog.search("red heart").first().emoji)
        assertTrue(catalog.search("thumbs").any { it.emoji == "👍" })
        assertTrue(catalog.search("japan").any { it.emoji == "🇯🇵" })
        assertTrue(catalog.search("  ").isEmpty())
        assertTrue(catalog.search("zzzqqq").isEmpty())
        // Every word must match: "cat face" is the cat faces, not every face.
        assertTrue(catalog.search("cat face").all { r -> r.words.any { "cat" in it.lowercase() } })
    }

    @Test fun whatTheFontCannotDrawIsLeftOut() {
        val only = catalog.filter { it != "😀" && "🏿" !in it }
        assertTrue(only.item("😀") == null)
        assertTrue(only.item("👍")!!.tones.isEmpty())
    }

    @Test fun frequentlyUsedIsOftenAndLately() {
        val u = EmojiUsage()
        repeat(3) { u.used("😂") }
        u.used("🔥")
        assertEquals(listOf("😂", "🔥"), u.frequent())
        // Typed enough lately, a new one moves ahead of an old favourite.
        repeat(4) { u.used("🙏") }
        assertEquals("🙏", u.frequent().first())
        val back = EmojiUsage.parse(u.toText())
        assertEquals(u.frequent(), back.frequent())
    }

    @Test fun aPickedToneIsShownInItsPlace() {
        val thumbs = catalog.item("👍")!!
        val u = EmojiUsage()
        assertEquals("👍", u.shown(thumbs))
        u.pickTone(thumbs, "👍🏾")
        assertEquals("👍🏾", EmojiUsage.parse(u.toText()).shown(thumbs))
        u.pickTone(thumbs, "👍")
        assertEquals("👍", u.shown(thumbs))
    }
}
