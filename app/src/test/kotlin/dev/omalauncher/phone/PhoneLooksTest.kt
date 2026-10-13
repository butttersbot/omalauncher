package dev.omalauncher.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneLooksTest {
    private class Looks(
        override var current: String = "riso",
    ) : LookPad {
        override val looks = listOf("console", "bujo", "city-pop", "riso", "tiny-magazine")
        val picked = ArrayList<String>()

        override fun pick(id: String) {
            picked += id
            current = id
        }
    }

    private fun JSONObject.looks() = getJSONArray("looks").let { list -> (0 until list.length()).map(list::getString) }

    @Test fun withNoNameTheLooksAreToldAndNoneIsPicked() {
        val pad = Looks()
        val told = PhoneLooks.reply(JSONObject().put("command", "look"), pad)
        assertEquals("riso", told.getString("look"))
        assertEquals(pad.looks, told.looks())
        assertEquals(emptyList<String>(), pad.picked)
    }

    @Test fun aLookIsPickedAsItIsSaidOrWritten() {
        val pad = Looks()
        assertEquals("tiny-magazine", PhoneLooks.reply(JSONObject().put("command", "look").put("name", " Tiny Magazine "), pad).getString("look"))
        assertEquals("city-pop", PhoneLooks.reply(JSONObject().put("command", "look").put("name", "city_pop"), pad).getString("look"))
        assertEquals(listOf("tiny-magazine", "city-pop"), pad.picked)
        assertNull(PhoneLooks.match(pad.looks, "magazine"))
    }

    @Test fun aNameThatIsNoLookChangesNothing() {
        val pad = Looks()
        assertThrows(IllegalStateException::class.java) { PhoneLooks.reply(JSONObject().put("command", "look").put("name", "neon"), pad) }
        assertEquals(emptyList<String>(), pad.picked)
        assertEquals("riso", pad.current)
    }

    @Test fun lookPassesTheSameGatesAndNeverRunsWhileAnApprovalWaits() {
        assertEquals(PhoneRequests.Route.Looks, PhoneRequests.admit(PhoneLooks.COMMAND, {}) { 0 })
        assertFalse(PhoneLooks.COMMAND in PhoneControlPolicy.whilePending)
        assertThrows(IllegalStateException::class.java) { PhoneRequests.admit(PhoneLooks.COMMAND, {}) { 1 } }
    }
}
