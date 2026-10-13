package dev.omalauncher.phone

import dev.omalauncher.widgets.Cell
import dev.omalauncher.widgets.annotations.Annotations
import dev.omalauncher.widgets.annotations.NoteLeft
import dev.omalauncher.widgets.annotations.PageBoard
import dev.omalauncher.widgets.annotations.PageTile
import dev.omalauncher.widgets.annotations.Sticker
import dev.omalauncher.widgets.annotations.StickerKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePageEditsTest {
    /** A notebook of named pages: "Today" has a tile, "Trips" a sticker, "Spare" nothing; [unread] can't be read. */
    private class Home(
        val unread: String? = null,
    ) : NotePad, PagePad {
        val names = linkedMapOf("p1" to "Today", "p2" to "Trips", "p3" to "Spare")
        override val asked: String? = null
        override val showing: String? = "p1"
        override val pages get() = names.keys.toList()

        override fun name(page: String) = names[page]

        override fun leave(
            page: String,
            text: String,
            near: Pair<Int, Int>?,
            then: (NoteLeft) -> Unit,
        ) = error("No note is left by a page command.")

        override fun look(
            page: String,
            then: (PageBoard, Annotations?) -> Unit,
        ) = then(
            PageBoard(9, if (page == "p1") listOf(PageTile("Camera", Cell(0, 0, 1, 1), "app")) else emptyList()),
            when (page) {
                unread -> null
                "p2" -> Annotations(stickers = listOf(Sticker("s1", StickerKind.Star, 0.5f, 0.5f, 0.14f)))
                else -> Annotations()
            },
        )

        override fun add(): String = "p${names.size + 1}".also { names[it] = "Page ${names.size + 1}" }

        override fun taken(
            name: String,
            except: String?,
        ) = names.any { it.key != except && it.value.equals(name, ignoreCase = true) }

        override fun rename(
            page: String,
            name: String,
        ): Boolean {
            if (name.isBlank()) return false
            names[page] = name
            return true
        }

        override fun delete(page: String) = names.size > 1 && names.remove(page) != null
    }

    private fun ask(
        home: Home,
        vararg pairs: Pair<String, String>,
    ): JSONObject {
        var answer: JSONObject? = null
        val input = JSONObject().put("command", "pageedit")
        for ((key, value) in pairs) input.put(key, value)
        PhonePageEdits.reply(input, home, home) { answer = it }
        return answer!!
    }

    private fun JSONObject.pages() = getJSONArray("pages").let { list -> (0 until list.length()).map(list::getString) }

    @Test fun aPageIsAddedLastWithItsNameOrTheNotebooksOwn() {
        val home = Home()
        assertEquals("Page 4", ask(home, "action" to "add").getString("page"))
        val named = ask(home, "action" to "add", "name" to " Recipes ")
        assertEquals("Recipes", named.getString("page"))
        assertEquals(listOf("Today", "Trips", "Spare", "Page 4", "Recipes"), named.pages())
        // A name another page has is refused before a page is made.
        assertThrows(IllegalStateException::class.java) { ask(home, "action" to "add", "name" to "today") }
        assertEquals(5, home.names.size)
    }

    @Test fun aPageIsRenamedUnlessTheNameIsTakenOrNoName() {
        val home = Home()
        assertEquals(listOf("Today", "Holidays", "Spare"), ask(home, "action" to "rename", "name" to "trips", "to" to "Holidays").pages())
        // Its own name in other capitals is no clash.
        assertEquals("TODAY", ask(home, "action" to "rename", "name" to "Today", "to" to "TODAY").getString("page"))
        assertThrows(IllegalStateException::class.java) { ask(home, "action" to "rename", "name" to "Spare", "to" to "holidays") }
        assertThrows(IllegalStateException::class.java) { ask(home, "action" to "rename", "name" to "Spare", "to" to "  ") }
        assertThrows(IllegalStateException::class.java) { ask(home, "action" to "rename", "name" to "Nowhere", "to" to "X") }
        assertEquals(listOf("TODAY", "Holidays", "Spare"), home.names.values.toList())
    }

    @Test fun onlyAnEmptyPageIsDeleted() {
        val home = Home()
        val gone = ask(home, "action" to "delete", "name" to "Spare")
        assertTrue(gone.getBoolean("ok"))
        assertEquals("Spare", gone.getString("deleted"))
        assertEquals(listOf("Today", "Trips"), gone.pages())
        // A tile, or a sticker, keeps a page.
        for (kept in listOf("Today", "Trips")) {
            val told = ask(home, "action" to "delete", "name" to kept)
            assertFalse(told.getBoolean("ok"))
            assertTrue(told.getString("error").contains("has things on it"))
        }
        assertEquals(listOf("Today", "Trips"), home.names.values.toList())
        // A page whose notes can't be read is not known to be empty.
        val unread = Home(unread = "p3")
        assertFalse(ask(unread, "action" to "delete", "name" to "Spare").getBoolean("ok"))
        assertEquals(3, unread.names.size)
    }

    @Test fun pageEditsPassTheSameGatesAndNeverRunWhileAnApprovalWaits() {
        assertEquals(PhoneRequests.Route.PageEdits, PhoneRequests.admit(PhonePageEdits.COMMAND, {}) { 0 })
        assertFalse(PhonePageEdits.COMMAND in PhoneControlPolicy.whilePending)
        assertThrows(IllegalStateException::class.java) { PhoneRequests.admit(PhonePageEdits.COMMAND, {}) { 1 } }
        assertThrows(IllegalStateException::class.java) { ask(Home(), "action" to "shuffle") }
    }
}
