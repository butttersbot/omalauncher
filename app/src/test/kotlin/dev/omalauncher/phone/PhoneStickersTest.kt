package dev.omalauncher.phone

import dev.omalauncher.widgets.annotations.Annotations
import dev.omalauncher.widgets.annotations.LibrarySticker
import dev.omalauncher.widgets.annotations.NoteLeft
import dev.omalauncher.widgets.annotations.PageBoard
import dev.omalauncher.widgets.annotations.Sticker
import dev.omalauncher.widgets.annotations.StickerChoice
import dev.omalauncher.widgets.annotations.StickerGroup
import dev.omalauncher.widgets.annotations.StickerKind
import dev.omalauncher.widgets.annotations.StickerLibrary
import dev.omalauncher.widgets.annotations.StickyNote
import dev.omalauncher.widgets.annotations.fitted
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneStickersTest {
    private val library =
        StickerLibrary(
            File("/library"),
            listOf(
                StickerGroup("", listOf(LibrarySticker("moon.png", "moon", ""))),
                StickerGroup("cove", listOf(LibrarySticker("cove/butterfly.png", "butterfly", "cove"), LibrarySticker("cove/star.png", "star", "cove"))),
                StickerGroup("frogs", listOf(LibrarySticker("frogs/butterfly.png", "butterfly", "frogs"))),
            ),
        )

    /** Home with two pages, "p1" called Today and "p2" called Trips, and what is stuck on the first. */
    private class Home(
        var stickers: List<Sticker> = emptyList(),
        val library: StickerLibrary? = null,
        val notes: List<StickyNote> = emptyList(),
    ) : NotePad, StickerPad {
        /** Every id the book was asked to take off. */
        val removed = ArrayList<String>()
        val stuck = ArrayList<Pair<StickerChoice, Pair<Float, Float>?>>()
        override val asked: String? = "p1"
        override val showing: String? = "p1"
        override val pages = listOf("p1", "p2")

        override fun name(page: String) = mapOf("p1" to "Today", "p2" to "Trips")[page]

        override fun leave(
            page: String,
            text: String,
            near: Pair<Int, Int>?,
            then: (NoteLeft) -> Unit,
        ) = error("No note is left by a sticker command.")

        override fun look(
            page: String,
            then: (PageBoard, Annotations?) -> Unit,
        ) = then(PageBoard(9), if (page == "p1") Annotations(notes = notes, stickers = stickers) else Annotations())

        override fun library(then: (StickerLibrary?) -> Unit) = then(library)

        override fun stick(
            page: String,
            choice: StickerChoice,
            near: Pair<Float, Float>?,
            then: (String?) -> Unit,
        ) {
            stuck += choice to near
            stickers = stickers + Sticker("s9", StickerKind.Star, near?.first ?: 0.5f, near?.second ?: 0.25f, 0.14f)
            then("s9")
        }

        override fun change(
            page: String,
            id: String,
            edit: (Sticker) -> Sticker,
        ): Boolean {
            stickers = stickers.map { if (it.id == id) edit(it).fitted() else it }
            return true
        }

        override fun remove(
            page: String,
            id: String,
        ): Boolean {
            removed += id
            stickers = stickers.filter { it.id != id }
            return true
        }
    }

    private fun ask(
        home: Home,
        vararg pairs: Pair<String, String>,
    ): JSONObject {
        var answer: JSONObject? = null
        val input = JSONObject().put("command", "sticker")
        for ((key, value) in pairs) input.put(key, value)
        PhoneStickers.reply(input, home, home) { answer = it }
        return answer!!
    }

    @Test fun aStickerIsPickedByItsWordItsFullNameOrItsNameAlone() {
        // The built-in star wins over a picture that happens to be called star: that one is asked for by its folder.
        assertEquals(PhoneStickers.Picked.One(StickerChoice.BuiltIn(StickerKind.Star), "star"), PhoneStickers.pick(" Star ", library))
        assertEquals(PhoneStickers.Picked.One(StickerChoice.Pictured(File("/library/cove/star.png"), "star"), "cove/star"), PhoneStickers.pick("cove/star", library))
        assertEquals(PhoneStickers.Picked.One(StickerChoice.Pictured(File("/library/moon.png"), "moon"), "moon"), PhoneStickers.pick("MOON", library))
        assertEquals(PhoneStickers.Picked.Several(listOf("cove/butterfly", "frogs/butterfly")), PhoneStickers.pick("butterfly", library))
        assertEquals(PhoneStickers.Picked.None, PhoneStickers.pick("pepe", library))
        assertEquals(PhoneStickers.Picked.None, PhoneStickers.pick("moon", null))
    }

    @Test fun theStickersThereAreAreListed() {
        val told = ask(Home(library = library), "action" to "list")
        assertTrue(told.getBoolean("ok"))
        assertEquals(StickerKind.entries.size, told.getJSONArray("builtIn").length())
        val pictures = told.getJSONArray("pictures")
        assertEquals(listOf("moon", "cove/butterfly", "cove/star", "frogs/butterfly"), (0 until pictures.length()).map(pictures::getString))
        assertEquals(0, ask(Home(), "action" to "list").getJSONArray("pictures").length())
    }

    @Test fun aStickerIsStuckNearACellAndToldWithItsId() {
        val home = Home(library = library)
        val told = ask(home, "action" to "add", "name" to "heart", "near" to "3,8")
        assertTrue(told.toString(), told.getBoolean("ok"))
        assertEquals("s9", told.getString("id"))
        assertEquals("Today", told.getString("page"))
        val (choice, near) = home.stuck.single()
        assertEquals(StickerChoice.BuiltIn(StickerKind.Heart), choice)
        // The middle of cell 3,8 of a page six cells by nine: in its right half and near its foot.
        assertTrue(near!!.first > 0.5f && near.first < 0.7f && near.second > 0.85f)
        assertEquals(3, told.getJSONObject("cells").getInt("col"))
    }

    @Test fun aNameThatIsNoStickerOrTwoSticksNothing() {
        val home = Home(library = library)
        assertFalse(ask(home, "action" to "add", "name" to "pepe").getBoolean("ok"))
        val two = ask(home, "action" to "add", "name" to "butterfly")
        assertFalse(two.getBoolean("ok"))
        assertEquals(2, two.getJSONArray("matches").length())
        assertTrue(ask(home, "action" to "add", "name" to "heart", "near" to "9,9").getString("error").contains("off the page"))
        assertTrue(home.stuck.isEmpty())
    }

    @Test fun aStickerIsTurnedResizedMovedAndTakenOffByItsId() {
        val other = Sticker("s2", StickerKind.Heart, 0.2f, 0.2f, 0.14f)
        val home = Home(listOf(Sticker("s1", StickerKind.Star, 0.5f, 0.5f, 0.14f), other))
        assertEquals(30, ask(home, "action" to "turn", "id" to "s1", "value" to "30").getInt("turn"))
        assertEquals(25, ask(home, "action" to "size", "id" to "s1", "value" to "25").getInt("size"))
        val moved = ask(home, "action" to "move", "id" to "s1", "value" to "0,0")
        assertEquals(0, moved.getJSONObject("cells").getInt("col"))
        assertEquals(0, moved.getJSONObject("cells").getInt("row"))
        // The other sticker is as it was.
        assertEquals(other, home.stickers.single { it.id == "s2" })
        assertEquals("s1", ask(home, "action" to "remove", "id" to "s1").getString("removed"))
        assertEquals(listOf("s2"), home.stickers.map { it.id })
    }

    @Test fun whatIsNotAStickerOnThatPageOrNoSizeChangesNothing() {
        val home = Home(listOf(Sticker("s1", StickerKind.Star, 0.5f, 0.5f, 0.14f)))
        val before = home.stickers
        val wrong =
            listOf(
                listOf("action" to "turn", "id" to "n1", "value" to "15"),
                listOf("action" to "remove", "id" to "s7"),
                listOf("action" to "size", "id" to "s1", "value" to "80"),
                listOf("action" to "turn", "id" to "s1", "value" to "a lot"),
                listOf("action" to "move", "id" to "s1", "value" to "here"),
                listOf("action" to "remove", "id" to "s1", "page" to "Trips"),
            )
        for (pairs in wrong) {
            val told = ask(home, *pairs.toTypedArray())
            assertFalse(told.toString(), told.getBoolean("ok"))
        }
        assertEquals(before, home.stickers)
        assertThrows(IllegalStateException::class.java) { ask(home, "action" to "remove", "id" to "s1", "page" to "holiday") }
        assertThrows(IllegalStateException::class.java) { ask(home, "action" to "polish", "id" to "s1") }
    }

    @Test fun aStickerGrownOverAnotherIsToldAsNotClear() {
        val home = Home(listOf(Sticker("s1", StickerKind.Star, 0.3f, 0.3f, 0.1f), Sticker("s2", StickerKind.Heart, 0.5f, 0.3f, 0.1f)))
        assertTrue(ask(home, "action" to "turn", "id" to "s1", "value" to "15").getBoolean("clear"))
        // Grown to half the page's width it lies over its neighbour: done as asked, and said.
        val grown = ask(home, "action" to "size", "id" to "s1", "value" to "50")
        assertTrue(grown.getBoolean("ok"))
        assertFalse(grown.getBoolean("clear"))
    }

    @Test fun aNoteIsNeverTakenOffOrChangedByItsId() {
        // The owner's note on the very page asked about: its id is no sticker's, so the book is never asked.
        val home = Home(listOf(Sticker("s1", StickerKind.Star, 0.5f, 0.5f, 0.14f)), notes = listOf(StickyNote("n1", 0.1f, 0.1f, 0.44f, 0.16f, "call mum")))
        for (action in listOf("remove", "turn", "size", "move")) {
            val told = ask(home, "action" to action, "id" to "n1", "value" to if (action == "move") "1,1" else "20")
            assertFalse(told.toString(), told.getBoolean("ok"))
            assertTrue(told.getString("error").contains("No sticker n1"))
        }
        assertTrue(home.removed.isEmpty())
        assertEquals(listOf("s1"), home.stickers.map { it.id })
    }

    @Test fun stickerCommandsPassTheSameGatesAndNeverRunWhileAnApprovalWaits() {
        assertEquals(PhoneRequests.Route.Stickers, PhoneRequests.admit(PhoneStickers.COMMAND, {}) { 0 })
        assertFalse(PhoneStickers.COMMAND in PhoneControlPolicy.whilePending)
        assertThrows(IllegalStateException::class.java) { PhoneRequests.admit(PhoneStickers.COMMAND, {}) { 1 } }
    }
}
